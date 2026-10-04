package com.airlineprep.bot.engine;
import com.airlineprep.bot.analytics.*;
import com.airlineprep.bot.practice.*;
import com.airlineprep.bot.mock.*;
import com.airlineprep.bot.question.*;
import com.airlineprep.bot.admin.*;
import com.airlineprep.bot.user.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in, new disposable loopback database only. Scenario data rolls back. */
@SpringBootTest(properties={"telegram.bot.enabled=false","admin.bootstrap.username=","admin.bootstrap.password="}) @Transactional
class Phase10PostgresIT {
 @DynamicPropertySource static void guard(DynamicPropertyRegistry registry) {
  if(!List.of("127.0.0.1","localhost").contains(System.getenv("DB_HOST"))||!System.getenv("DB_NAME").startsWith("airline_phase9_phase10")) throw new IllegalStateException("Phase 10 integration requires a disposable loopback database.");
 }
 @Autowired CatalogService catalog;@Autowired RegistrationService registration;@Autowired QuestionService questions;
 @Autowired PracticeService practice;@Autowired MockAttemptService mocks;@Autowired StudentInsights insights;
 @Autowired AnalyticsService analytics;@Autowired JdbcTemplate jdbc;@Autowired jakarta.persistence.EntityManager em;
 @Test void realPostgresAnalyticsLargeHistoryAndQueryPlans() {
  assertThat(jdbc.queryForObject("SELECT version()",String.class)).contains("PostgreSQL");
  long exam=catalog.save(false,null,new CatalogForm("pg-phase10","Fictional analytics","",true,0,null),"test");
  long cat=catalog.save(true,null,new CatalogForm("math","Fictional math","",true,0,exam),"test");
  for(int i=0;i<60;i++) {
   var f=new QuestionForm();f.setExamTypeId(exam);f.setCategoryId(cat);f.setQuestionText("Fictional PG analytics "+i);f.setExplanation("Original fixture");f.setDifficulty(Difficulty.EASY);f.setTags("logic, ሂሳብ");f.setSourceType("Original");f.setSourceTitle("Fictional");f.setUseStatus(UseStatus.ORIGINAL);f.setFreePool(true);f.setMockPool(true);f.getOptions().set(0,"One");f.getOptions().set(1,"Two");f.setCorrectOption(0);
   long id=questions.save(null,f,"test");questions.transition(id,QuestionStatus.REVIEWED,questions.get(id).getRevision(),"test");em.flush();questions.transition(id,QuestionStatus.PUBLISHED,questions.get(id).getRevision(),"test");em.flush();
  }
  for(int u=0;u<3;u++) {
   long sender=880000+u;registration.start(sender);registration.language(sender,"en");registration.exam(sender,exam);registration.contact(sender,sender,"0998"+String.format("%06d",u));
   for(int i=0;i<6;i++) {var d=practice.next(sender,null,null,false);practice.answer(sender,d.delivery().id(),1);}
   assertThat(insights.recommendation(sender)).isEqualTo(cat);
   var a=mocks.prepare(sender,"large");mocks.open(sender,a.attempt().id(),0,false);mocks.answer(sender,a.attempt().id(),0,0,0);mocks.submit(sender,a.attempt().id());
   // Large repeat history is fixture data inside this rolled-back isolated transaction.
   jdbc.update("""
    INSERT INTO practice_deliveries(user_id,exam_type_id,question_id,version_id,selected_option,created_at,answered_at)
    SELECT d.user_id,d.exam_type_id,d.question_id,d.version_id,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
    FROM practice_deliveries d CROSS JOIN generate_series(1,200) n WHERE d.id=(SELECT MIN(id) FROM practice_deliveries WHERE user_id=d.user_id)
     AND d.user_id=(SELECT id FROM bot_users WHERE telegram_user_id=?)
    """,sender);
   assertThat(insights.history(sender,false,0).rows()).hasSize(5);assertThat(insights.history(sender,false,0).more()).isTrue();
   assertThat(insights.history(sender,true,0).rows()).hasSize(1);
  }
  var f=new AnalyticsService.Filter("all",null,null,null,null,null,null,null,null);
  for(String type:List.of("questions","categories","registrations","content","mocks","payments","students","outbox")) {
   var report=analytics.report(type,f,0,25);assertThat(report.rows().size()).isLessThanOrEqualTo(25);
   if(type.equals("questions")) {assertThat(report.more()).isTrue();assertThat(analytics.options(Long.parseLong(report.rows().getFirst().getFirst()),f,0).rows()).hasSize(2);}
  }
  assertThat(analytics.overview(f).get("Submitted practice answers (including repeats)")).isEqualTo(618L);
  assertThat(analytics.overview(f).get("Active students")).isEqualTo(3L);
  assertThat(analytics.report("questions",f,0,500).rows()).hasSize(60);
  var plan=jdbc.queryForList("EXPLAIN SELECT id FROM practice_deliveries WHERE user_id=1 ORDER BY id DESC LIMIT 6",String.class);
  assertThat(plan.toString()).contains("Limit");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE indexname IN ('practice_answer_time_idx','mock_item_version_idx','registration_time_idx')",Integer.class)).isEqualTo(3);
 }
}
