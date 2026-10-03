package com.airlineprep.bot.engine;
import java.util.UUID;
import com.airlineprep.bot.admin.*;
import com.airlineprep.bot.user.*;
import com.airlineprep.bot.question.*;
import com.airlineprep.bot.practice.*;
import com.airlineprep.bot.mock.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
/** Explicit opt-in: mvnw -Dtest=PostgresExamEngineIT test. All scenario writes roll back. */
@SpringBootTest(properties={"telegram.bot.enabled=false","admin.bootstrap.username=","admin.bootstrap.password="})
@Transactional
class PostgresExamEngineIT {
 @Autowired CatalogService catalog;
 @Autowired RegistrationService registration;
 @Autowired QuestionService questions;
 @Autowired PracticeService practice;
 @Autowired MockAttemptService mocks;
 @Autowired StudentProgressService progress;
 @Autowired JdbcTemplate jdbc;
 @Autowired jakarta.persistence.EntityManager entityManager;
 @Test void realPostgresFullDefaultMockPracticeHistoricalReviewAndLimits() {
  assertThat(jdbc.queryForObject("SELECT version()",String.class)).contains("PostgreSQL");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='practice_update_receipts'",Integer.class)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname='practice_update_receipts_delivery_idx'",Integer.class)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='practice_update_receipts'::regclass AND contype='p'",String.class)).isEqualTo("PRIMARY KEY (update_id)");
  assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='practice_update_receipts'::regclass AND contype='f'",String.class)).contains("FOREIGN KEY (delivery_id, user_id, question_id) REFERENCES practice_deliveries(id, user_id, question_id)");
  assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid='practice_update_receipts'::regclass",Boolean.class)).isTrue();
  String suffix=UUID.randomUUID().toString();long sender=7000000000000L+Math.floorMod(suffix.hashCode(),1000000000);
  long exam=catalog.save(false,null,new CatalogForm("pg-"+suffix,"Fictional PostgreSQL verification","",true,0,null),"verification");
  long category=catalog.save(true,null,new CatalogForm("test","Fictional arithmetic","",true,0,exam),"verification");
  registration.start(sender);registration.language(sender,"en");registration.exam(sender,exam);
  registration.contact(sender,sender,"09"+String.format("%08d",Math.floorMod(suffix.hashCode(),100000000)));
  for(int i=0;i<101;i++) {
   QuestionForm f=new QuestionForm();f.setExamTypeId(exam);f.setCategoryId(category);f.setQuestionText("Fictional verification "+i);
   f.setExplanation("Fictional test explanation");f.setDifficulty(Difficulty.EASY);f.setSourceType("Original");
   f.setSourceTitle("Fictional verification");f.setUseStatus(UseStatus.ORIGINAL);f.setFreePool(true);f.setMockPool(true);
   f.getOptions().set(0,"One");f.getOptions().set(1,"Two");f.setCorrectOption(0);
   long id=questions.save(null,f,"verification");
   questions.transition(id,QuestionStatus.REVIEWED,questions.get(id).getRevision(),"verification");entityManager.flush();
   questions.transition(id,QuestionStatus.PUBLISHED,questions.get(id).getRevision(),"verification");entityManager.flush();
  }
  Long previous=null;
  for(int i=0;i<100;i++) {var p=practice.next(sender,null,previous,false);practice.answer(sender,p.delivery().id(),0);previous=p.delivery().id();}
  assertThat(progress.get(sender).answered()).isEqualTo(100);
  assertThatThrownBy(()->practice.next(sender,null,null,false)).hasMessage("practice.limit");
  var review=practice.next(sender,null,null,true);practice.answer(sender,review.delivery().id(),1);
  assertThat(progress.get(sender).student().grant().getPracticeUsed()).isEqualTo(100);
  for(int n=0;n<2;n++) {
   var ready=mocks.prepare(sender,"pg"+n);long id=ready.attempt().id();assertThat(ready.attempt().count()).isEqualTo(50);
   assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT question_id) FROM mock_items WHERE attempt_id=?",Integer.class,id)).isEqualTo(50);
   mocks.open(sender,id,0,false);
   for(int i=0;i<40;i++) mocks.answer(sender,id,i,i<30?0:1,0);
   var result=mocks.submit(sender,id);assertThat(result.score().correct()).isEqualTo(30);
   assertThat(result.score().incorrect()).isEqualTo(10);assertThat(result.score().unanswered()).isEqualTo(10);
   assertThat(result.score().percentage()).isEqualTo(60);assertThat(mocks.open(sender,id,49,true).question().explanation()).contains("Fictional");
  }
  assertThatThrownBy(()->mocks.prepare(sender,"third")).hasMessage("mock.limit");
  assertThat(progress.get(sender).completed()).isEqualTo(2);
 }
}
