package com.airlineprep.bot.payment;
import java.math.BigDecimal;
import java.time.Instant;
public record PaymentRequest(long id,long userId,long examTypeId,PaymentStatus status,BigDecimal amount,String currency,Long methodId,
 String methodType,String methodName,String accountName,String destination,String instructions,String reference,
 String normalizedReference,ReceiptMetadata receipt,Instant created,Instant submitted,Instant reviewed,String reviewer,String rejectionReason) {}
