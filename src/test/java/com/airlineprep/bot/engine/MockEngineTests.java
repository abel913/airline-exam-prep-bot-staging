package com.airlineprep.bot.engine;
import java.time.*;
import java.util.*;
import com.airlineprep.bot.mock.*;
import com.airlineprep.bot.practice.*;
import com.airlineprep.bot.question.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest @Transactional
class MockEngineTests extends EngineFixture {
 @Test void answerJustBeforeDeadlineThenSubmitAfterDeadlineUsesFrozenScore() {
  duration(1);content(2);var opened=start("near-deadline");long id=opened.attempt().id();
  when(clock.instant()).thenReturn(now.plusSeconds(59));mocks.answer(sender,id,0,0,0);
  when(clock.instant()).thenReturn(now.plusSeconds(61));var result=mocks.submit(sender,id);
  assertThat(result.attempt().status()).isEqualTo("EXPIRED");assertThat(result.score().correct()).isEqualTo(1);assertThat(result.score().unanswered()).isEqualTo(1);assertThat(grant().getMocksUsed()).isEqualTo(1);
 }
 @Autowired MockAttemptService mocks;
 @Autowired StudentProgressService progress;
 @Autowired PracticeService practice;
 @Autowired JdbcTemplate jdbc;
 @MockitoBean Clock clock;
 Instant now=Instant.parse("2026-09-01T10:00:00Z");
 @BeforeEach void setup() { when(clock.instant()).thenReturn(now);prepareFixture(2); }
 MockAttemptService.View start(String key) {
  var ready=mocks.prepare(sender,key);return mocks.open(sender,ready.attempt().id(),0,false);
 }
 @Test void introReadyFreezeAndOneActiveCostZero() {
  content(3);
  assertThat(mocks.introduction(sender).active()).isNull();assertThat(grant().getMocksUsed()).isZero();
  var a=mocks.prepare(sender,"start1");var b=mocks.prepare(sender,"start2");
  assertThat(a.attempt().status()).isEqualTo("READY");assertThat(a.attempt().count()).isEqualTo(2);
  assertThat(a.attempt().id()).isEqualTo(b.attempt().id());assertThat(grant().getMocksUsed()).isZero();
  assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT question_id) FROM mock_items WHERE attempt_id=?",Integer.class,a.attempt().id())).isEqualTo(2);
 }
 @Test void firstAnswerChangesDuplicatesAndSubmitAreIdempotent() {
  content(2);var a=start("stable");long id=a.attempt().id();
  mocks.answer(sender,id,0,1,0);assertThat(grant().getMocksUsed()).isEqualTo(1);
  mocks.answer(sender,id,0,0,1);
  mocks.answer(sender,id,0,1,0); // An old callback must not undo the newer answer.
  assertThat(mocks.open(sender,id,0,false).item().selected()).isEqualTo(0);
  var result=mocks.submit(sender,id);
  assertThat(result.score().correct()).isEqualTo(1);assertThat(result.score().unanswered()).isEqualTo(1);
  assertThat(mocks.submit(sender,id).score()).isEqualTo(result.score());
  assertThat(mocks.answer(sender,id,1,0,0).score()).isEqualTo(result.score());
  assertThat(mocks.prepare(sender,"stable").attempt().id()).isEqualTo(id);
  assertThat(grant().getMocksUsed()).isEqualTo(1);assertThat(grant().getPracticeUsed()).isZero();
 }
 @Test void fiftyQuestionDefaultScoringCategoriesReviewAndSnapshots() {
  var current=com.airlineprep.bot.settings.SettingsForm.from(settings.current());
  settings.update(new com.airlineprep.bot.settings.SettingsForm(current.freePracticeLimit(),current.freeMockLimit(),50,current.lifetimePrice(),
   current.currency(),current.paymentEnabled(),current.manualPaymentEnabled(),current.supportInfo(),current.mockDurationMinutes()),"test-admin");
  long second=catalog.save(true,null,new com.airlineprep.bot.admin.CatalogForm("logic","Fictional logic","",true,0,exam),"test-admin");
  content(25);category=second;for(int i=25;i<50;i++) question("Fictional "+i,true,false,true);
  var a=start("fifty");long id=a.attempt().id();
  assertThat(a.attempt().count()).isEqualTo(50);
  assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT question_id) FROM mock_items WHERE attempt_id=?",Integer.class,id)).isEqualTo(50);
  for(int i=0;i<40;i++) mocks.answer(sender,id,i,i<30?0:1,0);
  var result=mocks.submit(sender,id);
  assertThat(result.score().correct()).isEqualTo(30);assertThat(result.score().incorrect()).isEqualTo(10);
  assertThat(result.score().unanswered()).isEqualTo(10);assertThat(result.score().percentage()).isEqualTo(60);
  assertThat(result.score().categories()).hasSize(2);
  assertThat(result.score().categories().stream().mapToInt(MockScoringService.CategoryScore::total).sum()).isEqualTo(50);
  assertThat(mocks.open(sender,id,49,true).review()).isTrue();
  assertThat(mocks.open(sender,id,49,true).item().selected()).isNull();
  assertThat(progress.get(sender).completed()).isEqualTo(1);
  assertThat(progress.get(sender).recent().getFirst().percentage()).isEqualTo(60);
  assertThat(grant().getMocksUsed()).isEqualTo(1);assertThat(grant().getPracticeUsed()).isZero();
 }
 @Test void twoMockLimitAndIndependentPractice() {
  content(2);
  for(int i=0;i<2;i++) {var a=start("attempt"+i);mocks.answer(sender,a.attempt().id(),0,0,0);mocks.submit(sender,a.attempt().id());}
  assertThat(grant().getMocksUsed()).isEqualTo(2);
  assertThatThrownBy(()->mocks.prepare(sender,"third")).hasMessage("mock.limit");
  var p=practice.next(sender,null,null,false);practice.answer(sender,p.delivery().id(),0);
  assertThat(grant().getPracticeUsed()).isEqualTo(1);
 }
 @Test void lifetimeBypassesMockLimit() {
  content(2);grant().setAccessLevel("LIFETIME");grant().setMockLimit(0);
  for(int i=0;i<3;i++) {var a=start("unlimited"+i);mocks.answer(sender,a.attempt().id(),0,0,0);mocks.submit(sender,a.attempt().id());}
  assertThat(grant().getMocksUsed()).isZero();assertThat(progress.get(sender).completed()).isEqualTo(3);
 }
 @Test void timerSnapshotsStartAtOpenAndExpireAtExactDeadline() {
  duration(1);content(2);var ready=mocks.prepare(sender,"timed");
  assertThat(ready.attempt().deadline()).isNull();
  var opened=mocks.open(sender,ready.attempt().id(),0,false);
  assertThat(opened.attempt().deadline()).isEqualTo(now.plusSeconds(60));
  duration(5);when(clock.instant()).thenReturn(now.plusSeconds(20));
  mocks.answer(sender,opened.attempt().id(),0,0,0);
  assertThat(mocks.open(sender,opened.attempt().id(),null,false).attempt().deadline()).isEqualTo(now.plusSeconds(60));
  when(clock.instant()).thenReturn(now.plusSeconds(60));
  var expired=mocks.answer(sender,opened.attempt().id(),1,0,0);
  assertThat(expired.attempt().status()).isEqualTo("EXPIRED");assertThat(expired.score().unanswered()).isEqualTo(1);
  assertThat(grant().getMocksUsed()).isEqualTo(1);
 }
 @Test void zeroAnswerExpiryConsumesNothingAndCannotRevealAnswerKey() {
  duration(1);content(2);var opened=start("zero");
  when(clock.instant()).thenReturn(now.plusSeconds(60));
  var expired=mocks.open(sender,opened.attempt().id(),0,true);
  assertThat(expired.attempt().status()).isEqualTo("EXPIRED");assertThat(expired.review()).isFalse();
  assertThat(expired.question()).isNull();assertThat(grant().getMocksUsed()).isZero();
  assertThat(mocks.prepare(sender,"after-expiry").attempt().id()).isNotEqualTo(opened.attempt().id());
 }
 @Test void frozenVersionsSurvivePublishedEditsArchivalAndCategoryRename() {
  content(2);var opened=start("history");long id=opened.attempt().id(),qid=opened.question().id(),version=opened.question().versionId();
  var edit=questions.form(qid);edit.setQuestionText("Changed");edit.setCorrectOption(1);questions.save(qid,edit,"test-admin");publish(qid);
  questions.transition(qid,QuestionStatus.ARCHIVED,questions.get(qid).getRevision(),"test-admin");
  catalog.save(true,category,new com.airlineprep.bot.admin.CatalogForm("numbers","Renamed","",true,0,exam),"test-admin");
  mocks.answer(sender,id,0,0,0);mocks.submit(sender,id);
  var review=mocks.open(sender,id,0,true);
  assertThat(review.question().versionId()).isEqualTo(version);assertThat(review.question().text()).startsWith("Fictional");
  assertThat(review.question().categoryName()).isEqualTo("Fictional numbers");assertThat(review.question().option(0).correct()).isTrue();
 }
 @Test void resumeFindsFirstUnansweredAndDoesNotRestartTimer() {
  duration(3);content(2);var opened=start("resume");
  mocks.answer(sender,opened.attempt().id(),0,0,0);
  var resume=mocks.open(sender,mocks.introduction(sender).active().id(),null,false);
  assertThat(resume.item().sequence()).isEqualTo(1);assertThat(resume.attempt().deadline()).isEqualTo(opened.attempt().deadline());
 }
 @Test void shortageUnregisteredAndInvalidOptionDoNotConsume() {
  content(1);assertThatThrownBy(()->mocks.prepare(sender,"short")).hasMessage("mock.empty");
  assertThat(grant().getMocksUsed()).isZero();assertThat(mocks.introduction(sender).active()).isNull();
  question("Second",true,false,true);var opened=start("valid");
  assertThatThrownBy(()->mocks.answer(sender,opened.attempt().id(),0,8,0)).hasMessage("student.invalid");
  assertThatThrownBy(()->mocks.answer(sender,opened.attempt().id(),999,0,0)).hasMessage("student.invalid");
  assertThatThrownBy(()->mocks.submit(sender,opened.attempt().id())).hasMessage("mock.answerFirst");
  assertThat(grant().getMocksUsed()).isZero();
 }
 @Test void otherUserCannotReadChangeSubmitOrReviewAttempt() {
  content(2);var opened=start("owned");long id=opened.attempt().id();
  prepareFixture(2);
  assertThatThrownBy(()->mocks.open(sender,id,0,false)).hasMessage("student.invalid");
  assertThatThrownBy(()->mocks.answer(sender,id,0,0,0)).hasMessage("student.invalid");
  assertThatThrownBy(()->mocks.submit(sender,id)).hasMessage("student.invalid");
  assertThatThrownBy(()->mocks.open(sender,id,0,true)).hasMessage("student.invalid");
 }
 @Test void durationValidationAndAdminMockSizeApplyToExistingUsersFutureMocks() {
  assertThatThrownBy(()->duration(0)).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
  assertThatThrownBy(()->duration(1441)).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
  var f=com.airlineprep.bot.settings.SettingsForm.from(settings.current());
  settings.update(new com.airlineprep.bot.settings.SettingsForm(f.freePracticeLimit(),f.freeMockLimit(),50,f.lifetimePrice(),
   f.currency(),f.paymentEnabled(),f.manualPaymentEnabled(),f.supportInfo(),null),"test-admin");
  content(50);
  assertThat(mocks.introduction(sender).questionsPerMock()).isEqualTo(50);
  var prepared=mocks.prepare(sender,"admin-updated-size");
  assertThat(prepared.attempt().count()).isEqualTo(50);
  var updated=com.airlineprep.bot.settings.SettingsForm.from(settings.current());
  settings.update(new com.airlineprep.bot.settings.SettingsForm(updated.freePracticeLimit(),updated.freeMockLimit(),25,
   updated.lifetimePrice(),updated.currency(),updated.paymentEnabled(),updated.manualPaymentEnabled(),updated.supportInfo(),updated.mockDurationMinutes()),"test-admin");
  assertThat(mocks.introduction(sender).questionsPerMock()).isEqualTo(25);
  assertThat(mocks.introduction(sender).active().count()).isEqualTo(50);
  mocks.open(sender,prepared.attempt().id(),0,false);
  mocks.answer(sender,prepared.attempt().id(),0,0,0);
  mocks.submit(sender,prepared.attempt().id());
  assertThat(mocks.prepare(sender,"admin-updated-size-again").attempt().count()).isEqualTo(25);
  // The registration snapshot remains historical; the global setting controls future attempts.
  assertThat(grant().getQuestionsPerMock()).isEqualTo(2);
 }
}
