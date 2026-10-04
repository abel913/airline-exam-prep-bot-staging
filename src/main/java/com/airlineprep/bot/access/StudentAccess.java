package com.airlineprep.bot.access;
import com.airlineprep.bot.user.*;
import com.airlineprep.bot.settings.SettingsService;
import com.airlineprep.bot.category.CategoryRepository;
import com.airlineprep.bot.common.ExamException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class StudentAccess {
 private final SettingsService settings;
 private final BotUserRepository users;
 private final AccessEntitlementRepository grants;
 private final CategoryRepository categories;
 private final com.airlineprep.bot.examtype.ExamTypeRepository exams;
 public StudentAccess(SettingsService s,BotUserRepository u,AccessEntitlementRepository g,CategoryRepository c,
  com.airlineprep.bot.examtype.ExamTypeRepository e) {
  settings=s;users=u;grants=g;categories=c;exams=e;
 }
 public record Student(long id,long examId,String examName,String examNameAm,String language,AccessEntitlement grant) {
  public boolean lifetime() { return "LIFETIME".equals(grant.getAccessLevel()); }
  public int practiceRemaining() { return Math.max(0,grant.getPracticeLimit()-grant.getPracticeUsed()); }
  public int mockRemaining() { return Math.max(0,grant.getMockLimit()-grant.getMocksUsed()); }
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public Student lock(long telegramId) {
  // The existing singleton lock also serializes content publication and taxonomy changes.
  // No Telegram/network work occurs under this lock.
  settings.lock();
  BotUser user=users.findByTelegramUserId(telegramId).orElseThrow(()->new ExamException("student.register"));
  if(user.getRegistrationStatus()!=RegistrationStatus.COMPLETED) throw new ExamException("student.register");
  return lock(user,user.getSelectedExamTypeId());
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public Student lock(long telegramId,long examId) {
  settings.lock();
  BotUser user=users.findByTelegramUserId(telegramId).orElseThrow(()->new ExamException("student.register"));
  if(user.getRegistrationStatus()!=RegistrationStatus.COMPLETED) throw new ExamException("student.register");
  return lock(user,examId);
 }
 private Student lock(BotUser user,long examId) {
  var exam=exams.findById(examId).orElseThrow(()->new ExamException("registration.examUnavailable"));
  if(!exam.getActive()) throw new ExamException("registration.examUnavailable");
  var grant=grants.findByUserIdAndExamTypeId(user.getId(),examId).orElseThrow(()->new ExamException("student.register"));
  return new Student(user.getId(),examId,exam.getName(),exam.getNameAm(),user.getPreferredLanguage(),grant);
 }
 public void category(Student user,Long category) {
  if(category==null) return;
  var c=categories.findById(category).orElseThrow(()->new ExamException("student.invalid"));
  if(!c.getActive()||!c.getExamTypeId().equals(user.examId())) throw new ExamException("student.invalid");
 }
}
