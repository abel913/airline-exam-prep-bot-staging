package com.airlineprep.bot;
import java.util.*;
import com.airlineprep.bot.admin.*;
import com.airlineprep.bot.user.*;
import com.airlineprep.bot.question.*;
import com.airlineprep.bot.settings.*;
import com.airlineprep.bot.payment.*;
import com.airlineprep.bot.mock.*;
import com.airlineprep.bot.practice.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
class RestartPersistenceTests {
 ConfigurableApplicationContext open(String database) {
  return new SpringApplicationBuilder(AirlineExamPrepApplication.class).run(
   "--server.port=0","--spring.datasource.url="+database,"--spring.datasource.driver-class-name=org.h2.Driver","--spring.datasource.username=sa","--spring.datasource.password=",
   "--spring.flyway.url="+database,"--spring.flyway.user=sa","--spring.flyway.password=","--telegram.bot.enabled=false","--telegram.bot.token=","--telegram.admin.id=",
   "--registration.phone-hmac-key=dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1ub3QtYS1zZWNyZXQ=","--admin.bootstrap.username=","--admin.bootstrap.password=","--payment.notifications.automatic=false","--debug=false");
 }
 @Test void freshApplicationContextResumesEveryPersistentWorkflowWithoutNetwork() {
  String database="jdbc:h2:mem:restart-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
  long attempt,delivery;java.time.Instant deadline;long[] payments=new long[4];
  try(var app=open(database)) {
   var catalog=app.getBean(CatalogService.class);var registration=app.getBean(RegistrationService.class);var settings=app.getBean(SettingsService.class);
   var cfg=SettingsForm.from(settings.current());settings.update(new SettingsForm(100,2,1,cfg.lifetimePrice(),"ETB",true,true,"Fictional support",1440),"test-admin");
   long exam=catalog.save(false,null,new CatalogForm("restart","Fictional restart","",true,0,null),"test-admin");
   long category=catalog.save(true,null,new CatalogForm("restart","Fictional restart","",true,0,exam),"test-admin");
   registration.start(700001);registration.language(700001,"am");
   for(long sender=700002;sender<=700005;sender++){registration.start(sender);registration.language(sender,"en");registration.exam(sender,exam);registration.contact(sender,sender,"09"+String.format("%08d",sender));}
   var questions=app.getBean(QuestionService.class);var form=new QuestionForm();form.setExamTypeId(exam);form.setCategoryId(category);form.setQuestionText("Fictional restart question");form.setExplanation("Fictional explanation");form.setDifficulty(Difficulty.EASY);form.setSourceType("Original");form.setSourceTitle("Fictional");form.setUseStatus(UseStatus.ORIGINAL);form.setFreePool(true);form.setMockPool(true);form.getOptions().set(0,"One");form.getOptions().set(1,"Two");form.setCorrectOption(0);
   form.setTags("restart, logic");
   long question=questions.save(null,form,"test-admin");questions.transition(question,QuestionStatus.REVIEWED,questions.get(question).getRevision(),"test-admin");questions.transition(question,QuestionStatus.PUBLISHED,questions.get(question).getRevision(),"test-admin");
   var practice=app.getBean(PracticeService.class);delivery=practice.next(700002,null,null,false).delivery().id();practice.answer(700002,delivery,0);
   var mocks=app.getBean(MockAttemptService.class);attempt=mocks.prepare(700002,"restart").attempt().id();deadline=mocks.open(700002,attempt,0,false).attempt().deadline();mocks.answer(700002,attempt,0,0,0);
   long method=app.getBean(PaymentMethodService.class).save(null,new PaymentMethodService.Form("BANK_TRANSFER","Fictional","Test","NOT-REAL","No money",true,0,null),"test-admin");
   var service=app.getBean(PaymentService.class);
   for(int i=0;i<4;i++) {long sender=700002+i;long id=service.start(sender,"restart").request().id();payments[i]=id;service.select(sender,id,method);if(i>0)service.reference(sender,id,"DEVTEST-RESTART-"+i);if(i>1)service.receipt(sender,id,new ReceiptMetadata("fictional","unique_"+i,"PHOTO",null,"image/jpeg",100));if(i==3)app.getBean(PaymentReviewService.class).approve(id,"test-admin");}
   settings.maintenance(true,"test-admin");
  }
  try(var app=open(database)) {
   assertThat(app.getBean(SettingsService.class).current().getMaintenanceEnabled()).isTrue();
   assertThat(app.getBean(StudentInsights.class).history(700002,false,0).rows()).hasSize(1);
   assertThat(app.getBean(StudentInsights.class).history(700002,true,0).rows()).hasSize(1);
   assertThat(app.getBean(JdbcTemplate.class).queryForObject("SELECT tags FROM question_versions LIMIT 1",String.class)).isEqualTo("restart,logic");
   app.getBean(SettingsService.class).maintenance(false,"test-admin");
   assertThat(app.getBean(RegistrationService.class).start(700001).status()).isEqualTo(RegistrationStatus.EXAM_TYPE_REQUIRED);
   assertThat(app.getBean(PracticeService.class).delivery(700002,delivery).answered()).isTrue();
   var resumed=app.getBean(MockAttemptService.class).open(700002,attempt,null,false);assertThat(resumed.attempt().deadline()).isEqualTo(deadline);assertThat(resumed.item().selected()).isZero();
   var queries=app.getBean(PaymentQueries.class);assertThat(queries.get(payments[0]).status()).isEqualTo(PaymentStatus.AWAITING_REFERENCE);assertThat(queries.get(payments[1]).status()).isEqualTo(PaymentStatus.AWAITING_RECEIPT);assertThat(queries.get(payments[2]).status()).isEqualTo(PaymentStatus.PENDING_REVIEW);assertThat(queries.get(payments[3]).status()).isEqualTo(PaymentStatus.APPROVED);
   assertThat(app.getBean(PaymentService.class).status(700005).student().lifetime()).isTrue();
   assertThat(app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM payment_notifications WHERE status='NEW' AND kind='USER_APPROVED'",Integer.class)).isEqualTo(1);
  }
 }
}
