package com.airlineprep.bot.telegram;
import java.util.*;
import com.airlineprep.bot.payment.*;
import com.airlineprep.bot.common.ExamException;
import com.fasterxml.jackson.databind.JsonNode;
public class PaymentFlow {
 private final PaymentService payments;private final StudentPresenter ui;
 private final com.airlineprep.bot.user.RegistrationService registration;
 public PaymentFlow(PaymentService p,StudentPresenter ui,com.airlineprep.bot.user.RegistrationService r) {payments=p;this.ui=ui;registration=r;}
 public void callback(long sender,String data) throws InterruptedException {
  String lang="en";
  try {
   var v=payments.status(sender);lang=v.student().language();
   if(data==null||data.length()>64||!data.matches("pay:(open|status)|pay:exam:[1-9][0-9]{0,17}|pay:(method|page):[0-9]+:[0-9]+|pay:cancel:[0-9]+")) throw new ExamException("student.invalid");
   if(data.equals("pay:open")) {ui.purchaseExamSelector(sender,v.student().language(),registration.purchaseExams(sender));return;}
   if(data.equals("pay:status")) {show(sender,v);return;}
   if(data.startsWith("pay:exam:")) {
    long exam=Long.parseLong(data.substring(9));
    show(sender,payments.start(sender,exam,UUID.randomUUID().toString().replace("-","")));return;
   }
   String[] parts=data.split(":");if(parts.length<3) return;
   long id=Long.parseLong(parts[2]);
   switch(parts[1]) {
    case "method" -> {if(parts.length==4) show(sender,payments.select(sender,id,Long.parseLong(parts[3])));}
    case "page" -> {if(parts.length==4) show(sender,payments.methods(sender,id,Integer.parseInt(parts[3])));}
    case "cancel" -> {if(parts.length==3) show(sender,payments.cancel(sender,id));}
    default -> {}
   }
  } catch(ExamException e) {ui.error(sender,lang,e.key());}
  catch(NumberFormatException e) {ui.error(sender,lang,"student.invalid");}
 }
 public void message(long sender,JsonNode message) throws InterruptedException {
  String lang="en";
  try {
   var v=payments.status(sender);lang=v.student().language();var p=v.request();
   if(p==null) return;
   String text=message.path("text").asText("");
   if(text.startsWith("/")) return;
   if(p.status()==PaymentStatus.AWAITING_REFERENCE&&message.path("text").isTextual()) {
    show(sender,payments.submitProof(sender,p.id(),text));return;
   }
   if(p.status()==PaymentStatus.AWAITING_REFERENCE) {
    ui.error(sender,lang,"payment.proofPrompt");return;
   }
   if(p.status()==PaymentStatus.AWAITING_RECEIPT) {
    if(message.path("text").isTextual()) {show(sender,payments.submitProof(sender,p.id(),text));return;}
    if(message.has("forward_origin")||message.has("forward_date")||message.has("forward_from")||message.has("forward_from_chat")||message.path("is_automatic_forward").asBoolean())
     throw new ExamException("payment.receiptInvalid");
    ReceiptMetadata receipt=parseReceipt(message);
    show(sender,payments.receipt(sender,p.id(),receipt));
   }
  } catch(ExamException e) {ui.error(sender,lang,e.key());}
 }
 public static ReceiptMetadata parseReceipt(JsonNode message) {
  var photo=message.path("photo");JsonNode file=null;
  if(photo.isArray()&&!photo.isEmpty()) {
   for(var candidate:photo) if(file==null||candidate.path("width").asLong()*candidate.path("height").asLong()>file.path("width").asLong()*file.path("height").asLong()) file=candidate;
   return new ReceiptMetadata(file.path("file_id").asText(null),file.path("file_unique_id").asText(null),"PHOTO",null,"image/jpeg",file.path("file_size").asLong(-1));
  }
  file=message.path("document");
  if(!file.isObject()) throw new ExamException("payment.receiptInvalid");
  return new ReceiptMetadata(file.path("file_id").asText(null),file.path("file_unique_id").asText(null),"DOCUMENT",
   file.path("file_name").asText(null),file.path("mime_type").asText(null),file.path("file_size").asLong(-1));
 }
 private void show(long chat,PaymentService.View v) throws InterruptedException {
  String lang=v.student().language();var rows=new ArrayList<List<Map<String,String>>>();String text;
  var p=v.request();
  if(v.student().lifetime()) text=ui.message(lang,"payment.lifetime");
  else if(p==null) {
   text=ui.message(lang,v.enabled()?"payment.intro":"payment.disabled",v.price().toPlainString(),v.currency());
  } else {
   text=ui.message(lang,"payment.summary",p.amount().toPlainString(),p.currency(),ui.message(lang,"payment.status."+p.status()));
   if(p.methodId()!=null) text+="\n"+p.methodName()+"\n"+p.accountName()+"\n"+p.destination()+"\n"+p.instructions();
   if(p.status()==PaymentStatus.AWAITING_REFERENCE||p.status()==PaymentStatus.AWAITING_RECEIPT)
    text+="\n"+ui.message(lang,"payment.proofPrompt");
   if(p.status()==PaymentStatus.SELECT_METHOD) {
    if(!v.enabled()) text+="\n"+ui.message(lang,"payment.disabled");
    else {
     for(var m:v.methods()) rows.add(List.of(Map.of("text",m.details().displayName(),"callback_data","pay:method:"+p.id()+":"+m.id())));
     if(v.methods().isEmpty()) text+="\n"+ui.message(lang,"payment.noMethods");
     if(v.page()>0) rows.add(List.of(ui.button(lang,"student.previous","pay:page:"+p.id()+":"+(v.page()-1))));
     if(v.methods().size()==20) rows.add(List.of(ui.button(lang,"student.next","pay:page:"+p.id()+":"+(v.page()+1))));
    }
   }
   if(p.status()==PaymentStatus.REJECTED) text+="\n"+p.rejectionReason();
   if(p.status().cancellable()) rows.add(List.of(ui.button(lang,"payment.cancel","pay:cancel:"+p.id())));
   if(!p.status().open()) rows.add(List.of(ui.button(lang,"payment.statusButton","pay:status")));
  }
  if(!v.support().isBlank()) text+="\n"+v.support();
  if(p==null&&!v.history().isEmpty()) {
   text+="\n"+ui.message(lang,"payment.history");
   for(var h:v.history()) text+="\n"+h.created()+" — "+h.amount().toPlainString()+" "+h.currency()+" — "+ui.message(lang,"payment.status."+h.status())+
    (h.status()==PaymentStatus.REJECTED?"\n"+h.rejectionReason():"");
  }
  rows.addAll(ui.home(lang));ui.send(chat,text,rows);
 }
}
