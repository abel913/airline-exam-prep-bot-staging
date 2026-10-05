package com.airlineprep.bot.admin;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import com.airlineprep.bot.payment.*;
import com.airlineprep.bot.common.ExamException;
import com.airlineprep.bot.telegram.TelegramBotClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
@Controller
public class PaymentAdminController {
 private final PaymentQueries queries;private final PaymentReviewService review;private final PaymentAuditService audit;
 private final PaymentNotificationDispatcher notifications;private final ObjectProvider<TelegramBotClient> clients;
 public PaymentAdminController(PaymentQueries q,PaymentReviewService r,PaymentAuditService a,PaymentNotificationDispatcher n,ObjectProvider<TelegramBotClient> c) {
  queries=q;review=r;audit=a;notifications=n;clients=c;
 }
 @GetMapping("/admin/payments")
 public String list(@RequestParam(defaultValue="") String status,@RequestParam(required=false) Long method,
  @RequestParam(required=false) LocalDate date,@RequestParam(required=false) Long user,@RequestParam(defaultValue="0") int page,Model model) {
  model.addAttribute("rows",queries.list(status,method,date,user,page));model.addAttribute("page",Math.max(0,page));
  model.addAttribute("status",status);model.addAttribute("method",method);model.addAttribute("date",date);model.addAttribute("user",user);return "admin/payments";
 }
 @GetMapping("/admin/payments/{id}")
 public String detail(@PathVariable long id,Model model) {
  var p=queries.get(id);model.addAttribute("payment",p);model.addAttribute("duplicates",queries.duplicateReceipts(p));
  model.addAttribute("paymentProofLinks",PaymentProofLinks.extract(p.paymentProofText()));
  model.addAttribute("events",audit.forPayment(id));
  return "admin/payment-detail";
 }
 @PostMapping("/admin/payments/{id}/approve")
 public String approve(@PathVariable long id,Principal admin) {review.approve(id,admin.getName());return "redirect:/admin/payments/"+id;}
 @PostMapping("/admin/payments/{id}/reject")
 public String reject(@PathVariable long id,@RequestParam String reason,Principal admin) {review.reject(id,admin.getName(),reason);return "redirect:/admin/payments/"+id;}
 @PostMapping("/admin/payments/{id}/retry-notification")
 public String retry(@PathVariable long id,Principal admin) {notifications.retry(id,admin.getName());return "redirect:/admin/payments/"+id;}
 @GetMapping("/admin/payments/{id}/receipt")
 public ResponseEntity<byte[]> receipt(@PathVariable long id) {
  var receipt=queries.get(id).receipt();
  if(receipt==null) return ResponseEntity.notFound().build();
  var client=clients.getIfAvailable();if(client==null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
  try {
   byte[] bytes=client.receiptBytes(receipt.fileId());
   if(!receipt.matchesBytes(bytes)) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
   String extension=switch(receipt.mime()) {case "image/jpeg"->"jpg";case "image/png"->"png";default->"pdf";};
   return ResponseEntity.ok().contentType(MediaType.parseMediaType(receipt.mime()))
    .header("Content-Disposition","attachment; filename=\"receipt-"+id+"."+extension+"\"")
    .header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(bytes);
  } catch(InterruptedException e) {Thread.currentThread().interrupt();return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();}
  catch(TelegramBotClient.ApiException e) {return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();}
 }
 @GetMapping("/admin/payment-audit")
 public String audit(@RequestParam(required=false) String action,@RequestParam(required=false) String entity,
  @RequestParam(required=false) String actorType,@RequestParam(required=false) LocalDate date,@RequestParam(defaultValue="0") int page,Model model) {
  model.addAttribute("events",audit.list(action,entity,actorType,date,page));model.addAttribute("page",Math.max(0,page));
  model.addAttribute("action",action);model.addAttribute("entity",entity);model.addAttribute("actorType",actorType);model.addAttribute("date",date);return "admin/payment-audit";
 }
 @ExceptionHandler(ExamException.class)
 public ResponseEntity<String> invalid(ExamException e) {
  return ResponseEntity.status(e.key().equals("payment.notFound")?404:409).contentType(MediaType.TEXT_PLAIN)
   .body("Payment action unavailable. Check the current state and required fields, then reload.");
 }
}
