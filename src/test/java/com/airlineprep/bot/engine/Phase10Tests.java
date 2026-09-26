package com.airlineprep.bot.engine;
import com.airlineprep.bot.analytics.*;
import com.airlineprep.bot.practice.*;
import com.airlineprep.bot.mock.*;
import com.airlineprep.bot.question.*;
import com.airlineprep.bot.telegram.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.MessageSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.mockito.Mockito.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class Phase10Tests extends EngineFixture {
 @Autowired StudentInsights insights;@Autowired PracticeService practice;@Autowired MockAttemptService mocks;
 @Autowired StudentProgressService progress;@Autowired AnalyticsService analytics;@Autowired JdbcTemplate jdbc;
 @Autowired MockMvc mvc;@Autowired MessageSource messages;@Autowired ObjectMapper json;
 @Autowired QuestionFileParser parser;@Autowired QuestionImportService imports;
 @BeforeEach void setup() {prepareFixture(2);}
 AnalyticsService.Filter filter(String window) {return new AnalyticsService.Filter(window,null,null,"","","","","id",null);}
 @Test void zeroHistoryAndOneAnswerNeedMoreData() {
  assertThat(insights.history(sender,false,0).rows()).isEmpty();assertThat(insights.insights(sender,0).rows()).isEmpty();assertThat(insights.recommendation(sender)).isNull();
  content(1);var d=practice.next(sender,null,null,false);practice.answer(sender,d.delivery().id(),0);
  assertThat(insights.insights(sender,0).rows().getFirst().key()).isEqualTo("insights.more");
  assertThat(insights.history(sender,false,0).rows().getFirst().correct()).isTrue();
  assertThat(insights.recommendation(sender)).isNull();
 }
 @Test void weakStrongRepeatAndRecommendationUseFirstAnswers() {
  content(7);for(int i=0;i<5;i++){var d=practice.next(sender,null,null,false);practice.answer(sender,d.delivery().id(),1);}
  var repeated=practice.next(sender,null,null,true);practice.answer(sender,repeated.delivery().id(),0);
  var weak=insights.insights(sender,0).rows().getFirst();assertThat(weak.answered()).isEqualTo(5);assertThat(weak.correct()).isZero();assertThat(weak.key()).isEqualTo("insights.weak");
  assertThat(insights.recommendation(sender)).isEqualTo(category);assertThat(insights.history(sender,false,0).more()).isTrue();assertThat(insights.history(sender,false,1).rows()).hasSize(1);
  assertThat(new StudentInsights.Insight(category,"test",5,4).key()).isEqualTo("insights.strong");
  assertThat(new StudentInsights.Insight(category,"test",5,3).key()).isEqualTo("insights.steady");
 }
 @Test void historySurvivesRevisionArchiveAndReload() {
  long q=question("history original",true,false,true);var d=practice.next(sender,null,null,false);practice.answer(sender,d.delivery().id(),0);
  var edit=questions.form(q);edit.setCorrectOption(1);edit.setTags("Logic, LOGIC, ሂሳብ");questions.save(q,edit,"test-admin");publish(q);
  questions.transition(q,QuestionStatus.ARCHIVED,questions.get(q).getRevision(),"test-admin");entityManager.flush();entityManager.clear();
  assertThat(insights.history(sender,false,0).rows().getFirst().correct()).isTrue();assertThat(practice.delivery(sender,d.delivery().id()).question().text()).isEqualTo("history original");
  assertThat(questions.form(q).getTags()).isEqualTo("logic,ሂሳብ");
 }
 @Test void mockHistoryAndOwnershipKeepFrozenScores() {
  content(2);var a=mocks.prepare(sender,"phase10");mocks.open(sender,a.attempt().id(),0,false);mocks.answer(sender,a.attempt().id(),0,0,0);mocks.submit(sender,a.attempt().id());
  var h=insights.history(sender,true,0).rows().getFirst();assertThat(h.right()).isEqualTo(1);assertThat(h.unanswered()).isEqualTo(1);
  long other=sender+900000;registration.start(other);registration.language(other,"en");registration.exam(other,exam);registration.contact(other,other,"09"+String.format("%08d",other));
  assertThat(insights.history(other,true,0).rows()).isEmpty();assertThatThrownBy(()->mocks.open(other,a.attempt().id(),null,false)).hasMessage("student.invalid");
 }
 @Test void analyticsReportsAndVersionOptionsAreExact() {
  content(2);var d=practice.next(sender,null,null,false);practice.answer(sender,d.delivery().id(),0);
  var repeat=practice.next(sender,null,null,true);practice.answer(sender,repeat.delivery().id(),1);
  var f=filter("all");var report=analytics.report("questions",f,0,25);assertThat(report.rows()).hasSize(2);
  var row=report.rows().stream().filter(r->r.getFirst().equals(Long.toString(d.question().id()))).findFirst().orElseThrow();
  assertThat(row.get(report.headers().indexOf("practice_answers"))).isEqualTo("1");assertThat(row.get(report.headers().indexOf("practice_correct"))).isEqualTo("1");assertThat(row).contains("Insufficient data");
  var options=analytics.options(d.question().id(),f,0);assertThat(options.rows().getFirst().get(4)).isEqualTo("1");
  assertThat(analytics.overview(f).get("Submitted practice answers (including repeats)")).isEqualTo(2L);
  for(String type:List.of("categories","registrations","content","payments","mocks","students","outbox")) assertThatCode(()->analytics.report(type,f,0,25)).doesNotThrowAnyException();
 }
 @Test void utcWindowsAndBoundsAreDeterministic() {
  var a=new AnalyticsService(jdbc,Clock.fixed(Instant.parse("2026-09-26T23:59:00Z"),ZoneOffset.ofHours(3)));
  assertThat(a.since("today").toInstant()).isEqualTo(Instant.parse("2026-09-26T00:00:00Z"));
  assertThat(a.since("7").toInstant()).isEqualTo(Instant.parse("2026-09-20T00:00:00Z"));
  assertThat(a.since("30").toInstant()).isEqualTo(Instant.parse("2026-08-28T00:00:00Z"));assertThat(a.since("all").toInstant()).isEqualTo(Instant.EPOCH);
  assertThatThrownBy(()->filter("365")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->insights.history(sender,false,-1)).hasMessage("student.invalid");
 }
 @Test void tagsAndFiltersRejectInjectionAndPreserveLegacySearch() {
  assertThat(QuestionTags.normalize(" Logic ,LOGIC, Arithmetic ")).isEqualTo("logic,arithmetic");
  for(String invalid:List.of("<script>","x_y","a,,b","x".repeat(33),"a,b,c,d,e,f,g,h,i")) assertThatThrownBy(()->QuestionTags.normalize(invalid)).isInstanceOf(IllegalArgumentException.class);
  var f=form("tagged sample",true,false,true);f.setTags("Logic");questions.save(null,f,"test-admin");
  assertThat(questions.search("",exam,null,null,null,null,0,"updatedAt",Difficulty.EASY,"logic").getTotalElements()).isEqualTo(1);
  assertThat(questions.search("",exam,null,null,null,null,0,"updatedAt",Difficulty.HARD,"logic").isEmpty()).isTrue();
 }
 @Test void csvProtectsFormulasAndPreservesUnicode() throws Exception {
  for(String s:List.of("=1+1"," +2","-2","@SUM(A1)","\t=cmd","\uFEFF=1")) assertThat(SafeCsv.cell(s)).startsWith("\"'");
  assertThat(SafeCsv.cell("ሂሳብ, \"test\"\nnext")).isEqualTo("\"ሂሳብ, \"\"test\"\"\nnext\"");
  mvc.perform(get("/admin/analytics/questions/export.csv")).andExpect(status().is3xxRedirection());
  mvc.perform(get("/admin/analytics/questions/export.csv").with(user("test-admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(header().string("X-Export-Row-Limit","500"));
 }
 @Test void allAdminPagesRenderAndRejectInvalidParameters() throws Exception {
  content(1);
  for(String path:List.of("","/questions","/categories","/registrations","/content","/mocks","/payments","/students","/outbox")) {
   mvc.perform(get("/admin/analytics"+path).with(user("test-admin").roles("ADMIN"))).andExpect(r -> { if(r.getResolvedException()!=null) throw new AssertionError(r.getResolvedException()); }).andExpect(status().isOk());
  }
  long id=users.findByTelegramUserId(sender).orElseThrow().getId();
  mvc.perform(get("/admin/analytics/students/"+id).with(user("test-admin").roles("ADMIN"))).andExpect(r -> { if(r.getResolvedException()!=null) throw new AssertionError(r.getResolvedException()); }).andExpect(status().isOk());
  mvc.perform(get("/admin/analytics/questions").param("sort","id;drop").with(user("test-admin").roles("ADMIN"))).andExpect(status().isBadRequest());
  mvc.perform(post("/admin/analytics/maintenance").param("enabled","true").with(user("test-admin").roles("ADMIN"))).andExpect(status().isForbidden());
 }
 @Test void maintenanceAcknowledgesWithoutMutationAndResumes() throws Exception {
  content(2);var d=practice.next(sender,null,null,false);var a=mocks.prepare(sender,"maintenance");
  var client=mock(TelegramBotClient.class);var flow=new StudentFlow(practice,mocks,progress,new StudentPresenter(client,messages),settings).withInsights(insights);
  var handler=new TelegramUpdateHandler(client,registration,new RegistrationPresenter(client,messages),flow);
  settings.maintenance(true,"test-admin");entityManager.flush();
  handler.handleWebhook(json.readTree("{\"update_id\":1,\"message\":{\"chat\":{\"id\":"+sender+",\"type\":\"private\"},\"from\":{\"id\":"+sender+"},\"text\":\"/start\"}}"),"testbot");
  assertThat(grant().getPracticeUsed()).isZero();assertThat(grant().getMocksUsed()).isZero();assertThat(practice.delivery(sender,d.delivery().id()).answered()).isFalse();
  verify(client).sendMessage(eq(sender),contains("maintenance"),anyMap());
  mvc.perform(get("/actuator/health")).andExpect(status().isOk());mvc.perform(get("/admin").with(user("test-admin").roles("ADMIN"))).andExpect(r -> { if(r.getResolvedException()!=null) throw new AssertionError(r.getResolvedException()); }).andExpect(status().isOk());
  mvc.perform(post("/admin/analytics/maintenance").with(user("test-admin").roles("ADMIN")).with(csrf()).param("enabled","false")).andExpect(status().is3xxRedirection());
  entityManager.flush();assertThat(insights.maintenanceLanguage(sender)).isNull();practice.answer(sender,d.delivery().id(),0);mocks.open(sender,a.attempt().id(),0,false);assertThat(grant().getPracticeUsed()).isEqualTo(1);
 }
 @Test void historyCallbacksAreOwnedReplaySafeAndLocalized() throws Exception {
  content(2);var d=practice.next(sender,null,null,false);practice.answer(sender,d.delivery().id(),0);
  var client=mock(TelegramBotClient.class);var flow=new StudentFlow(practice,mocks,progress,new StudentPresenter(client,messages),settings).withInsights(insights);
  for(String callback:List.of("s:ph:0","s:ph:1","s:mh:0","s:weak:0","s:recommend","p:h:"+d.delivery().id(),"p:h:"+d.delivery().id())) flow.callback(sender,callback);
  assertThat(grant().getPracticeUsed()).isEqualTo(1);
  long other=sender+800000;registration.start(other);registration.language(other,"am");registration.exam(other,exam);registration.contact(other,other,"09"+String.format("%08d",other));
  clearInvocations(client);flow.callback(other,"p:h:"+d.delivery().id());
  verify(client).sendMessage(eq(other),eq(messages.getMessage("student.invalid",null,Locale.forLanguageTag("am"))),anyMap());
  assertThat(insights.history(other,false,0).rows()).isEmpty();
 }
 @Test void maintenanceBlocksAnswerMockAndPaymentCallbacksWithoutAllowanceChanges() throws Exception {
  content(2);var d=practice.next(sender,null,null,false);var a=mocks.prepare(sender,"hold");
  var client=mock(TelegramBotClient.class);var flow=new StudentFlow(practice,mocks,progress,new StudentPresenter(client,messages),settings).withInsights(insights);
  var handler=new TelegramUpdateHandler(client,registration,new RegistrationPresenter(client,messages),flow);
  settings.maintenance(true,"test-admin");entityManager.flush();
  for(String callback:List.of("p:a:"+d.delivery().id()+":0","m:o:"+a.attempt().id()+":0","pay:status","s:ph:0")) {
   var root=json.createObjectNode();root.put("update_id",99);var cb=root.putObject("callback_query");cb.put("id","fictional");cb.put("data",callback);cb.putObject("from").put("id",sender);cb.putObject("message").putObject("chat").put("id",sender).put("type","private");handler.handleWebhook(root,"testbot");
  }
  assertThat(grant().getPracticeUsed()).isZero();assertThat(grant().getMocksUsed()).isZero();
  assertThat(jdbc.queryForObject("SELECT status FROM mock_attempts WHERE id=?",String.class,a.attempt().id())).isEqualTo("READY");
 }
 @Test void mockHistoryIncludesPreparedAndZeroAnswerExpiryWithoutReviewLeak() {
  duration(1);content(2);var a=mocks.prepare(sender,"expiry");
  assertThat(insights.history(sender,true,0).rows().getFirst().status()).isEqualTo("READY");mocks.open(sender,a.attempt().id(),0,false);
  jdbc.update("UPDATE mock_attempts SET deadline_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(2)),a.attempt().id());mocks.introduction(sender);
  var h=insights.history(sender,true,0).rows().getFirst();assertThat(h.status()).isEqualTo("EXPIRED");assertThat(h.unanswered()).isEqualTo(2);assertThat(grant().getMocksUsed()).isZero();assertThat(mocks.open(sender,a.attempt().id(),0,true).question()).isNull();
 }
 @Test void tagsImportSupportsBothHeaderGenerationsAndRejectsBadTags() throws Exception {
  var values=new LinkedHashMap<String,String>();for(String h:QuestionFileParser.HEADERS) values.put(h,"");
  values.put("exam_type",jdbc.queryForObject("SELECT code FROM exam_types WHERE id=?",String.class,exam));values.put("category","numbers");values.put("question","import tag sample");values.put("option_a","one");values.put("option_b","two");values.put("correct_answer","A");values.put("explanation","original explanation");values.put("difficulty","EASY");values.put("source_type","Original");values.put("source","Fictional");values.put("copyright_status","ORIGINAL");values.put("free_available","true");values.put("premium_available","false");values.put("mock_available","false");
  for(boolean tagged:List.of(false,true)) {
   if(tagged) {values.put("tags","Logic, LOGIC");values.put("question","import tagged variant");}
   var writer=new java.io.StringWriter();try(var csv=new org.apache.commons.csv.CSVPrinter(writer,org.apache.commons.csv.CSVFormat.RFC4180)){csv.printRecord(values.keySet());csv.printRecord(values.values());}
   var parsed=parser.parse(new org.springframework.mock.web.MockMultipartFile("file","sample.csv","text/csv",writer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   long batch=imports.stage(parsed,"test-admin");assertThat(imports.confirm(batch,"test-admin").getImportedRows()).isEqualTo(1);
  }
 }
 @Test void windowCountsRespectFirstAnswerDatesAndCsvFiltersEscapeContent() throws Exception {
  var f=form("=HYPERLINK fake ሂሳብ",true,false,true);f.setTags("logic");long q=questions.save(null,f,"test-admin");publish(q);
  var d=practice.next(sender,null,null,false);practice.answer(sender,d.delivery().id(),0);
  jdbc.update("UPDATE practice_deliveries SET answered_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(40L*86400)),d.delivery().id());
  assertThat(analytics.overview(filter("7")).get("Submitted practice answers (including repeats)")).isEqualTo(0L);assertThat(analytics.overview(filter("all")).get("Submitted practice answers (including repeats)")).isEqualTo(1L);
  var response=mvc.perform(get("/admin/analytics/questions/export.csv").param("window","all").param("tag","logic").with(user("test-admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
  assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("'=HYPERLINK fake ሂሳብ").doesNotContain("phone_identity_hash");
  var empty=mvc.perform(get("/admin/analytics/questions/export.csv").param("tag","missing").with(user("test-admin").roles("ADMIN"))).andReturn().getResponse();assertThat(empty.getContentAsString()).doesNotContain("HYPERLINK");
 }
 @Test void largeExportIsCappedAndPagesRemainBounded() throws Exception {
  for(int i=0;i<505;i++) questions.save(null,form("Fictional export "+i,true,false,false),"test-admin");
  var report=analytics.report("questions",filter("all"),0,25);assertThat(report.rows()).hasSize(25);assertThat(report.more()).isTrue();
  var response=mvc.perform(get("/admin/analytics/questions/export.csv").param("window","all").with(user("test-admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(header().string("X-Export-Truncated","true")).andReturn().getResponse();
  String csv=response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertThat(csv.lines().count()).isEqualTo(501);
 }
 @Test void tagMetadataRendersEscapedAndOptionPageLoads() throws Exception {
  long q=question("<script>alert('fixture')</script>",true,false,true);
  mvc.perform(get("/admin/analytics/questions/"+q).with(user("test-admin").roles("ADMIN"))).andExpect(status().isOk());
  String html=mvc.perform(get("/admin/analytics/questions").with(user("test-admin").roles("ADMIN"))).andReturn().getResponse().getContentAsString();
  assertThat(html).doesNotContain("<script>alert").contains("&lt;script&gt;");
  mvc.perform(get("/admin/questions").param("difficulty","EASY").param("tag","logic").with(user("test-admin").roles("ADMIN"))).andExpect(status().isOk());
 }
}
