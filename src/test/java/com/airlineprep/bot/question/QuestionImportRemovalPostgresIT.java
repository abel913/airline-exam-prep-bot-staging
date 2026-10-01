package com.airlineprep.bot.question;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.airlineprep.bot.admin.CatalogForm;
import com.airlineprep.bot.admin.CatalogService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly opt in with PG_TEST_URL pointing at a disposable local PostgreSQL database. */
@SpringBootTest(properties={"telegram.bot.enabled=false","admin.bootstrap.username=","admin.bootstrap.password="})
class QuestionImportRemovalPostgresIT {
 @DynamicPropertySource
 static void postgres(DynamicPropertyRegistry registry) {
  String url=Objects.requireNonNull(System.getenv("PG_TEST_URL"),"Set PG_TEST_URL to a disposable local PostgreSQL database.");
  registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  registry.add("spring.datasource.username",()->"postgres");registry.add("spring.datasource.password",()->"");
  registry.add("spring.flyway.url",()->url);registry.add("spring.flyway.user",()->"postgres");registry.add("spring.flyway.password",()->"");
  registry.add("registration.phone-hmac-key",()->"dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1ub3QtYS1zZWNyZXQ=");
 }
 @Autowired org.flywaydb.core.Flyway flyway;
 @Autowired JdbcTemplate jdbc;
 @Autowired CatalogService catalog;
 @Autowired QuestionFileParser parser;
 @Autowired QuestionImportService imports;
 @Autowired QuestionService questions;
 @Autowired QuestionRepository questionRepository;
 @Autowired QuestionImportRemovalService removal;
 @Autowired ImportRowRepository rows;

 @Test void postgresFlywayAndImportRemovalIntegration() throws Exception {
  flyway.validate();
  assertThat(jdbc.queryForObject("SELECT version()",String.class)).contains("PostgreSQL");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='16' AND success",Integer.class)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='question_import_rows' AND column_name='removed_question_id'",Integer.class)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname='question_import_removed_idx'",Integer.class)).isEqualTo(1);
  String rowLimit=jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='question_import_batches'::regclass AND conname='question_import_batch_row_limit'",String.class);
  assertThat(rowLimit).contains("5000");

  String examCode="release-"+UUID.randomUUID().toString().substring(0,8);
  long exam=catalog.save(false,null,new CatalogForm(examCode,"Fictional PostgreSQL release verification","",true,0,null),"release-test");
  catalog.save(true,null,new CatalogForm("numbers","Fictional arithmetic","",true,0,exam),"release-test");
  long draftBatch=importOne(examCode,"numbers","Fictional PostgreSQL safe import draft");
  long draftId=rows.findByBatchIdOrderByRowNumber(draftBatch).getFirst().getQuestionId();
  var deleted=removal.remove(draftBatch,"release-test");
  assertThat(deleted.deleted()).isEqualTo(1);assertThat(questionRepository.findById(draftId)).isEmpty();
  assertThat(jdbc.queryForObject("SELECT removed_question_id FROM question_import_rows WHERE batch_id=?",Long.class,draftBatch)).isEqualTo(draftId);
  assertThat(imports.get(draftBatch)).isNotNull();

  long publishedBatch=importOne(examCode,"numbers","Fictional PostgreSQL published import question");
  long publishedId=rows.findByBatchIdOrderByRowNumber(publishedBatch).getFirst().getQuestionId();
  questions.transition(publishedId,QuestionStatus.REVIEWED,questions.get(publishedId).getRevision(),"release-test");
  questions.transition(publishedId,QuestionStatus.PUBLISHED,questions.get(publishedId).getRevision(),"release-test");
  long versionCount=jdbc.queryForObject("SELECT COUNT(*) FROM question_versions WHERE question_id=?",Long.class,publishedId);
  var archived=removal.remove(publishedBatch,"release-test");
  assertThat(archived.archived()).isEqualTo(1);assertThat(questions.get(publishedId).getStatus()).isEqualTo(QuestionStatus.ARCHIVED);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_versions WHERE question_id=?",Long.class,publishedId)).isEqualTo(versionCount);
  assertThat(imports.get(publishedBatch)).isNotNull();
  imports.deleteHistory(publishedBatch,"release-test");
  assertThat(questionRepository.findById(publishedId)).isPresent();assertThat(questions.get(publishedId).getStatus()).isEqualTo(QuestionStatus.ARCHIVED);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_import_rows WHERE batch_id=?",Integer.class,publishedBatch)).isZero();
 }

 private long importOne(String exam,String category,String question) throws Exception {
  Map<String,String> value=new HashMap<>();QuestionFileParser.HEADERS.forEach(h->value.put(h,""));
  value.put("exam_type",exam);value.put("category",category);value.put("question",question);
  value.put("option_a","One");value.put("option_b","Two");value.put("correct_answer","A");
  value.put("explanation","Fictional PostgreSQL test");value.put("difficulty","EASY");value.put("source_type","Original");
  value.put("source","Fictional release test");value.put("copyright_status","ORIGINAL");
  value.put("free_available","true");value.put("premium_available","false");value.put("mock_available","true");
  StringWriter writer=new StringWriter();
  try(CSVPrinter csv=new CSVPrinter(writer,CSVFormat.RFC4180)) {csv.printRecord(QuestionFileParser.HEADERS);csv.printRecord(QuestionFileParser.HEADERS.stream().map(value::get).toList());}
  var parsed=parser.parse(new MockMultipartFile("file","release.csv","text/csv",writer.toString().getBytes(StandardCharsets.UTF_8)));
  long batch=imports.stage(parsed,"release-test");imports.confirm(batch,"release-test");return batch;
 }
}
