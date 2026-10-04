import { message } from "./domain.mjs";
import { PaymentError } from "./payment-service.mjs";

function button(lang,key,data,fallback=key){
 const text=message(lang,key),b={text:text===key?fallback:text,callback_data:data};
 if(new TextEncoder().encode(data).length>64)throw new Error("CALLBACK_DATA_TOO_LONG");return b;
}
const home=lang=>[[button(lang,"student.menu","s:home","Menu")]];
function receiptFromMessage(m){
 const photo=Array.isArray(m?.photo)?m.photo:[];let file=null;
 for(const f of photo)if(!file||Number(f.width??0)*Number(f.height??0)>Number(file.width??0)*Number(file.height??0))file=f;
 if(file)return {fileId:file.file_id,uniqueId:file.file_unique_id,type:"PHOTO",filename:null,mime:"image/jpeg",size:Number(file.file_size??-1)};
 const d=m?.document;if(!d||typeof d!=="object")throw new PaymentError("payment.receiptInvalid");
 return {fileId:d.file_id,uniqueId:d.file_unique_id,type:"DOCUMENT",filename:d.file_name,mime:d.mime_type,size:Number(d.file_size??-1)};
}
function paymentView(v){
 const lang=v.student.language,p=v.request,rows=[];let text;
 if(v.student.accessLevel==="LIFETIME")text=message(lang,"payment.lifetime");
 else if(!p){
  text=message(lang,v.enabled?"payment.intro":"payment.disabled",v.price,v.currency);
 }else{
  text=message(lang,"payment.summary",p.amount,p.currency,message(lang,"payment.status."+p.status));
  if(p.methodId!==null)text+="\n"+[p.methodName,p.accountName,p.destination,p.instructions].filter(Boolean).join("\n");
  if(p.status==="SELECT_METHOD"){
   if(!v.enabled)text+="\n"+message(lang,"payment.disabled");
   else{
    for(const m of v.methods)rows.push([{text:m.display_name,callback_data:"pay:method:"+p.id+":"+m.id}]);
    if(!v.methods.length)text+="\n"+message(lang,"payment.noMethods");
    if(v.page>0)rows.push([button(lang,"student.previous","pay:page:"+p.id+":"+(v.page-1))]);
    if(v.methods.length===20)rows.push([button(lang,"student.next","pay:page:"+p.id+":"+(v.page+1))]);
   }
  }
  if(p.status==="REJECTED"&&p.rejectionReason)text+="\n"+p.rejectionReason;
  if(["SELECT_METHOD","AWAITING_REFERENCE","AWAITING_RECEIPT"].includes(p.status))rows.push([button(lang,"payment.cancel","pay:cancel:"+p.id)]);
  if(!["SELECT_METHOD","AWAITING_REFERENCE","AWAITING_RECEIPT","PENDING_REVIEW"].includes(p.status))
    rows.push([button(lang,"payment.statusButton","pay:status")]);
 }
 if(v.support)text+="\n"+v.support;
 if(!p&&v.history.length){
  text+="\n"+message(lang,"payment.history");
  for(const h of v.history)text+="\n"+h.created+" — "+h.amount+" "+h.currency+" — "+message(lang,"payment.status."+h.status)+
    (h.status==="REJECTED"&&h.rejectionReason?"\n"+h.rejectionReason:"");
 }
 rows.push(...home(lang));return {text,reply_markup:{inline_keyboard:rows}};
}
function examView(v){return {text:message(v.language,"payment.chooseExam"),reply_markup:{inline_keyboard:[
 ...v.exams.map(e=>[{text:`${e.current?"✅ ":""}${v.language==="am"&&e.nameAm?e.nameAm:e.name} — ${message(v.language,"payment.tier."+e.tier)}`,callback_data:"pay:exam:"+e.id}]),
 home(v.language)[0],
]}};}
async function send(telegram,chat,view){
 let text=view.text;while(text.length>3500){let end=3500;if(text.charCodeAt(end-1)>=0xd800&&text.charCodeAt(end-1)<=0xdbff)end--;
  await telegram.sendMessage(chat,text.slice(0,end));text=text.slice(end);}
 await telegram.sendMessage(chat,text,view.reply_markup);
}
export function createPaymentFlow(service,telegram){
 return{
  async callback(chat,tg,data,updateId){
   let lang="en";
   try{
    let v=await service.status(tg);lang=v.student.language;
    if(data==="pay:open"){await send(telegram,chat,examView(await service.exams(tg)));return;}
    if(data==="pay:status"){await send(telegram,chat,paymentView(v));return;}
    let m;
    if((m=/^pay:exam:([1-9][0-9]{0,17})$/.exec(data)))v=await service.start(tg,m[1],crypto.randomUUID().replaceAll("-",""));
    else if((m=/^pay:method:([1-9][0-9]{0,18}):([1-9][0-9]{0,18})$/.exec(data)))v=await service.select(tg,m[1],m[2]);
    else if((m=/^pay:page:([1-9][0-9]{0,18}):([0-9]{1,6})$/.exec(data)))v=await service.methods(tg,m[1],Number(m[2]));
    else if((m=/^pay:cancel:([1-9][0-9]{0,18})$/.exec(data)))v=await service.cancel(tg,m[1]);
    else throw new PaymentError("student.invalid");
    await send(telegram,chat,paymentView(v));
   }catch(e){if(!(e instanceof PaymentError))throw e;await telegram.sendMessage(chat,message(lang,e.key),{inline_keyboard:home(lang)});}
  },
  async message(chat,tg,m,updateId){
   let lang="en";
   try{
    const v=await service.status(tg);lang=v.student.language;const p=v.request;if(!p)return;
    if(m.forward_origin!==undefined||m.forward_date!==undefined||m.forward_from!==undefined||
      m.forward_from_chat!==undefined||m.is_automatic_forward===true)throw new PaymentError("payment.receiptInvalid");
    const text=typeof m.text==="string"?m.text:"";
    if(text.startsWith("/"))return;
    if(p.status==="AWAITING_REFERENCE"&&typeof m.text==="string"){
      await send(telegram,chat,paymentView(await service.reference(tg,p.id,text)));return;
    }
    if(p.status==="AWAITING_RECEIPT"){
      await send(telegram,chat,paymentView(await service.receipt(tg,p.id,receiptFromMessage(m))));return;
    }
   }catch(e){if(!(e instanceof PaymentError))throw e;if(e.key==="student.register")return;
    await telegram.sendMessage(chat,message(lang,e.key),{inline_keyboard:home(lang)});}
  }
 };
}
