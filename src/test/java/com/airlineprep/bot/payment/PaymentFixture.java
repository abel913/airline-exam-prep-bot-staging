package com.airlineprep.bot.payment;
import java.math.BigDecimal;
import java.util.UUID;
import com.airlineprep.bot.engine.EngineFixture;
import com.airlineprep.bot.settings.SettingsForm;
import org.springframework.beans.factory.annotation.Autowired;
public abstract class PaymentFixture extends EngineFixture {
 @Autowired protected PaymentService payments;
 @Autowired protected PaymentMethodService methods;
 @Autowired protected PaymentReviewService review;
 @Autowired protected PaymentQueries queries;
 @Autowired protected org.springframework.jdbc.core.JdbcTemplate jdbc;
 protected long method;
 protected void setupPayment() {prepareFixture(2);configure(true,true,new BigDecimal("50.00"));method=methods.save(null,methodForm("TELEBIRR",true),"test-admin");}
 protected PaymentMethodService.Form methodForm(String type,boolean active) {return new PaymentMethodService.Form(type,"Fictional method","Fictional holder","DEVELOPMENT-ONLY","Do not send real money.",active,0,null);}
 protected void configure(boolean enabled,boolean manual,BigDecimal price) {
  var f=SettingsForm.from(settings.current());
  settings.update(new SettingsForm(f.freePracticeLimit(),f.freeMockLimit(),f.questionsPerMock(),price,"ETB",enabled,manual,"Test support",f.mockDurationMinutes()),"test-admin");
 }
 protected long selected() {long id=payments.start(sender,exam,UUID.randomUUID().toString()).request().id();payments.select(sender,id,method);return id;}
 protected ReceiptMetadata receipt(String unique) {return new ReceiptMetadata("test_file",unique,"PHOTO",null,"image/jpeg",100);}
 protected long pending() {long id=selected();payments.reference(sender,id,"DEVTEST-"+UUID.randomUUID());payments.receipt(sender,id,receipt("unique_"+id));return id;}
}
