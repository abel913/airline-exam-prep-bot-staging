package com.airlineprep.bot.question;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.airlineprep.bot.examtype.ExamTypeRepository;
import com.airlineprep.bot.category.CategoryRepository;
import com.airlineprep.bot.settings.SettingsService;
import com.airlineprep.bot.audit.AdminChangeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Service
public class QuestionImportService {
 private final QuestionFileParser parser;
 private final ImportBatchRepository batches;
 private final ImportRowRepository rows;
 private final QuestionValidationService validation;
 private final QuestionDuplicateService duplicates;
 private final QuestionService questions;
 private final ExamTypeRepository exams;
 private final CategoryRepository categories;
 private final SettingsService settings;
 private final AdminChangeService changes;
 private final ObjectMapper json;
 public QuestionImportService(QuestionFileParser p,ImportBatchRepository b,ImportRowRepository r,QuestionValidationService v,
  QuestionDuplicateService d,QuestionService q,ExamTypeRepository e,CategoryRepository c,SettingsService s,AdminChangeService a,ObjectMapper j) {
  parser=p;batches=b;rows=r;validation=v;duplicates=d;questions=q;exams=e;categories=c;settings=s;changes=a;json=j;
 }
 // Parsing occurs before the transaction: no database lock is held while reading a file.
 public QuestionFileParser.Parsed parse(MultipartFile file) { return parser.parse(file); }
 @Transactional
 public long stage(QuestionFileParser.Parsed parsed,String actor) {
  settings.lock();
  ImportBatch b=new ImportBatch(); b.filename=parsed.filename(); b.fileType=parsed.type(); b.actor=actor;
  b.status=ImportStatus.VALIDATED; batches.saveAndFlush(b);
  Set<String> exact=new HashSet<>(),stems=new HashSet<>();
  for(var parsedRow:parsed.rows()) {
   ImportRow row=new ImportRow(); row.batchId=b.id; row.rowNumber=parsedRow.number();
   Map<String,String> values=parsedRow.values();
   row.questionPreview=clip(values.getOrDefault("question",""),300);
   row.examCode=clip(values.getOrDefault("exam_type",""),40); row.categoryCode=clip(values.getOrDefault("category",""),40);
   row.useStatus=clip(values.getOrDefault("copyright_status",""),32);
   row.errors=parsedRow.error(); row.duplicateStatus=DuplicateStatus.UNIQUE; row.duplicateReason="";
   QuestionForm form=null;
   try {
    form=convert(values);
    row.errors=clip(row.errors+" "+String.join(" ",validation.errors(form,true,false)),2000).strip();
   } catch(IllegalArgumentException e) { row.errors=clip(row.errors+" "+e.getMessage(),2000).strip(); }
   row.payload=encode(values);
   if(row.errors.isBlank()&&form!=null) {
    var duplicate=duplicates.check(form); row.duplicateStatus=duplicate.status();row.duplicateReason=duplicate.reason();
    String key=form.examTypeId+":"+QuestionDuplicateService.fingerprint(form);
    String stem=form.examTypeId+":"+QuestionDuplicateService.stem(form);
    if(exact.contains(key)) { row.duplicateStatus=DuplicateStatus.EXACT_DUPLICATE; row.duplicateReason="Same normalized content as an earlier row in this batch."; }
    else if(row.duplicateStatus==DuplicateStatus.UNIQUE&&stems.contains(stem)) { row.duplicateStatus=DuplicateStatus.LIKELY_DUPLICATE;row.duplicateReason="Same text as an earlier row in this batch; review options."; }
    exact.add(key);stems.add(stem);
   }
   if(!row.errors.isBlank()) b.invalidRows++;
   else if(row.duplicateStatus!=DuplicateStatus.UNIQUE) b.duplicateRows++;
   else b.validRows++;
   b.totalRows++;rows.save(row);
  }
  if(b.invalidRows>0||b.duplicateRows>0) b.status=ImportStatus.REVIEW_REQUIRED;
  changes.record(actor,"IMPORT_STAGED","import:"+b.id,"","rows:"+b.totalRows);
  return b.id;
 }
 @Transactional
 public ImportBatch confirm(long id,String actor) {
  // Same lock as manual creation and taxonomy edits closes cross-batch duplicate races.
  settings.lock(); ImportBatch b=get(id);
  if(b.status==ImportStatus.IMPORTED) return b;
  if(b.status!=ImportStatus.VALIDATED&&b.status!=ImportStatus.REVIEW_REQUIRED) throw new IllegalArgumentException("This batch cannot be confirmed.");
  for(ImportRow row:rows.findByBatchIdOrderByRowNumber(id)) {
   if(!row.errors.isBlank()||row.duplicateStatus!=DuplicateStatus.UNIQUE) continue;
   QuestionForm f;
   try { f=convert(decode(row.payload)); }
   catch(IllegalArgumentException e) { row.errors=e.getMessage(); b.validRows--;b.invalidRows++;continue; }
   var errors=validation.errors(f,true,false);
   if(!errors.isEmpty()) { row.errors=clip(String.join(" ",errors),2000);b.validRows--;b.invalidRows++;continue; }
   var duplicate=duplicates.check(f);
   if(duplicate.status()!=DuplicateStatus.UNIQUE) {
    row.duplicateStatus=duplicate.status();row.duplicateReason=duplicate.reason();b.validRows--;b.duplicateRows++;continue;
   }
   row.questionId=questions.save(null,f,actor); b.importedRows++;
  }
  // Unexpected persistence failures roll back the entire confirmation, including counts.
  b.status=ImportStatus.IMPORTED;b.completedAt=Instant.now();
  changes.record(actor,"QUESTIONS_IMPORTED","import:"+id,"","created:"+b.importedRows+", duplicates:"+b.duplicateRows+", invalid:"+b.invalidRows);
  return b;
 }
 @Transactional
 public void cancel(long id,String actor) {
  settings.lock();ImportBatch b=get(id);
  if(b.status==ImportStatus.IMPORTED) throw new IllegalArgumentException("An imported batch cannot be cancelled. Archive its questions instead.");
  b.status=ImportStatus.CANCELLED;b.completedAt=Instant.now();
  changes.record(actor,"IMPORT_CANCELLED","import:"+id,"","CANCELLED");
 }
 @Transactional(readOnly=true)
 public ImportBatch get(long id) { return batches.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND)); }
 @Transactional(readOnly=true)
 public Page<ImportBatch> history(int page) { return batches.findAll(PageRequest.of(Math.max(0,page),20,Sort.by(Sort.Direction.DESC,"id"))); }
 @Transactional(readOnly=true)
 public Page<ImportRow> preview(long id,int page) { get(id);return rows.findByBatchIdOrderByRowNumber(id,PageRequest.of(Math.max(0,page),20)); }
 private QuestionForm convert(Map<String,String> m) {
  QuestionForm f=new QuestionForm();
  f.setTags(QuestionTags.normalize(m.getOrDefault("tags","")));
  String exam=m.getOrDefault("exam_type","").strip(),category=m.getOrDefault("category","").strip();
  var e=exams.findByCode(exam).orElseThrow(()->new IllegalArgumentException("Unknown exam type code."));
  var c=categories.findByExamTypeIdAndCode(e.getId(),category).orElseThrow(()->new IllegalArgumentException("Unknown category code for this exam type."));
  f.examTypeId=e.getId();f.categoryId=c.getId();f.questionText=m.getOrDefault("question","");
  for(int i=0;i<8;i++) f.getOptions().set(i,m.getOrDefault("option_"+(char)('a'+i),""));
  String answer=m.getOrDefault("correct_answer","").strip().toUpperCase(Locale.ROOT);
  if(!answer.matches("[A-H]")) throw new IllegalArgumentException("Correct answer must be one letter A-H.");
  f.setCorrectOption(answer.charAt(0)-'A');
  f.explanation=m.getOrDefault("explanation","");f.sourceType=m.getOrDefault("source_type","");
  f.sourceTitle=m.getOrDefault("source","");f.sourceReference=m.getOrDefault("source_reference","");f.sourceNotes=m.getOrDefault("source_notes","");
  try {
   f.difficulty=Difficulty.valueOf(m.getOrDefault("difficulty","").strip().toUpperCase(Locale.ROOT));
   f.useStatus=UseStatus.valueOf(m.getOrDefault("copyright_status","").strip().toUpperCase(Locale.ROOT));
   String year=m.getOrDefault("source_year","").strip();f.sourceYear=year.isBlank()?null:Integer.valueOf(year);
  } catch(IllegalArgumentException ex) { throw new IllegalArgumentException("Invalid difficulty, copyright status, or source year."); }
  f.freePool=bool(m,"free_available");f.premiumPool=bool(m,"premium_available");f.mockPool=bool(m,"mock_available");
  return f;
 }
 private boolean bool(Map<String,String> m,String field) {
  String value=m.getOrDefault(field,"").strip();
  if(!value.equalsIgnoreCase("true")&&!value.equalsIgnoreCase("false")) throw new IllegalArgumentException(field+" must be true or false.");
  return Boolean.parseBoolean(value);
 }
 private String encode(Map<String,String> value) {
  try { return json.writeValueAsString(value); } catch(java.io.IOException e) { throw new IllegalStateException("Cannot stage import.",e); }
 }
 private Map<String,String> decode(String value) {
  try { return json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}); }
  catch(java.io.IOException e) { throw new IllegalStateException("Cannot read staged import.",e); }
 }
 private static String clip(String value,int length) { return value.substring(0,Math.min(value.length(),length)); }
}
