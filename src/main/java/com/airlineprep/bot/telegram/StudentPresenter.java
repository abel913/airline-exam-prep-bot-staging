package com.airlineprep.bot.telegram;
import java.util.*;
import com.airlineprep.bot.access.StudentAccess.Student;
import com.airlineprep.bot.practice.*;
import com.airlineprep.bot.mock.*;
import com.airlineprep.bot.question.*;
import org.springframework.context.MessageSource;
public class StudentPresenter {
 private final TelegramBotClient client;private final MessageSource messages;
 public StudentPresenter(TelegramBotClient c,MessageSource m) { client=c;messages=m; }
 String message(String lang,String key,Object... args) { return messages.getMessage(key,args,Locale.forLanguageTag(lang)); }
 Map<String,String> button(String lang,String key,String data) {
  if(data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>64) throw new IllegalArgumentException("Callback too long");
  return Map.of("text",message(lang,key),"callback_data",data);
 }
 void send(long chat,String text,List<List<Map<String,String>>> rows) throws InterruptedException {
  // Content may be 12,000 characters; send plain text in bounded chunks, never HTML.
  while(text.length()>3500) {
   int end=3500;if(Character.isHighSurrogate(text.charAt(end-1))) end--;
   client.sendMessage(chat,text.substring(0,end));text=text.substring(end);
  }
  client.sendMessage(chat,text,Map.of("inline_keyboard",rows));
 }
 List<List<Map<String,String>>> home(String lang) { return List.of(List.of(button(lang,"student.menu","s:home"))); }
 String allowance(Student s,boolean mock) {
  if(s.lifetime()) return message(s.language(),"student.unlimited");
  return message(s.language(),"student.remaining",mock?s.mockRemaining():s.practiceRemaining(),
   mock?s.grant().getMockLimit():s.grant().getPracticeLimit());
 }
 public void menu(long chat,MockAttemptService.Intro intro) throws InterruptedException {
  var s=intro.student();String lang=s.language();
  var rows=new ArrayList<List<Map<String,String>>>();
  rows.add(List.of(button(lang,"student.practice","p:menu"),button(lang,"student.mock","m:intro")));
  rows.add(List.of(button(lang,"student.progress","s:progress"),button(lang,"student.help","s:help")));
  rows.add(List.of(button(lang,"history.practice","s:ph:0"),button(lang,"history.mock","s:mh:0")));
  rows.add(List.of(button(lang,"insights.title","s:weak:0"),button(lang,"insights.practice","s:recommend")));
  rows.add(List.of(button(lang,s.lifetime()?"payment.activeButton":"payment.upgrade","pay:open"),button(lang,"payment.statusButton","pay:status")));
  rows.add(List.of(button(lang,"student.switchExam","s:exams")));
  if(intro.active()!=null) rows.add(List.of(button(lang,"mock.resume","m:o:"+intro.active().id()+":-1")));
  send(chat,message(lang,"student.currentExam",lang.equals("am")&&!s.examNameAm().isBlank()?s.examNameAm():s.examName())+"\n\n"+
   message(lang,"student.welcome",allowance(s,false),allowance(s,true)),rows);
 }
 public void categories(long chat,Student s,List<StudentQuestionSelector.CategoryChoice> categories) throws InterruptedException {
  categories(chat,s,categories,0);
 }
 public void history(long chat,String lang,boolean mock,StudentInsights.Page<StudentInsights.History> page) throws InterruptedException {
  StringBuilder text=new StringBuilder(message(lang,mock?"history.mock":"history.practice")).append("\nUTC\n");
  var rows=new ArrayList<List<Map<String,String>>>();
  for(var h:page.rows()) {
   text.append(h.date()).append("\n");
   if(mock) {
    text.append(message(lang,"history.status."+h.status())).append("\n");
    if(h.right()!=null) text.append(message(lang,"history.score",h.right(),h.total(),h.wrong(),h.unanswered(),Math.round(h.right()*10000.0/h.total())/100.0)).append("\n");
    text.append(h.duration()==null?message(lang,"mock.untimed"):message(lang,"mock.minutes",h.duration())).append("\n");
    rows.add(List.of(button(lang,h.right()==null?"mock.resume":"mock.resultButton","m:o:"+h.id()+":-1")));
   } else {
    text.append(h.category()).append(" — ").append(message(lang,h.correct()?"practice.correct":"practice.incorrect")).append("\n");
    rows.add(List.of(Map.of("text",message(lang,"history.explanation",h.id()),"callback_data","p:h:"+h.id())));
   }
  }
  if(page.rows().isEmpty()) text.append(message(lang,"history.empty"));
  paginate(rows,lang,mock?"s:mh:":"s:ph:",page.number(),page.more());
  send(chat,text.toString(),rows);
 }
 public void insights(long chat,String lang,StudentInsights.Page<StudentInsights.Insight> page) throws InterruptedException {
  StringBuilder text=new StringBuilder(message(lang,"insights.title")).append("\n").append(message(lang,"insights.rule")).append("\n");
  for(var c:page.rows()) text.append(c.name()).append(": ").append(message(lang,"progress.category",c.name(),c.correct(),c.answered(),c.accuracy()))
   .append("\n").append(message(lang,c.key())).append("\n");
  if(page.rows().isEmpty()) text.append(message(lang,"insights.more"));
  var rows=new ArrayList<List<Map<String,String>>>();
  rows.add(List.of(button(lang,"insights.practice","s:recommend")));
  paginate(rows,lang,"s:weak:",page.number(),page.more());send(chat,text.toString(),rows);
 }
 private void paginate(List<List<Map<String,String>>> rows,String lang,String prefix,int page,boolean more) {
  if(page>0) rows.add(List.of(button(lang,"student.previous",prefix+(page-1))));
  if(more) rows.add(List.of(button(lang,"student.next",prefix+(page+1))));
  rows.addAll(home(lang));
 }
 public void categories(long chat,Student s,List<StudentQuestionSelector.CategoryChoice> categories,int page) throws InterruptedException {
  String lang=s.language();var rows=new ArrayList<List<Map<String,String>>>();
  rows.add(List.of(button(lang,"practice.all","p:c:0")));
  for(var c:categories.stream().limit(20).toList()) rows.add(List.of(Map.of("text",lang.equals("am")&&!c.nameAm().isBlank()?c.nameAm():c.name(),"callback_data","p:c:"+c.id())));
  if(page>0) rows.add(List.of(button(lang,"student.previous","p:g:"+(page-1))));
  if(categories.size()>20) rows.add(List.of(button(lang,"student.next","p:g:"+(page+1))));
  rows.add(List.of(button(lang,"practice.review","p:r:0")));rows.addAll(home(lang));
  send(chat,message(lang,"practice.choose"),rows);
 }
 private String content(StudentQuestion q) {
  StringBuilder text=new StringBuilder(q.text()).append("\n\n");
  for(var o:q.options()) text.append((char)('A'+o.position())).append(". ").append(o.text()).append("\n");
  return text.toString();
 }
 public void practice(long chat,PracticeService.View v) throws InterruptedException {
  String lang=v.student().language();long id=v.delivery().id();
  String text=content(v.question());var rows=new ArrayList<List<Map<String,String>>>();
  if(v.answered()) {
   var correct=v.question().options().stream().filter(StudentQuestion.Option::correct).findFirst().orElseThrow();
   text+=message(lang,v.correct()?"practice.correct":"practice.incorrect")+"\n"+
    message(lang,"student.correctAnswer",String.valueOf((char)('A'+correct.position())))+"\n"+v.question().explanation()+"\n"+allowance(v.student(),false);
   rows.add(List.of(button(lang,"practice.next","p:n:"+id),button(lang,"practice.review","p:r:"+id)));
  } else {
   var choices=new ArrayList<Map<String,String>>();
   for(var o:v.question().options()) choices.add(Map.of("text",String.valueOf((char)('A'+o.position())),"callback_data","p:a:"+id+":"+o.position()));
   rows.add(choices);rows.add(List.of(button(lang,"practice.skip","p:n:"+id)));
  }
  rows.add(List.of(button(lang,"student.progress","s:progress")));rows.addAll(home(lang));send(chat,text,rows);
 }
 public void intro(long chat,MockAttemptService.Intro intro) throws InterruptedException {
  var s=intro.student();String lang=s.language();
  if(intro.active()!=null) {
   send(chat,message(lang,"mock.active"),List.of(List.of(button(lang,"mock.resume","m:o:"+intro.active().id()+":-1")),home(lang).getFirst()));return;
  }
  if(!s.lifetime()&&s.mockRemaining()==0) { error(chat,lang,"mock.limit");return; }
  String timer=intro.duration()==null?message(lang,"mock.untimed"):message(lang,"mock.minutes",intro.duration());
  send(chat,message(lang,"mock.intro",s.grant().getQuestionsPerMock(),timer,allowance(s,true)),
   List.of(List.of(button(lang,"mock.prepare","m:s:"+UUID.randomUUID().toString().replace("-",""))),home(lang).getFirst()));
 }
 public void mock(long chat,MockAttemptService.View v) throws InterruptedException {
  String lang=v.student().language();var a=v.attempt();long id=a.id();
  if(a.status().equals("READY")) {
   send(chat,message(lang,"mock.ready",a.count()),List.of(List.of(button(lang,"mock.open","m:o:"+id+":0")),home(lang).getFirst()));return;
  }
  if(v.score()!=null) {
   var score=v.score();String text=message(lang,"mock.result",score.correct(),score.total(),score.percentage(),score.incorrect(),score.unanswered())+"\n"+allowance(v.student(),true);
   for(var c:score.categories()) text+="\n"+message(lang,"mock.category",c.name(),c.correct(),c.total(),c.incorrect(),c.unanswered(),c.percentage());
   var rows=new ArrayList<List<Map<String,String>>>();
   if(a.firstAnswer()!=null) rows.add(List.of(button(lang,"mock.review","m:r:"+id+":0")));
   rows.add(List.of(button(lang,"student.progress","s:progress")));rows.addAll(home(lang));
   if(a.status().equals("EXPIRED")) text=message(lang,"mock.expired")+"\n"+text;
   send(chat,text,rows);return;
  }
  var item=v.item();String text=message(lang,"mock.position",item.sequence()+1,a.count())+"\n"+content(v.question());
  var rows=new ArrayList<List<Map<String,String>>>();
  if(v.review()) {
   var correct=v.question().options().stream().filter(StudentQuestion.Option::correct).findFirst().orElseThrow();
   text+="\n"+message(lang,"student.selected",item.selected()==null?message(lang,"mock.unanswered"):String.valueOf((char)('A'+item.selected())))+
    "\n"+message(lang,"student.correctAnswer",String.valueOf((char)('A'+correct.position())))+"\n"+
    message(lang,item.selected()==null?"mock.unanswered":v.question().option(item.selected()).correct()?"practice.correct":"practice.incorrect")+
    "\n"+v.question().explanation();
  } else {
   if(v.secondsRemaining()!=null) text+="\n"+message(lang,"mock.seconds",v.secondsRemaining());
   if(item.selected()!=null) text+="\n"+message(lang,"student.selected",String.valueOf((char)('A'+item.selected())));
   var choices=new ArrayList<Map<String,String>>();
   for(var o:v.question().options()) choices.add(Map.of("text",String.valueOf((char)('A'+o.position())),
    "callback_data","m:a:"+id+":"+item.sequence()+":"+o.position()+":"+item.revision()));
   rows.add(choices);
  }
  String prefix=v.review()?"m:r:":"m:o:";
  var navigation=new ArrayList<Map<String,String>>();
  if(item.sequence()>0) navigation.add(button(lang,"student.previous",prefix+id+":"+(item.sequence()-1)));
  if(item.sequence()+1<a.count()) navigation.add(button(lang,"student.next",prefix+id+":"+(item.sequence()+1)));
  if(!navigation.isEmpty()) rows.add(navigation);
  rows.add(List.of(button(lang,v.review()?"mock.resultButton":"mock.submit","m:f:"+id)));rows.addAll(home(lang));send(chat,text,rows);
 }
 public void progress(long chat,StudentProgressService.Progress p) throws InterruptedException {
  String lang=p.student().language();
  String text=message(lang,"progress.summary",p.answered(),p.correct(),p.incorrect(),p.percentage(),allowance(p.student(),false),p.completed(),allowance(p.student(),true));
  if(p.student().lifetime()) text+="\n"+message(lang,"payment.activeButton");
  for(var c:p.categories().stream().limit(20).toList()) text+="\n"+message(lang,"progress.category",c.name(),c.correct(),c.answered(),c.percentage());
  var rows=new ArrayList<List<Map<String,String>>>();
  rows.add(List.of(button(lang,"insights.title","s:weak:0")));
  rows.add(List.of(button(lang,"history.practice","s:ph:0"),button(lang,"history.mock","s:mh:0")));
  for(var h:p.recent()) rows.add(List.of(Map.of("text",message(lang,"progress.mock",h.id(),h.correct(),h.total(),h.percentage()),"callback_data","m:f:"+h.id())));
  rows.addAll(home(lang));send(chat,text,rows);
 }
 public void error(long chat,String lang,String key) throws InterruptedException {
  var rows=new ArrayList<>(home(lang));
  if(key.equals("practice.limit")||key.equals("practice.empty")) rows.add(0,List.of(button(lang,"practice.review","p:r:0")));
  send(chat,message(lang,key),rows);
 }
 public void help(long chat,String lang) throws InterruptedException { send(chat,message(lang,"student.helpText"),home(lang)); }
 public void examSelector(long chat,String lang,java.util.List<com.airlineprep.bot.user.RegistrationView.ExamOption> exams,boolean upgrade) throws InterruptedException {
  var rows=exams.stream().map(e->List.of(Map.of("text",(e.current()?"✅ ":"")+(lang.equals("am")&&!e.nameAm().isBlank()?e.nameAm():e.name()),
   "callback_data",(upgrade?"pay:exam:":"exam:")+e.id()))).toList();
  send(chat,message(lang,upgrade?"payment.chooseExam":"registration.switchExam"),rows);
 }
 public void purchaseExamSelector(long chat,String lang,java.util.List<com.airlineprep.bot.user.RegistrationService.PurchaseExam> exams) throws InterruptedException {
  var rows=exams.stream().map(e->List.of(Map.of("text",(e.current()?"✅ ":"")+
   (lang.equals("am")&&!e.nameAm().isBlank()?e.nameAm():e.name())+" — "+message(lang,"payment.tier."+e.tier()),"callback_data","pay:exam:"+e.id()))).toList();
  send(chat,message(lang,"payment.chooseExam"),rows);
 }
 public void help(long chat,String lang,String support) throws InterruptedException { send(chat,message(lang,"student.helpText")+"\n\n"+message(lang,"student.paymentHelp")+(support.isBlank()?"":"\n\n"+support),home(lang)); }
}
