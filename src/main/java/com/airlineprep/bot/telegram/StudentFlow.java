package com.airlineprep.bot.telegram;
import com.airlineprep.bot.practice.*;
import com.airlineprep.bot.mock.*;
import com.airlineprep.bot.common.ExamException;
public class StudentFlow {
 private final PracticeService practice;private final MockAttemptService mocks;
 private final StudentProgressService progress;private final StudentPresenter presenter;
 private final com.airlineprep.bot.settings.SettingsService settings;
 private StudentInsights insights;
 public StudentFlow withInsights(StudentInsights value) { insights=value;return this; }
 public boolean maintenance(long sender) throws InterruptedException {
  if(insights==null) return false;
  String lang=insights.maintenanceLanguage(sender);
  if(lang==null) return false;
  presenter.error(sender,lang,"maintenance.message");return true;
 }
 public StudentFlow(PracticeService p,MockAttemptService m,StudentProgressService g,StudentPresenter ui) { this(p,m,g,ui,null); }
 public StudentFlow(PracticeService p,MockAttemptService m,StudentProgressService g,StudentPresenter ui,com.airlineprep.bot.settings.SettingsService settings) { practice=p;mocks=m;progress=g;presenter=ui;this.settings=settings; }
 public void menu(long sender) throws InterruptedException { presenter.menu(sender,mocks.introduction(sender)); }
 public void callback(long sender,String data) throws InterruptedException {
  String lang="en";
  try {
   var intro=mocks.introduction(sender);lang=intro.student().language();
   if(insights!=null && data!=null && data.matches("s:(ph|mh|weak):[0-9]{1,6}|s:recommend|p:h:[0-9]{1,18}")) {
    if(data.equals("s:recommend")) {
     Long category=insights.recommendation(sender);
     if(category==null) { presenter.error(sender,lang,"insights.fallback");presenter.categories(sender,intro.student(),practice.categories(sender)); }
     else presenter.practice(sender,practice.next(sender,category,null,false));
    } else if(data.startsWith("p:h:")) {
     var view=practice.delivery(sender,Long.parseLong(data.substring(4)));
     if(!view.answered()) throw new ExamException("student.invalid");
     presenter.practice(sender,view);
    } else {
     String[] parts=data.split(":");int page=Integer.parseInt(parts[2]);
     if(parts[1].equals("weak")) presenter.insights(sender,lang,insights.insights(sender,page));
     else presenter.history(sender,lang,parts[1].equals("mh"),insights.history(sender,parts[1].equals("mh"),page));
    }
    return;
   }
   if(data==null||data.length()>64||!data.matches("s:(home|help|progress)|p:menu|m:intro|p:(g|c|n|r):[0-9]+|p:a:[0-9]+:[0-9]+|m:s:[a-z0-9-]+|m:(o|r):[0-9]+:-?[0-9]+|m:a:[0-9]+:[0-9]+:[0-9]+:[0-9]+|m:f:[0-9]+")) throw new ExamException("student.invalid");
   if(data.equals("s:home")) presenter.menu(sender,intro);
   else if(data.equals("s:help")) presenter.help(sender,lang,settings==null?"":settings.current().getSupportInfo());
   else if(data.equals("s:progress")) presenter.progress(sender,progress.get(sender));
   else if(data.equals("p:menu")) presenter.categories(sender,intro.student(),practice.categories(sender));
   else if(data.equals("m:intro")) presenter.intro(sender,intro);
   else {
    String[] p=data.split(":");if(p.length<3) return;
    if(p[0].equals("p")) {
     long id=Long.parseLong(p[2]);if(id<0) throw new NumberFormatException();
     switch(p[1]) {
      case "g" -> { if(p.length==3) { int page=Integer.parseInt(p[2]);presenter.categories(sender,intro.student(),practice.categories(sender,page),page); } }
      case "c" -> { if(p.length==3) presenter.practice(sender,practice.next(sender,id==0?null:id,null,false)); }
      case "n" -> { if(p.length==3) presenter.practice(sender,practice.next(sender,null,id,false)); }
      case "r" -> { if(p.length==3) presenter.practice(sender,practice.next(sender,null,id==0?null:id,true)); }
      case "a" -> { if(p.length==4) presenter.practice(sender,practice.answer(sender,id,Integer.parseInt(p[3]))); }
      default -> {}
     }
    } else if(p[0].equals("m")) {
     if(p[1].equals("s")&&p.length==3) { presenter.mock(sender,mocks.prepare(sender,p[2]));return; }
     long id=Long.parseLong(p[2]);if(id<=0) throw new NumberFormatException();
     switch(p[1]) {
      case "o","r" -> { if(p.length==4) { int pos=Integer.parseInt(p[3]);presenter.mock(sender,mocks.open(sender,id,pos==-1?null:pos,p[1].equals("r"))); } }
      case "a" -> { if(p.length==6) presenter.mock(sender,mocks.answer(sender,id,Integer.parseInt(p[3]),Integer.parseInt(p[4]),Integer.parseInt(p[5]))); }
      case "f" -> { if(p.length==3) presenter.mock(sender,mocks.submit(sender,id)); }
      default -> {}
     }
    }
   }
  } catch(ExamException e) { presenter.error(sender,lang,e.key()); }
  catch(NumberFormatException e) { presenter.error(sender,lang,"student.invalid"); }
 }
}
