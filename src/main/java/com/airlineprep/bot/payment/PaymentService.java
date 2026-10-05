package com.airlineprep.bot.payment;
import java.util.List;
import java.math.BigDecimal;
import com.airlineprep.bot.access.StudentAccess.Student;
public interface PaymentService {
 record View(Student student,PaymentRequest request,List<PaymentMethodService.Method> methods,boolean enabled,BigDecimal price,String currency,String support,int page,List<PaymentRequest> history) {}
 View status(long sender);
 View start(long sender,long targetExamId,String creationKey);
 View methods(long sender,long request,int page);
 View select(long sender,long request,long method);
 View reference(long sender,long request,String reference);
 View submitProof(long sender,long request,String proofText);
 View receipt(long sender,long request,ReceiptMetadata receipt);
 View cancel(long sender,long request);
}
