package com.airlineprep.bot.question;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
@Component
public class QuestionFileParser {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(QuestionFileParser.class);
 private static class InvalidFile extends IllegalArgumentException {
  InvalidFile(String message) { super(message); }
 }
 public static final int MAX_BYTES=2*1024*1024, MAX_ROWS=5000, MAX_CELL=12000;
 public static final List<String> HEADERS=List.of("exam_type","category","question","option_a","option_b","option_c","option_d","option_e","option_f","option_g","option_h","correct_answer","explanation","difficulty","source_type","source","source_reference","source_year","source_notes","copyright_status","free_available","premium_available","mock_available");
 public record Row(int number,Map<String,String> values,String error) {}
 public record Parsed(String filename,String type,List<Row> rows) {}
 public Parsed parse(MultipartFile file) {
  String name=file.getOriginalFilename();
  if(name==null || name.length()>200 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) throw new InvalidFile("Use a plain filename of at most 200 characters.");
  String type=name.toLowerCase(Locale.ROOT).endsWith(".csv")?"CSV":name.toLowerCase(Locale.ROOT).endsWith(".xlsx")?"XLSX":"";
  if(type.isEmpty()) throw new InvalidFile("Only UTF-8 CSV and XLSX files are supported.");
  String mime=file.getContentType();
  if(mime!=null&&!mime.isBlank()) {
   mime=mime.split(";",2)[0].strip().toLowerCase(Locale.ROOT);
   var allowed=type.equals("CSV")?List.of("text/csv","text/plain","application/csv","application/vnd.ms-excel","application/octet-stream"):
    List.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/octet-stream");
   if(!allowed.contains(mime)) throw new InvalidFile("The file type does not match a CSV or XLSX upload.");
  }
  if(file.isEmpty()||file.getSize()>MAX_BYTES) throw new InvalidFile("Upload a nonempty file of at most 2 MiB.");
  try(InputStream in=file.getInputStream()) {
   byte[] data=in.readNBytes(MAX_BYTES+1);
   if(data.length>MAX_BYTES) throw new InvalidFile("File exceeds 2 MiB.");
   List<Row> rows=type.equals("CSV")?csv(data):xlsx(data);
   if(rows.isEmpty()) throw new InvalidFile("No data rows found.");
   return new Parsed(name,type,rows);
  } catch(InvalidFile e) { throw e; }
  catch(Exception e) {
   log.debug("Rejected question import due to parser exception type {}",e.getClass().getSimpleName());
   throw new InvalidFile("Cannot parse file. Check its format, UTF-8 encoding, and headers.");
  }
 }
 private List<Row> csv(byte[] data) throws IOException {
  String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data)).toString();
  if(value.startsWith("\uFEFF")) value=value.substring(1);
  if(value.indexOf('\0')>=0) throw new InvalidFile("CSV contains invalid null bytes.");
  try(CSVParser parser=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).get().parse(new StringReader(value))) {
   List<String> headers=parser.getHeaderNames(); headers(headers);
   List<Row> rows=new ArrayList<>();
   for(CSVRecord record:parser) {
    if(rows.size()>=MAX_ROWS) throw new InvalidFile("At most 5000 data rows are allowed.");
    Map<String,String> values=new LinkedHashMap<>();
    String error=record.size()==headers.size()?"":"Column count does not match headers.";
    for(int i=0;i<Math.min(record.size(),headers.size());i++) values.put(headers.get(i),record.get(i));
    if(values.values().stream().allMatch(String::isBlank)) continue;
    rows.add(new Row((int)record.getRecordNumber()+1,values,error+cellErrors(values)));
   }
   return rows;
  }
 }
 private List<Row> xlsx(byte[] data) throws IOException {
  if(data.length<4||data[0]!='P'||data[1]!='K') throw new InvalidFile("XLSX must be a valid workbook.");
  // Bound total expanded ZIP size before POI constructs its in-memory document.
  int entries=0; long expanded=0;
  try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(data))) {
   ZipEntry entry; byte[] buffer=new byte[8192];
   while((entry=zip.getNextEntry())!=null) {
    if(++entries>1000) throw new InvalidFile("Workbook has too many parts.");
    String path=entry.getName().toLowerCase(Locale.ROOT);
    if(path.startsWith("/")||path.contains("\\")||Arrays.asList(path.split("/")).contains("..")) throw new InvalidFile("Workbook contains an unsafe part path.");
    if(path.contains("vbaproject")||path.startsWith("xl/externallinks/")||path.startsWith("xl/embeddings/")) throw new InvalidFile("Macros, embedded objects, and external links are not supported.");
    int n; while((n=zip.read(buffer))!=-1) { expanded+=n; if(expanded>20L*1024*1024) throw new InvalidFile("Expanded workbook exceeds 20 MiB."); }
   }
  }
  try(XSSFWorkbook workbook=new XSSFWorkbook(new ByteArrayInputStream(data))) {
   if(workbook.getNumberOfSheets()!=1) throw new InvalidFile("Use exactly one worksheet.");
   Sheet sheet=workbook.getSheetAt(0);
   if(sheet.getLastRowNum()>MAX_ROWS) throw new InvalidFile("At most 5000 data rows are allowed.");
   org.apache.poi.ss.usermodel.Row header=sheet.getRow(0);
   if(header==null||header.getLastCellNum()<HEADERS.size()||header.getLastCellNum()>HEADERS.size()+1) throw new InvalidFile("Use the template headers.");
   List<String> headers=new ArrayList<>();
   for(Cell c:header) { if(c.getCellType()!=CellType.STRING) throw new InvalidFile("Headers must be text."); headers.add(c.getStringCellValue()); }
   headers(headers);
   List<Row> result=new ArrayList<>();
   for(int r=1;r<=sheet.getLastRowNum();r++) {
    var row=sheet.getRow(r); if(row==null) continue;
    Map<String,String> values=new LinkedHashMap<>(); String error="";
    if(row.getLastCellNum()>headers.size()) error="Extra cells beyond headers.";
    for(int col=0;col<headers.size();col++) {
     Cell cell=row.getCell(col); String val="";
     if(cell!=null) {
      switch(cell.getCellType()) {
       case STRING -> val=cell.getStringCellValue();
       case BOOLEAN -> val=Boolean.toString(cell.getBooleanCellValue());
       case NUMERIC -> val=org.apache.poi.ss.util.NumberToTextConverter.toText(cell.getNumericCellValue());
       case FORMULA, ERROR -> error="Formula and error cells are not accepted.";
       default -> {}
      }
     }
     values.put(headers.get(col),val);
    }
    if(error.isEmpty()&&values.values().stream().allMatch(String::isBlank)) continue;
    result.add(new Row(r+1,values,error+cellErrors(values)));
   }
   return result;
  }
 }
 private String cellErrors(Map<String,String> values) {
  return values.values().stream().anyMatch(s->s.length()>MAX_CELL||s.indexOf('\0')>=0)?" Cell too long or contains null bytes.":"";
 }
 private void headers(List<String> headers) {
  Set<String> expected=new HashSet<>(HEADERS); if(headers.contains("tags")) expected.add("tags");
  if(headers.size()!=expected.size() || !new HashSet<>(headers).equals(expected)) throw new InvalidFile("Headers must match the template; tags is optional. No unknown or duplicate columns.");
 }
 public byte[] template() { return (String.join(",",HEADERS)+",tags\r\n").getBytes(StandardCharsets.UTF_8); }
}
