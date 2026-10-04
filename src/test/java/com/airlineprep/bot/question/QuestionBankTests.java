package com.airlineprep.bot.question;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import com.airlineprep.bot.IsolatedDatabaseSupport;
import com.airlineprep.bot.admin.*;
import org.apache.commons.csv.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
@SpringBootTest @AutoConfigureMockMvc @Transactional
class QuestionBankTests extends IsolatedDatabaseSupport {
@Test void multipleCorrectAnswersCannotBeReviewed() {
  long id=create();questions.get(id).currentVersion.options.get(1).correct=true;
  assertThatThrownBy(()->transition(id,QuestionStatus.REVIEWED)).hasMessageContaining("Exactly one");
 }
 @Test void csvBomBlankRowsAndRowLimit() throws Exception {
  byte[] data=csv(List.of(values("Fictional BOM")));
  String bom="\uFEFF"+new String(data,StandardCharsets.UTF_8)+"\r\n";
  assertThat(parser.parse(new MockMultipartFile("file","bom.csv","text/csv",bom.getBytes(StandardCharsets.UTF_8))).rows()).hasSize(1);
  List<Map<String,String>> many=new ArrayList<>();for(int i=0;i<5000;i++) many.add(values("Fictional "+i));
  assertThat(parser.parse(new MockMultipartFile("file","large.csv","text/csv",csv(many))).rows()).hasSize(5000);
  many.add(values("Too many"));
  assertThatThrownBy(()->parser.parse(new MockMultipartFile("file","large.csv","text/csv",csv(many)))).hasMessageContaining("5000");
 }
 @Test void unknownAndDuplicateHeadersRejected() {
  String header=new String(parser.template(),StandardCharsets.UTF_8);
  for(String bad:List.of(header.replace("exam_type","unexpected"),header.replace("category","exam_type"))) {
   assertThatThrownBy(()->parser.parse(new MockMultipartFile("file","bad.csv","text/csv",(bad+"x").getBytes(StandardCharsets.UTF_8)))).isInstanceOf(IllegalArgumentException.class);
  }
 }
 @Test void emptyAndExtraSheetXlsxRejected() throws Exception {
  for(int n:List.of(0,2)) {
   byte[] data;
   try(XSSFWorkbook w=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
    for(int i=0;i<n;i++) w.createSheet("Sheet"+i);w.write(out);data=out.toByteArray();
   }
   assertThatThrownBy(()->parser.parse(new MockMultipartFile("file","bad.xlsx","application/octet-stream",data))).isInstanceOf(IllegalArgumentException.class);
  }
 }
 @Test void xlsxMissingHeadersAndExcessiveRowsRejected() throws Exception {
  for(boolean huge:List.of(false,true)) {
   byte[] data;
   try(XSSFWorkbook w=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
    var sheet=w.createSheet("Questions");sheet.createRow(huge?501:0).createCell(0).setCellValue("wrong");w.write(out);data=out.toByteArray();
   }
   assertThatThrownBy(()->parser.parse(new MockMultipartFile("file","bad.xlsx","application/octet-stream",data))).isInstanceOf(IllegalArgumentException.class);
  }
 }
 @Test void webLifecycleEditAndConfirmActionsWork() throws Exception {
  long id=create();
  for(QuestionStatus target:List.of(QuestionStatus.REVIEWED,QuestionStatus.PUBLISHED)) {
   mvc.perform(post("/admin/questions/"+id+"/transition").with(user("admin").roles("ADMIN")).with(csrf())
    .param("target",target.name()).param("expectedRevision",Long.toString(questions.get(id).revision)))
    .andExpect(redirectedUrl("/admin/questions/"+id));
   em.flush();
  }
  mvc.perform(post("/admin/questions/"+id+"/edit").with(user("admin").roles("ADMIN")).with(csrf())
   .param("expectedRevision",Long.toString(questions.get(id).revision)).param("questionText","Fictional web revision"))
   .andExpect(redirectedUrl("/admin/questions/"+id));
  assertThat(questions.get(id).currentVersion.versionNumber).isEqualTo(2);
  long b=stage(List.of(values("Fictional confirm HTTP")));
  mvc.perform(post("/admin/questions/import/"+b+"/confirm").with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().is3xxRedirection());
  assertThat(imports.get(b).importedRows).isEqualTo(1);
  mvc.perform(post("/admin/questions/import/"+b+"/review-publish").with(user("admin").roles("ADMIN")).with(csrf()).param("confirm","true"))
   .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("bulkResult"));
  mvc.perform(get("/admin/questions/import/"+b).with(user("admin").roles("ADMIN"))
   .flashAttr("bulkResult",new QuestionImportBulkService.Result(1,1,0,0,List.of())))
   .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Bulk review and publish result")));
 }
 @Autowired QuestionService questions;
 @Autowired QuestionRepository repository;
 @Autowired com.airlineprep.bot.category.CategoryRepository categories;
 @Autowired QuestionVersionRepository versions;
 @Autowired QuestionValidationService validation;
 @Autowired QuestionDuplicateService duplicates;
 @Autowired QuestionImportService imports;
 @Autowired QuestionImportBulkService bulkImports;
 @Autowired QuestionBulkDeleteService bulkDelete;
 @Autowired QuestionImportRemovalService importRemoval;
 @Autowired com.airlineprep.bot.audit.AdminChangeRepository changes;
 @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 @Autowired com.airlineprep.bot.user.BotUserRepository users;
 @Autowired com.airlineprep.bot.user.RegistrationService registration;
 @Autowired ImportRowRepository rows;
 @Autowired QuestionFileParser parser;
 @Autowired CatalogService catalog;
 @Autowired MockMvc mvc;
 @Autowired EntityManager em;
 long exam,category;
 @BeforeEach void setup() {
  exam=catalog.save(false,null,new CatalogForm("fictional","Fictional exam","",true,0,null),"test-admin");
  category=catalog.save(true,null,new CatalogForm("numbers","Numbers","",true,0,exam),"test-admin");
 }
 QuestionForm form(String text) {
  QuestionForm f=new QuestionForm();f.examTypeId=exam;f.categoryId=category;f.questionText=text;f.explanation="Fictional test explanation.";
  f.sourceType="Original practice";f.sourceTitle="Fictional test fixtures";f.useStatus=UseStatus.ORIGINAL;
  f.sourceYear=2026;f.sourceReference="Unit fixture";f.sourceNotes="No real exam content.";
  f.difficulty=Difficulty.EASY;f.freePool=true;f.mockPool=true;
  f.getOptions().set(0,"Two");f.getOptions().set(1,"Three");f.setCorrectOption(0);return f;
 }
 long create() { return questions.save(null,form("Fictional: one plus one?"),"test-admin"); }
 void transition(long id,QuestionStatus status) { questions.transition(id,status,questions.get(id).revision,"test-admin");em.flush(); }
 void publish(long id) { transition(id,QuestionStatus.REVIEWED);transition(id,QuestionStatus.PUBLISHED); }
 Map<String,String> values(String question) {
  Map<String,String> m=new LinkedHashMap<>();
  QuestionFileParser.HEADERS.forEach(h->m.put(h,""));
  m.put("exam_type","fictional");m.put("category","numbers");m.put("question",question);
  m.put("option_a","Two");m.put("option_b","Three");m.put("correct_answer","A");
  m.put("explanation","Fictional explanation");m.put("difficulty","EASY");m.put("source_type","Original practice");
  m.put("source","Fictional fixtures");m.put("copyright_status","ORIGINAL");
  m.put("free_available","true");m.put("premium_available","false");m.put("mock_available","true");return m;
 }
 byte[] csv(List<Map<String,String>> records) throws Exception {
  StringWriter out=new StringWriter();
  try(CSVPrinter p=new CSVPrinter(out,CSVFormat.RFC4180)) {
   p.printRecord(QuestionFileParser.HEADERS);
   for(var m:records) p.printRecord(QuestionFileParser.HEADERS.stream().map(h->m.getOrDefault(h,"")).toList());
  }
  return out.toString().getBytes(StandardCharsets.UTF_8);
 }
 long stage(List<Map<String,String>> records) throws Exception {
  return imports.stage(imports.parse(new MockMultipartFile("file","test.csv","text/csv",csv(records))),"test-admin");
 }
 @Test void draftAllowsIncompleteContent() {
  long id=questions.save(null,new QuestionForm(),"test-admin");
  assertThat(questions.get(id).status).isEqualTo(QuestionStatus.DRAFT);
  assertThat(questions.get(id).currentVersion.versionNumber).isEqualTo(1);
  assertThatThrownBy(()->transition(id,QuestionStatus.REVIEWED)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void draftEditPreservesVersionAndRejectsStaleForm() {
  long id=create();QuestionForm edit=questions.form(id),stale=questions.form(id);
  edit.questionText="Changed draft";questions.save(id,edit,"test-admin");
  assertThat(questions.get(id).currentVersion.versionNumber).isEqualTo(1);
  assertThatThrownBy(()->questions.save(id,stale,"test-admin")).hasMessageContaining("Reload");
 }
 @Test void repeatedUnchangedDraftSavesAlwaysAdvanceRevision() {
  long id=create();
  for(int i=0;i<10;i++) {
   var f=questions.form(id);long before=f.getExpectedRevision();
   questions.save(id,f,"test-admin");
   assertThat(questions.get(id).revision).isGreaterThan(before);
  }
  assertThat(questions.get(id).currentVersion.versionNumber).isEqualTo(1);
 }
 @Test void publishedRevisionAndArchivePreserveHistoricalContentAndContext() {
  long id=create();publish(id);long old=questions.get(id).currentVersion.id;
  QuestionForm f=questions.form(id);f.questionText="Corrected fictional wording";f.setCorrectOption(1);f.sourceNotes="Revision note";
  questions.save(id,f,"test-admin");em.flush();em.clear();
  Question q=questions.get(id);
  assertThat(q.status).isEqualTo(QuestionStatus.DRAFT);assertThat(q.currentVersion.versionNumber).isEqualTo(2);
  var historical=questions.history(id,0).getContent().get(1);
  assertThat(historical.id).isEqualTo(old);assertThat(historical.content.questionText).contains("one plus one");
  assertThat(historical.options.get(0).correct).isTrue();assertThat(historical.content.sourceNotes).isEqualTo("No real exam content.");
  assertThat(historical.examName).isEqualTo("Fictional exam");assertThat(historical.publishedAt).isNotNull();
  publish(id);transition(id,QuestionStatus.ARCHIVED);em.flush();em.clear();
  assertThat(questions.history(id,0).getTotalElements()).isEqualTo(2);
  assertThatThrownBy(()->transition(id,QuestionStatus.PUBLISHED)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->questions.save(id,questions.form(id),"test-admin")).hasMessageContaining("Archived");
 }
 @Test void reviewedEditReturnsToDraftAndClearsReview() {
  long id=create();transition(id,QuestionStatus.REVIEWED);
  questions.save(id,questions.form(id),"test-admin");
  assertThat(questions.get(id).status).isEqualTo(QuestionStatus.DRAFT);
  assertThat(questions.get(id).currentVersion.reviewedAt).isNull();
 }
 @Test void cannotPublishDraftOrBypassRights() {
  long id=create();assertThatThrownBy(()->transition(id,QuestionStatus.PUBLISHED)).isInstanceOf(IllegalArgumentException.class);
  var f=questions.form(id);f.useStatus=UseStatus.UNKNOWN_REVIEW_REQUIRED;questions.save(id,f,"test-admin");
  assertThatThrownBy(()->transition(id,QuestionStatus.REVIEWED)).hasMessageContaining("rights");
 }
 @ParameterizedTest @ValueSource(strings={"blank","zero","one","duplicate","badCorrect","noPool","noSource","noDifficulty","noExplanation"})
 void publishValidation(String invalid) {
  var f=form("Fictional prompt");
  switch(invalid) {
   case "blank" -> f.questionText=" ";
   case "zero" -> f.setCorrectOption(null);
   case "one" -> f.getOptions().set(1,"");
   case "duplicate" -> f.getOptions().set(1," TWO ");
   case "badCorrect" -> f.setCorrectOption(7);
   case "noPool" -> { f.freePool=false;f.mockPool=false; }
   case "noSource" -> f.sourceTitle="";
   case "noDifficulty" -> f.difficulty=Difficulty.UNSPECIFIED;
   case "noExplanation" -> f.explanation="";
  }
  assertThat(validation.errors(f,true,true)).isNotEmpty();
 }
 @Test void taxonomyMismatchAndInactiveReferencesRejected() {
  long other=catalog.save(false,null,new CatalogForm("other","Other","",true,0,null),"test-admin");
  var f=form("Fictional");f.examTypeId=other;
  assertThatThrownBy(()->questions.save(null,f,"test-admin")).hasMessageContaining("belong");
  f.examTypeId=exam;catalog.toggle(true,category,"test-admin");
  assertThat(validation.errors(f,true,true)).anyMatch(s->s.contains("active"));
 }
 @Test void variableOptionsAndProvenancePersist() {
  var f=form("Fictional");f.getOptions().set(7,"Eight");f.setCorrectOption(7);
  long id=questions.save(null,f,"test-admin");publish(id);em.clear();
  var q=questions.get(id);
  assertThat(q.currentVersion.options).hasSize(3);assertThat(q.currentVersion.options.get(2).correct).isTrue();
  assertThat(q.currentVersion.content.sourceYear).isEqualTo(2026);assertThat(q.currentVersion.content.mockPool).isTrue();
 }
 @Test void searchFiltersAndPagination() {
  for(int i=0;i<22;i++) questions.save(null,form("Fictional number "+i),"test-admin");
  assertThat(questions.search("number",exam,category,QuestionStatus.DRAFT,"freePool",UseStatus.ORIGINAL,0,"createdAt").getTotalElements()).isEqualTo(22);
  assertThat(questions.search("",null,null,null,null,null,1,"updatedAt").getContent()).hasSize(2);
  assertThat(questions.search("",null,null,null,"premiumPool",null,0,"updatedAt")).isEmpty();
  long id=repository.findAll().getFirst().id;transition(id,QuestionStatus.ARCHIVED);
  assertThat(questions.search("",null,null,null,null,null,0,"updatedAt").getTotalElements()).isEqualTo(21);
  assertThat(questions.search("",null,null,QuestionStatus.ARCHIVED,null,null,0,"updatedAt").getTotalElements()).isEqualTo(1);
  assertThat(questions.search("%",null,null,null,null,null,0,"updatedAt")).isEmpty();
 }
 @Test void duplicateNormalizationIsConservativeAndScoped() {
  create();
  assertThat(duplicates.check(form("  FICTIONAL: one  plus one? ")).status()).isEqualTo(DuplicateStatus.EXACT_DUPLICATE);
  var different=form("Fictional: one plus one?");different.setCorrectOption(1);
  assertThat(duplicates.check(different).status()).isEqualTo(DuplicateStatus.LIKELY_DUPLICATE);
  assertThat(duplicates.check(form("Fictional: three minus two?")).status()).isEqualTo(DuplicateStatus.UNIQUE);
  different.examTypeId=999L;assertThat(duplicates.check(different).status()).isEqualTo(DuplicateStatus.UNIQUE);
 }
 @Test void partialImportPreviewConfirmIdempotencyAndHistory() throws Exception {
  var invalid=values("");var valid=values("Fictional, quoted \"question\"\nsecond line ሰላም");
  long batch=stage(List.of(valid,invalid,valid));assertThat(repository.count()).isZero();
  assertThat(imports.get(batch).totalRows).isEqualTo(3);assertThat(imports.get(batch).validRows).isEqualTo(1);
  assertThat(imports.get(batch).invalidRows).isEqualTo(1);assertThat(imports.get(batch).duplicateRows).isEqualTo(1);
  imports.confirm(batch,"test-admin");imports.confirm(batch,"test-admin");em.flush();em.clear();
  assertThat(repository.count()).isEqualTo(1);assertThat(imports.get(batch).importedRows).isEqualTo(1);
  long id=rows.findByBatchIdOrderByRowNumber(batch).getFirst().questionId;
  assertThat(questions.get(id).status).isEqualTo(QuestionStatus.DRAFT);
  assertThat(questions.get(id).currentVersion.content.questionText).contains("ሰላም");
 }
 @Test void cancellationPreventsCreation() throws Exception {
  long id=stage(List.of(values("Fictional")));imports.cancel(id,"test-admin");
  assertThatThrownBy(()->imports.confirm(id,"test-admin")).hasMessageContaining("cannot");
  assertThat(repository.count()).isZero();
 }
 @Test void oneClickBatchReviewPublishUsesLifecycleAndIsRepeatSafeFor101Questions() throws Exception {
  List<Map<String,String>> content=new ArrayList<>();for(int i=0;i<101;i++)content.add(values("Fictional bulk question "+i));
  long batch=stage(content);imports.confirm(batch,"test-admin");
  long reviewed=rows.findByBatchIdOrderByRowNumber(batch).getFirst().questionId;transition(reviewed,QuestionStatus.REVIEWED);
  var first=bulkImports.reviewAndPublish(batch,"test-admin");em.flush();em.clear();
  assertThat(first.published()).as(first.toString()).isEqualTo(101);assertThat(first.skipped()).isZero();
  assertThat(repository.countByStatus(QuestionStatus.PUBLISHED)).isEqualTo(101);
  var again=bulkImports.reviewAndPublish(batch,"test-admin");
  assertThat(again.published()).isZero();assertThat(again.alreadyPublished()).isEqualTo(101);
  assertThat(versions.count()).isEqualTo(101);
  assertThat(changes.findAll()).extracting(c->c.getAction()).contains("IMPORT_BULK_REVIEW_PUBLISH");
 }
 @Test void bulkContinuesAfterRightsRestrictedQuestionAndReportsIt() throws Exception {
  var allowed=values("Fictional publishable bulk question");var restricted=values("Fictional rights restricted bulk question");
  restricted.put("copyright_status","UNKNOWN_REVIEW_REQUIRED");var incomplete=values("Fictional incomplete bulk question");
  long batch=stage(List.of(allowed,restricted,incomplete));imports.confirm(batch,"test-admin");
  long invalidId=rows.findByBatchIdOrderByRowNumber(batch).get(2).questionId;
  var invalidDraft=questions.form(invalidId);invalidDraft.explanation="";questions.save(invalidId,invalidDraft,"test-admin");
  var result=bulkImports.reviewAndPublish(batch,"test-admin");
  assertThat(result.published()).isEqualTo(1);assertThat(result.skipped()).isEqualTo(2);
  assertThat(result.failures()).extracting(QuestionImportBulkService.Failure::reason)
   .anyMatch(reason->reason.contains("UNKNOWN_REVIEW_REQUIRED"))
   .anyMatch(reason->reason.contains("Explanation"));
 }
 @Test void invalidStagingCleanupAndCancelledHistoryCleanupPreserveQuestions() throws Exception {
  long invalid=stage(List.of(values("Fictional retained valid row"),values("")));
  assertThat(imports.clearInvalidRows(invalid,"test-admin")).isEqualTo(1);
  assertThat(imports.preview(invalid,0).getContent()).hasSize(1);
  long cancelled=stage(List.of(values("Fictional cancelled batch")));imports.cancel(cancelled,"test-admin");
  assertThat(imports.clearCancelledFailed("test-admin")).isEqualTo(1);
  assertThat(imports.history(0).getContent()).noneMatch(b->b.getId()==cancelled);
  assertThat(repository.count()).isZero();
 }
 @Test void archivedPublishedQuestionRestoresAsNewDraftVersionAndCanBeRevalidated() {
  long id=create();publish(id);transition(id,QuestionStatus.ARCHIVED);
  long before=versions.count();questions.restoreToDraft(id,"test-admin");
  assertThat(questions.get(id).status).isEqualTo(QuestionStatus.DRAFT);
  assertThat(questions.get(id).currentVersion.versionNumber).isEqualTo(2);assertThat(versions.count()).isEqualTo(before+1);
  transition(id,QuestionStatus.REVIEWED);transition(id,QuestionStatus.PUBLISHED);
  assertThat(questions.get(id).status).isEqualTo(QuestionStatus.PUBLISHED);
 }
 @Test void importedHistoryDeletionPreservesQuestionsAndVersions() throws Exception {
  long batch=stage(List.of(values("Fictional preserved import")));imports.confirm(batch,"test-admin");
  long id=rows.findByBatchIdOrderByRowNumber(batch).getFirst().questionId;long versionsBefore=versions.count();
  imports.deleteHistory(batch,"test-admin");
  assertThat(repository.findById(id)).isPresent();assertThat(versions.count()).isEqualTo(versionsBefore);
  assertThat(imports.history(0).getContent()).noneMatch(b->b.getId()==batch);
 }
 @Test void importBatchRemovalDeletesOnlyUnusedAndArchivesPublishedOrUsedQuestions() throws Exception {
  long batch=stage(List.of(values("Fictional removal safe draft"),values("Fictional removal reviewed"),values("Fictional removal published"),values("Fictional removal used"),values("Fictional removal archived")));
  imports.confirm(batch,"test-admin");em.flush();List<ImportRow> imported=rows.findByBatchIdOrderByRowNumber(batch);
  long draft=imported.get(0).questionId,reviewed=imported.get(1).questionId,published=imported.get(2).questionId,used=imported.get(3).questionId,archived=imported.get(4).questionId;
  transition(reviewed,QuestionStatus.REVIEWED);publish(published);publish(used);transition(used,QuestionStatus.ARCHIVED);questions.restoreToDraft(used,"test-admin");publish(used);transition(used,QuestionStatus.ARCHIVED);
  transition(archived,QuestionStatus.ARCHIVED);
  // Add student practice history and a frozen mock item for the two historically used questions.
  long user=unusedStudentId();Question usedQuestion=questions.get(used);
  jdbc.update("INSERT INTO practice_deliveries(user_id,exam_type_id,question_id,version_id,created_at) VALUES (?,?,?,?,?)",user,exam,used,usedQuestion.currentVersion.id,java.sql.Timestamp.from(java.time.Instant.now()));
  jdbc.update("INSERT INTO mock_attempts(user_id,exam_type_id,creation_key,status,question_count,cursor_position,created_at,submitted_at,unanswered_count) VALUES (?,?,?,'SUBMITTED',1,0,?,?,1)",user,exam,"import-remove-"+published,java.sql.Timestamp.from(java.time.Instant.now()),java.sql.Timestamp.from(java.time.Instant.now()));
  long attempt=jdbc.queryForObject("SELECT MAX(id) FROM mock_attempts",Long.class);
  jdbc.update("INSERT INTO mock_items(attempt_id,sequence_number,question_id,version_id) VALUES (?,0,?,?)",attempt,published,questions.get(published).currentVersion.id);
  long oldVersions=versions.count();
  var plan=importRemoval.preview(batch);
  assertThat(plan.linked()).as("linked").isEqualTo(5);
  assertThat(plan.safeDelete()).as("safe delete").isEqualTo(2);
  assertThat(plan.archive()).as("archive").isEqualTo(1);
  assertThat(plan.alreadyInactive()).as("already inactive").isEqualTo(2);
  var result=importRemoval.remove(batch,"test-admin");
  assertThat(result.failed()).as(result.toString()).isZero();
  assertThat(result.linked()).as("result linked").isEqualTo(5);
  assertThat(result.deleted()).as("result deleted").isEqualTo(2);
  assertThat(result.archived()).as("result archived").isEqualTo(1);
  assertThat(result.alreadyInactive()).as("result inactive").isEqualTo(2);
  assertThat(result.failed()).as("result failed").isZero();
  assertThat(repository.findById(draft)).isEmpty();assertThat(repository.findById(reviewed)).isEmpty();
  assertThat(questions.get(published).status).isEqualTo(QuestionStatus.ARCHIVED);assertThat(questions.get(used).status).isEqualTo(QuestionStatus.ARCHIVED);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_deliveries WHERE question_id=?",Integer.class,used)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_items WHERE attempt_id=? AND question_id=?",Integer.class,attempt,published)).isEqualTo(1);
  assertThat(versions.count()).isEqualTo(oldVersions-2);assertThat(imports.get(batch)).isNotNull();
  assertThat(changes.findAll()).extracting(c->c.getAction()).contains("IMPORT_QUESTIONS_REMOVED");
  var repeated=importRemoval.remove(batch,"test-admin");assertThat(repeated.linked()).isEqualTo(5);assertThat(repeated.deleted()+repeated.archived()).isZero();assertThat(repeated.alreadyInactive()).isEqualTo(5);
 }
 @Test void importBatchMembershipIsExactAnd101RowsAreProcessed() throws Exception {
  List<Map<String,String>> data=new ArrayList<>();for(int i=0;i<101;i++)data.add(values("Fictional selected import batch row "+i));
  long selected=stage(data);imports.confirm(selected,"test-admin");em.flush();
  long other=stage(List.of(values("Fictional other import same exam and category")));imports.confirm(other,"test-admin");em.flush();
  long unrelated=rows.findByBatchIdOrderByRowNumber(other).getFirst().questionId;
  var result=importRemoval.remove(selected,"test-admin");
  assertThat(result.linked()).isEqualTo(101);assertThat(result.deleted()).isEqualTo(101);assertThat(result.failed()).isZero();
  assertThat(repository.count()).isEqualTo(1);assertThat(questions.get(unrelated).status).isEqualTo(QuestionStatus.DRAFT);
  assertThat(imports.get(selected)).isNotNull();
 }
 @Test void importBatchRemovalAdminCsrfConfirmationAndResultViews() throws Exception {
  long batch=stage(List.of(values("Fictional web batch removal")));imports.confirm(batch,"test-admin");em.flush();
  mvc.perform(get("/admin/questions/import/"+batch+"/remove-questions").with(user("admin").roles("ADMIN")))
   .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Safe permanent deletion: 1")));
  mvc.perform(post("/admin/questions/import/"+batch+"/remove-questions").with(user("admin").roles("ADMIN")).param("confirm","true"))
   .andExpect(status().isForbidden());
  mvc.perform(post("/admin/questions/import/"+batch+"/remove-questions").with(user("admin").roles("ADMIN")).with(csrf()))
   .andExpect(status().is3xxRedirection());
  mvc.perform(post("/admin/questions/import/"+batch+"/remove-questions").with(user("admin").roles("ADMIN")).with(csrf()).param("confirm","true"))
   .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Import question removal complete")));
 }
 @Test void unusedCategoryDeletesButQuestionHistoryReferencesBlockDelete() {
  long unused=catalog.save(true,null,new CatalogForm("unused","Unused","",true,0,exam),"test-admin");
  catalog.deleteCategory(unused,"test-admin");assertThat(categories.findById(unused)).isEmpty();
  create();assertThatThrownBy(()->catalog.deleteCategory(category,"test-admin")).hasMessageContaining("reference it");
 }
 @Test void safeNeverPublishedDraftCanBeDeletedButPublishedQuestionCannot() {
  long draft=create();long revision=questions.get(draft).revision;questions.deleteSafeDraft(draft,revision,"test-admin");
  assertThat(repository.findById(draft)).isEmpty();
  assertThat(changes.findAll()).extracting(c->c.getAction()).contains("QUESTION_DELETED");
  long published=create();publish(published);
  assertThatThrownBy(()->questions.deleteSafeDraft(published,questions.get(published).revision,"test-admin"))
   .hasMessageContaining("Archive it instead");
 }
 @Test void bulkDeleteRemovesSeveralSafeDraftsAndIsRepeatSafe() {
  long a=create(),b=questions.save(null,form("Fictional second safe bulk draft"),"test-admin");
  var plan=bulkDelete.preview();assertThat(plan.drafts()).isEqualTo(2);assertThat(plan.eligible()).isEqualTo(2);
  var result=bulkDelete.deleteAll("test-admin");
  assertThat(result.draftsFound()).isEqualTo(2);assertThat(result.deleted()).isEqualTo(2);
  assertThat(result.skipped()).isZero();assertThat(result.failed()).isZero();
  assertThat(repository.findById(a)).isEmpty();assertThat(repository.findById(b)).isEmpty();
  assertThat(changes.findAll()).extracting(c->c.getAction()).contains("BULK_DRAFTS_DELETED");
  var repeated=bulkDelete.deleteAll("test-admin");assertThat(repeated.draftsFound()).isZero();assertThat(repeated.deleted()).isZero();
 }
 @Test void bulkDelete101DraftsUsesBoundedKeysetBatches() {
  for(int i=0;i<101;i++) questions.save(null,form("Fictional bulk cleanup draft "+i),"test-admin");
  var result=bulkDelete.deleteAll("test-admin");
  assertThat(result.draftsFound()).isEqualTo(101);assertThat(result.deleted()).isEqualTo(101);
  assertThat(repository.count()).isZero();
 }
 @Test void bulkDeleteSkipsPreviouslyPublishedAndOtherLifecycleStatuses() {
  long oldPublished=create();publish(oldPublished);var revision=questions.form(oldPublished);revision.questionText="Fictional edited after publication";questions.save(oldPublished,revision,"test-admin");
  long published=create();publish(published);
  long reviewed=create();transition(reviewed,QuestionStatus.REVIEWED);
  long archived=create();transition(archived,QuestionStatus.ARCHIVED);
  var plan=bulkDelete.preview();assertThat(plan.drafts()).isEqualTo(1);assertThat(plan.eligible()).isZero();
  var result=bulkDelete.deleteAll("test-admin");assertThat(result.deleted()).isZero();assertThat(result.skipped()).isEqualTo(1);
  assertThat(result.issues()).anyMatch(i->i.questionId()==oldPublished&&i.reason().contains("Previously published"));
  assertThat(repository.findById(reviewed)).isPresent();assertThat(repository.findById(archived)).isPresent();
  assertThat(questions.get(published).status).isEqualTo(QuestionStatus.PUBLISHED);
  assertThat(questions.get(oldPublished).status).isEqualTo(QuestionStatus.DRAFT);
 }
 @Test void bulkDeleteSkipsPracticeMockAndImportLinkedDrafts() throws Exception {
  long practice=create();var p=questions.get(practice);long user=unusedStudentId();
  jdbc.update("INSERT INTO practice_deliveries(user_id,exam_type_id,question_id,version_id,created_at) VALUES (?,?,?,?,?)",user,exam,practice,p.currentVersion.id,java.sql.Timestamp.from(java.time.Instant.now()));
  long mock=create();var m=questions.get(mock);jdbc.update("INSERT INTO mock_attempts(user_id,exam_type_id,creation_key,status,question_count,cursor_position,created_at,submitted_at,unanswered_count) VALUES (?,?,?,'SUBMITTED',1,0,?,?,1)",user,exam,"bulk-delete-"+mock,java.sql.Timestamp.from(java.time.Instant.now()),java.sql.Timestamp.from(java.time.Instant.now()));
  long attempt=jdbc.queryForObject("SELECT MAX(id) FROM mock_attempts",Long.class);
  jdbc.update("INSERT INTO mock_items(attempt_id,sequence_number,question_id,version_id) VALUES (?,0,?,?)",attempt,mock,m.currentVersion.id);
  long batch=stage(List.of(values("Fictional imported protected draft")));imports.confirm(batch,"test-admin");
  long imported=rows.findByBatchIdOrderByRowNumber(batch).getFirst().questionId;
  var result=bulkDelete.deleteAll("test-admin");assertThat(result.deleted()).isZero();assertThat(result.skipped()).isEqualTo(3);
  assertThat(result.issues()).anyMatch(i->i.questionId()==practice&&i.reason().contains("practice"));
  assertThat(result.issues()).anyMatch(i->i.questionId()==mock&&i.reason().contains("mock"));
  assertThat(result.issues()).anyMatch(i->i.questionId()==imported&&i.reason().contains("import/history"));
  assertThat(repository.findById(practice)).isPresent();assertThat(repository.findById(mock)).isPresent();assertThat(repository.findById(imported)).isPresent();
 }
 private long unusedStudentId() {
  long sender=Math.abs(System.nanoTime())+1;
  registration.start(sender);registration.language(sender,"en");registration.exam(sender,exam);
  registration.contact(sender,sender,"09"+String.format("%08d",sender%100000000));
  return users.findByTelegramUserId(sender).orElseThrow().getId();
 }
 @Test void confirmationRechecksOtherBatchesAndTaxonomy() throws Exception {
  var m=values("Fictional concurrent");
  long a=stage(List.of(m)),b=stage(List.of(m));imports.confirm(a,"test-admin");imports.confirm(b,"test-admin");
  assertThat(imports.get(b).duplicateRows).isEqualTo(1);assertThat(imports.get(b).importedRows).isZero();
  long c=stage(List.of(values("Another fictional")));catalog.toggle(false,exam,"test-admin");imports.confirm(c,"test-admin");
  assertThat(imports.get(c).invalidRows).isEqualTo(1);
 }
 @Test void uncertainRightsImportAsDraftOnly() throws Exception {
  var m=values("Fictional rights");m.put("copyright_status","UNKNOWN_REVIEW_REQUIRED");
  long b=stage(List.of(m));imports.confirm(b,"test-admin");
  long q=rows.findByBatchIdOrderByRowNumber(b).getFirst().questionId;
  assertThatThrownBy(()->transition(q,QuestionStatus.REVIEWED)).hasMessageContaining("rights");
 }
 @ParameterizedTest @ValueSource(strings={"correct_answer","exam_type","category","difficulty","copyright_status","free_available","source_year","option_a"})
 void invalidImportValuesReported(String field) throws Exception {
  var m=values("Fictional invalid");m.put(field,field.equals("option_a")?"":"INVALID");
  long id=stage(List.of(m));assertThat(imports.get(id).invalidRows).isEqualTo(1);
  imports.confirm(id,"test-admin");assertThat(repository.count()).isZero();
 }
 @ParameterizedTest @ValueSource(strings={"evil.exe","../test.csv","path\\test.xlsx","old.xls"})
 void rejectUnsafeFileNames(String filename) {
  assertThatThrownBy(()->parser.parse(new MockMultipartFile("file",filename,"text/csv",new byte[]{1}))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void rejectOversizeMalformedHeadersAndEncoding() throws Exception {
  assertThatThrownBy(()->parser.parse(new MockMultipartFile("file","test.csv","text/csv",new byte[QuestionFileParser.MAX_BYTES+1]))).hasMessageContaining("2 MiB");
  assertThatThrownBy(()->parser.parse(new MockMultipartFile("file","test.csv","text/csv","bad,header\n1,2".getBytes(StandardCharsets.UTF_8)))).hasMessageContaining("Headers");
  assertThatThrownBy(()->parser.parse(new MockMultipartFile("file","test.csv","text/csv",new byte[]{(byte)0xff}))).isInstanceOf(IllegalArgumentException.class);
  String bad=new String(parser.template(),StandardCharsets.UTF_8)+"\"unterminated";
  assertThatThrownBy(()->parser.parse(new MockMultipartFile("file","test.csv","text/csv",bad.getBytes(StandardCharsets.UTF_8)))).isInstanceOf(IllegalArgumentException.class);
 }
 byte[] workbook(boolean formula) throws Exception {
  try(XSSFWorkbook w=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
   var sheet=w.createSheet("Questions");var header=sheet.createRow(0);var row=sheet.createRow(1);
   var m=values("Fictional XLSX");
   for(int i=0;i<QuestionFileParser.HEADERS.size();i++) { String key=QuestionFileParser.HEADERS.get(i);header.createCell(i).setCellValue(key);row.createCell(i).setCellValue(m.get(key)); }
   if(formula) row.getCell(2).setCellFormula("1+1");
   w.write(out);return out.toByteArray();
  }
 }
 @Test void xlsxParsesAndImports() throws Exception {
  long b=imports.stage(imports.parse(new MockMultipartFile("file","test.xlsx","application/octet-stream",workbook(false))),"test-admin");
  assertThat(imports.get(b).validRows).isEqualTo(1);imports.confirm(b,"test-admin");assertThat(repository.count()).isEqualTo(1);
 }
 @Test void xlsxFormulaIsInvalidAndNeverEvaluated() throws Exception {
  long b=imports.stage(imports.parse(new MockMultipartFile("file","test.xlsx","application/octet-stream",workbook(true))),"test-admin");
  assertThat(imports.get(b).invalidRows).isEqualTo(1);assertThat(imports.preview(b,0).getContent().getFirst().errors).contains("Formula");
 }
 @Test void corruptXlsxRejected() {
  assertThatThrownBy(()->parser.parse(new MockMultipartFile("file","test.xlsx","application/octet-stream",new byte[]{'P','K',1,2}))).isInstanceOf(IllegalArgumentException.class);
 }
 @ParameterizedTest @ValueSource(strings={"/admin/questions","/admin/questions/new","/admin/questions/bulk-delete-drafts","/admin/questions/new","/admin/questions/1","/admin/questions/1/edit","/admin/questions/import","/admin/questions/import/1","/admin/questions/import/1/remove-questions","/admin/questions/import/template.csv"})
 void allReadsRequireAdmin(String path) throws Exception {
  mvc.perform(get(path)).andExpect(status().is3xxRedirection());
  mvc.perform(get(path).with(user("ordinary").roles("USER"))).andExpect(status().isForbidden());
 }
 @ParameterizedTest @ValueSource(strings={"/admin/questions/new","/admin/questions/1/edit","/admin/questions/1/transition","/admin/questions/1/restore","/admin/questions/1/delete","/admin/questions/bulk-delete-drafts","/admin/questions/import/1/confirm","/admin/questions/import/1/cancel","/admin/questions/import/1/review-publish","/admin/questions/import/1/delete","/admin/questions/import/1/clear-invalid","/admin/questions/import/1/remove-questions","/admin/questions/import/clear-cancelled-failed"})
 void mutationsRequireCsrfAndAdmin(String path) throws Exception {
  mvc.perform(post(path).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
  mvc.perform(post(path).with(csrf())).andExpect(status().is3xxRedirection());
  mvc.perform(post(path).with(csrf()).with(user("ordinary").roles("USER"))).andExpect(status().isForbidden());
 }
 @Test void viewsRenderAndEscapeContent() throws Exception {
  var f=form("<script>alert('fictional')</script>");long id=questions.save(null,f,"test-admin");
  for(String path:List.of("/admin/questions","/admin/questions/new","/admin/questions/bulk-delete-drafts","/admin/questions/"+id,"/admin/questions/"+id+"/edit","/admin/questions/import","/admin")) {
   mvc.perform(get(path).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
  }
  mvc.perform(get("/admin/questions/"+id).with(user("admin").roles("ADMIN"))).andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")));
  long batch=stage(List.of(values("Fictional preview")));
  mvc.perform(get("/admin/questions/import/"+batch).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
 }
 @Test void bulkDeleteConfirmationAndAdminPostWork() throws Exception {
  long id=create();long protectedId=create();publish(protectedId);var f=questions.form(protectedId);f.questionText="Fictional protected published revision";questions.save(protectedId,f,"test-admin");
  mvc.perform(get("/admin/questions/bulk-delete-drafts").with(user("admin").roles("ADMIN")))
   .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Total DRAFT questions: 2")))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("Eligible for permanent deletion: 1")));
  mvc.perform(post("/admin/questions/bulk-delete-drafts").with(user("admin").roles("ADMIN")).with(csrf()))
   .andExpect(status().is3xxRedirection());assertThat(repository.findById(id)).isPresent();
  mvc.perform(post("/admin/questions/bulk-delete-drafts").with(user("admin").roles("ADMIN")).with(csrf()).param("confirm","true"))
   .andExpect(status().isOk()).andExpect(view().name("admin/question-bulk-delete-result"))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("Drafts found: 2 · Deleted: 1 · Skipped: 1 · Failed: 0")))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("Previously published")));
  assertThat(repository.findById(id)).isEmpty();assertThat(repository.findById(protectedId)).isPresent();
 }
 @Test void controllerDraftValidationAndUploadFlow() throws Exception {
  mvc.perform(post("/admin/questions/new").with(user("admin").roles("ADMIN")).with(csrf())
   .param("questionText","Fictional web draft")).andExpect(status().is3xxRedirection());
  mvc.perform(post("/admin/questions/new").with(user("admin").roles("ADMIN")).with(csrf())
   .param("examTypeId","999999")).andExpect(model().attributeHasErrors("form"));
  mvc.perform(multipart("/admin/questions/import").file(new MockMultipartFile("file","web.csv","text/csv",csv(List.of(values("Fictional web import")))))
   .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().is3xxRedirection());
 }
}
