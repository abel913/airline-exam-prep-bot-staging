export class PaymentError extends Error{
 constructor(key){super(key);this.name="PaymentError";this.key=key;}
}
const openStatus=new Set(["SELECT_METHOD","AWAITING_REFERENCE","AWAITING_RECEIPT","PENDING_REVIEW"]);
const cancellable=new Set(["SELECT_METHOD","AWAITING_REFERENCE","AWAITING_RECEIPT"]);
const enabled=c=>Boolean(c?.payment_enabled&&c?.manual_payment_enabled);
const viewOf=(s,p,c,methods=[],page=0,history=[])=>({student:s,request:p,enabled:enabled(c),
 price:String(c?.lifetime_price??0),currency:c?.currency??"",support:c?.support_info??"",methods,page,history});
function mapStatus(row){return row?{id:String(row.id),userId:String(row.user_id),status:row.status,amount:String(row.amount),currency:row.currency,
 methodId:row.method_id===null?null:String(row.method_id),methodType:row.method_type,methodName:row.method_name,
 accountName:row.account_name,destination:row.destination,instructions:row.instructions,reference:row.reference,
 receipt:row.receipt_file_id===null?null:{fileId:row.receipt_file_id,uniqueId:row.receipt_unique_id,type:row.receipt_type,
  filename:row.receipt_filename,mime:row.receipt_mime,size:Number(row.receipt_size)},
 created:new Date(row.created_at).toISOString(),submitted:row.submitted_at,rejectionReason:row.rejection_reason}:null;}
export class PaymentService{
 /** @param {{adminId?: string | null}} [options] */
 constructor(store,options={}){const {adminId=null}=options;this.store=store;this.adminId=/^[1-9][0-9]{0,18}$/.test(String(adminId??""))?String(adminId):null;}
 async withStudent(tg,fn){return await this.store.withAction(async unit=>{
  const student=await unit.student(tg);if(!student)throw new PaymentError("student.register");
  return await fn(unit,student);
 });}
 async status(tg){return await this.withStudent(tg,async(u,s)=>{
  const c=await u.config(),r=await u.open(s.id);
  if(r?.status==="PENDING_REVIEW")await u.enqueueAdmin(String(r.id),s.language,this.adminId);
  const methods=r?.status==="SELECT_METHOD"&&enabled(c)?await u.methods(0):[];
  const history=r?[]:await u.history(s.id);
  return viewOf(s,mapStatus(r),c,methods,0,history.map(mapStatus));
 });}
 async start(tg,key){if(typeof key!=="string"||!/^[a-z0-9-]{1,64}$/.test(key))throw new PaymentError("student.invalid");
  return await this.withStudent(tg,async(u,s)=>{
   if(s.accessLevel==="LIFETIME")throw new PaymentError("payment.lifetime");
   const c=await u.config();let p=await u.creation(s.id,key);if(p)return viewOf(s,mapStatus(p),c,[],0,[]);
   p=await u.open(s.id);if(p)return viewOf(s,mapStatus(p),c,[],0,[]);
   if(!enabled(c))throw new PaymentError("payment.disabled");
   const id=await u.create(s,key,c);p=await u.request(id,s.id);
   return viewOf(s,mapStatus(p),c,[],0,[]);
  });
 }
 async methods(tg,request,page){if(!Number.isSafeInteger(page)||page<0||page>1000000)throw new PaymentError("student.invalid");
  return await this.withStudent(tg,async(u,s)=>{
   const p=await u.request(request,s.id);if(!p)throw new PaymentError("payment.notFound");
   if(p.status!=="SELECT_METHOD")throw new PaymentError("payment.state");
   const c=await u.config();return viewOf(s,mapStatus(p),c,enabled(c)?await u.methods(page):[],page,[]);
  });
 }
 async select(tg,request,method){return await this.withStudent(tg,async(u,s)=>{
   const p=await u.request(request,s.id);if(!p)throw new PaymentError("payment.notFound");
   const c=await u.config();if(String(p.method_id??"")===String(method))return viewOf(s,mapStatus(p),c,[],0,[]);
   if(p.status!=="SELECT_METHOD")throw new PaymentError("payment.state");
   if(!enabled(c))throw new PaymentError("payment.disabled");
   const m=await u.method(method);if(!m||!m.active)throw new PaymentError("payment.methodInvalid");
   await u.snapshotMethod(request,method,m);return viewOf(s,mapStatus(await u.request(request,s.id)),c,[],0,[]);
  });
 }
 static normalizeReference(input){
  if(typeof input!=="string")throw new PaymentError("payment.referenceInvalid");
  const value=input.trim();if(!/^[A-Za-z0-9][A-Za-z0-9._/-]{2,99}$/.test(value))throw new PaymentError("payment.referenceInvalid");
  return {raw:value,normalized:value.toUpperCase()};
 }
 async reference(tg,request,input){return await this.withStudent(tg,async(u,s)=>{
   const p=await u.request(request,s.id);if(!p)throw new PaymentError("payment.notFound");
   const ref=PaymentService.normalizeReference(input),c=await u.config();
   if(ref.normalized===p.normalized_reference)return viewOf(s,mapStatus(p),c,[],0,[]);
   if(p.status!=="AWAITING_REFERENCE")throw new PaymentError("payment.state");
   if(await u.referenceExists(ref.normalized,request))throw new PaymentError("payment.duplicateReference");
   await u.saveReference(request,ref.raw,ref.normalized);
   return viewOf(s,mapStatus(await u.request(request,s.id)),c,[],0,[]);
  });
 }
 validateReceipt(r){
  const idOk=typeof r?.fileId==="string"&&/^[A-Za-z0-9_-]{1,1024}$/.test(r.fileId);
  const uniqueOk=typeof r?.uniqueId==="string"&&/^[A-Za-z0-9_-]{1,255}$/.test(r.uniqueId);
  if(!idOk||!uniqueOk||!Number.isInteger(r.size)||r.size<1||r.size>10485760)throw new PaymentError("payment.receiptInvalid");
  if(r.type==="PHOTO"){
   if(r.mime!=="image/jpeg")throw new PaymentError("payment.receiptInvalid");
   return {...r,filename:"receipt.jpg"};
  }
  if(r.type!=="DOCUMENT"||!["image/jpeg","image/png","application/pdf"].includes(r.mime)||
    typeof r.filename!=="string"||r.filename.length>200||/[\x00-\x1f\x7f/\\]/.test(r.filename))
    throw new PaymentError("payment.receiptInvalid");
  const n=r.filename.toLowerCase(),ok=r.mime==="image/jpeg"?(n.endsWith(".jpg")||n.endsWith(".jpeg")):
    r.mime==="image/png"?n.endsWith(".png"):n.endsWith(".pdf");
  if(!ok)throw new PaymentError("payment.receiptInvalid");
  return r;
 }
 async receipt(tg,request,raw){return await this.withStudent(tg,async(u,s)=>{
   const p=await u.request(request,s.id);if(!p)throw new PaymentError("payment.notFound");
   const c=await u.config(),receipt=this.validateReceipt(raw),old=p.receipt_file_id===null?null:{
    fileId:p.receipt_file_id,uniqueId:p.receipt_unique_id,type:p.receipt_type,filename:p.receipt_filename,mime:p.receipt_mime,size:Number(p.receipt_size)};
   if(old&&JSON.stringify(old)===JSON.stringify(receipt)){
    await u.enqueueAdmin(request,s.language,this.adminId);
    return viewOf(s,mapStatus(p),c,[],0,[]);
   }
   if(p.status!=="AWAITING_RECEIPT")throw new PaymentError("payment.state");
   await u.saveReceipt(request,receipt);await u.enqueueAdmin(request,s.language,this.adminId);
   return viewOf(s,mapStatus(await u.request(request,s.id)),c,[],0,[]);
  });
 }
 async cancel(tg,request){return await this.withStudent(tg,async(u,s)=>{
   const p=await u.request(request,s.id);if(!p)throw new PaymentError("payment.notFound");
   if(p.status==="CANCELLED")return viewOf(s,mapStatus(p),await u.config(),[],0,[]);
   if(!cancellable.has(p.status))throw new PaymentError("payment.state");
   await u.cancel(request);return viewOf(s,mapStatus(await u.request(request,s.id)),await u.config(),[],0,[]);
  });
 }
}
