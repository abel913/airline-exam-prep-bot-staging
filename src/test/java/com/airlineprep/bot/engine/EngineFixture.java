package com.airlineprep.bot.engine;
import java.util.concurrent.atomic.AtomicLong;
import com.airlineprep.bot.admin.*;
import com.airlineprep.bot.user.*;
import com.airlineprep.bot.access.*;
import com.airlineprep.bot.question.*;
import com.airlineprep.bot.settings.*;
import org.springframework.beans.factory.annotation.Autowired;
public abstract class EngineFixture extends com.airlineprep.bot.IsolatedDatabaseSupport {
 static final AtomicLong IDS=new AtomicLong(10000);
 @Autowired protected CatalogService catalog;
 @Autowired protected RegistrationService registration;
 @Autowired protected BotUserRepository users;
 @Autowired protected AccessEntitlementRepository grants;
 @Autowired protected QuestionService questions;
 @Autowired protected SettingsService settings;
 @Autowired protected jakarta.persistence.EntityManager entityManager;
 protected long sender,exam,category;
 protected void prepareFixture(int mockSize) {
  long number=IDS.incrementAndGet();sender=number;
  exam=catalog.save(false,null,new CatalogForm("engine-"+number,"Fictional engine exam","",true,0,null),"test-admin");
  category=catalog.save(true,null,new CatalogForm("numbers","Fictional numbers","",true,0,exam),"test-admin");
  registration.start(sender);registration.language(sender,"en");registration.exam(sender,exam);
  registration.contact(sender,sender,"09"+String.format("%08d",number));
  var entitlement=grant();entitlement.setQuestionsPerMock(mockSize);grants.saveAndFlush(entitlement);
 }
 protected AccessEntitlement grant() {
  var user=users.findByTelegramUserId(sender).orElseThrow();
  return grants.findByUserIdAndExamTypeId(user.getId(),user.getSelectedExamTypeId()).orElseThrow();
 }
 protected QuestionForm form(String text,boolean free,boolean premium,boolean mock) {
  var f=new QuestionForm();f.setExamTypeId(exam);f.setCategoryId(category);f.setQuestionText(text);
  f.setExplanation("Fictional explanation, not real exam content.");f.setDifficulty(Difficulty.EASY);
  f.setSourceType("Original");f.setSourceTitle("Fictional engine tests");f.setUseStatus(UseStatus.ORIGINAL);
  f.setFreePool(free);f.setPremiumPool(premium);f.setMockPool(mock);
  f.getOptions().set(0,"Two");f.getOptions().set(1,"Three");f.setCorrectOption(0);return f;
 }
 protected long question(String text,boolean free,boolean premium,boolean mock) {
  long id=questions.save(null,form(text,free,premium,mock),"test-admin");publish(id);return id;
 }
 protected void publish(long id) {
  questions.transition(id,QuestionStatus.REVIEWED,questions.get(id).getRevision(),"test-admin");
  // Flush between transitions to make the client revision mirror separate requests.
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) entityManager.flush();
  questions.transition(id,QuestionStatus.PUBLISHED,questions.get(id).getRevision(),"test-admin");
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) entityManager.flush();
 }
 protected void content(int count) { for(int i=0;i<count;i++) question("Fictional arithmetic "+i,true,false,true); }
 protected void duration(Integer minutes) {
  var f=SettingsForm.from(settings.current());
  settings.update(new SettingsForm(f.freePracticeLimit(),f.freeMockLimit(),f.questionsPerMock(),f.lifetimePrice(),f.currency(),
   f.paymentEnabled(),f.manualPaymentEnabled(),f.supportInfo(),minutes),"test-admin");
 }
}
