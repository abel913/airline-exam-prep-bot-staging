import test from "node:test";
import assert from "node:assert/strict";
import { MockError, MockService } from "../mock-service.mjs";
import { PaymentError, PaymentService } from "../payment-service.mjs";
import { createMockFlow } from "../mock-flow.mjs";
import { createPaymentFlow } from "../payment-flow.mjs";
import { message } from "../domain.mjs";

function fixture({duration=2,questions=2,limit=2,pool=3}={}){
  const state={now:1000000,next:1,attempts:new Map(),items:new Map(),used:0,limit,duration,pool,language:"en",
    active:null,studentId:"10",foreignId:"20"};
  const question=(id,examId="1")=>({id:String(id),versionId:"v"+id,examId:String(examId),categoryId:"3",categoryName:"Synthetic",
    text:"Synthetic question "+id,explanation:"Original explanation",options:[
      {position:0,text:"Correct",correct:true},{position:1,text:"Incorrect",correct:false}]});
  const versions=new Map(Array.from({length:pool},(_,i)=>[i+1,question(i+1)]));
  const student=(id=state.studentId,examId="1")=>({id,telegramId:id,examId:String(examId),language:state.language,accessLevel:"FREE",
    practiceLimit:100,practiceUsed:0,mockLimit:limit,mocksUsed:state.used,questionsPerMock:questions});
  const unit={
    async student(tg){return tg==="tg"?student():tg==="other"?student(state.foreignId):tg==="exam-b"?student(state.studentId,"2"):null;},
    async duration(){return state.duration;},
    async own(s,id){const a=state.attempts.get(String(id));return a&&a.user_id===s.id&&a.exam_type_id===s.examId?a:null;},
    async active(s){return [...state.attempts.values()].find(a=>a.active_user_id===s.id&&a.exam_type_id===s.examId)??null;},
    async creation(s,key){return [...state.attempts.values()].find(a=>a.user_id===s.id&&a.exam_type_id===s.examId&&a.creation_key===key)??null;},
    async eligibleCount(s){return Array.from({length:state.pool},(_,i)=>String(i+1)).filter(id=>![...state.attempts.values()]
      .filter(a=>a.user_id===s.id&&a.exam_type_id===s.examId).some(a=>(state.items.get(a.id)??[]).some(item=>item.question_id===id))).length;},
    async eligible(s,count){return Array.from({length:state.pool},(_,i)=>String(i+1)).filter(id=>![...state.attempts.values()]
      .filter(a=>a.user_id===s.id&&a.exam_type_id===s.examId).some(a=>(state.items.get(a.id)??[]).some(item=>item.question_id===id)))
      .slice(0,count).map(id=>({question_id:id,version_id:"v"+id}));},
    async create(s,key,duration){const id=String(state.next++),a={id,user_id:s.id,exam_type_id:s.examId,status:"READY",
      active_user_id:s.id,creation_key:key,question_count:questions,duration_minutes:duration,cursor_position:0,
      started_at:null,deadline_at:null,first_answer_at:null,submitted_at:null,correct_count:null,incorrect_count:null,unanswered_count:null};
      state.attempts.set(id,a);state.items.set(id,[]);state.active=id;return id;},
    async freeze(a,selected){state.items.get(a).push(...selected.map((x,i)=>({sequence_number:i,version_id:x.version_id,
      selected_option:null,answer_revision:0,question_id:x.question_id})) );},
    async items(a){return state.items.get(a).map(x=>({...x}));},
    async item(a,seq){const i=state.items.get(a)?.find(x=>x.sequence_number===seq);return i?{...i}:null;},
    async frozen(v){const n=Number(String(v).slice(1));return versions.get(n)??null;},
    async expired(a){const x=state.attempts.get(a);return x.deadline_at!==null&&state.now>=x.deadline_at;},
    async secondsRemaining(a){const x=state.attempts.get(a);return x.deadline_at===null?null:Math.max(0,Math.floor((x.deadline_at-state.now)/1000));},
    async open(id){const a=state.attempts.get(id);if(a.status==="READY"){a.status="IN_PROGRESS";a.started_at=state.now;
      a.deadline_at=a.duration_minutes===null?null:state.now+a.duration_minutes*60000;}},
    async answer(s,a,seq,opt,rev){const x=state.attempts.get(a),i=state.items.get(a).find(z=>z.sequence_number===seq);
      if(!i||i.answer_revision!==rev)return false;
      if(x.first_answer_at===null){if(s.accessLevel!=="LIFETIME"){if(state.used>=state.limit)throw new Error("MOCK_LIMIT");state.used++;}
        x.first_answer_at=state.now;}
      i.selected_option=opt;i.answer_revision++;x.cursor_position=seq+1<x.question_count?seq+1:seq;return true;},
    async move(a,seq){state.attempts.get(a).cursor_position=seq;return true;},
    async answerCounts(a){const items=state.items.get(a);return{answered:items.filter(i=>i.selected_option!==null).length,unanswered:items.filter(i=>i.selected_option===null).length};},
    async score(a){const items=state.items.get(a),categories=[{id:"3",name:"Synthetic",total:items.length,
      correct:items.filter(i=>i.selected_option===0).length,incorrect:items.filter(i=>i.selected_option===1).length,
      unanswered:items.filter(i=>i.selected_option===null).length}];
      return {total:items.length,correct:categories[0].correct,incorrect:categories[0].incorrect,unanswered:categories[0].unanswered,categories};},
    async finish(a,status,score){const x=state.attempts.get(String(a.id));x.status=status;x.active_user_id=null;x.submitted_at=state.now;
      x.correct_count=score.correct;x.incorrect_count=score.incorrect;x.unanswered_count=score.unanswered;},
    async history(s){return {rows:[...state.attempts.values()].filter(a=>a.user_id===s.id).map(a=>({id:a.id,status:a.status,
      date:new Date(a.created_at??state.now).toISOString(),total:a.question_count,correct:a.correct_count,
      incorrect:a.incorrect_count,unanswered:a.unanswered_count,hasAnswers:a.first_answer_at!==null})),more:false};},
  };
  let tail=Promise.resolve();
  const store={async withAction(fn){const prior=tail;let release;tail=new Promise(r=>release=r);await prior;
    try{return await fn(unit);}finally{release();}}};
  return {state,student,service:new MockService(store)};
}
function paymentFixture({paymentEnabled=true,manualEnabled=true,methods=null,methodsError=null}={}){
 const state={requests:new Map(),next:1,refs:new Set(),notifications:[],audit:[],methodsError,methods:methods??[{id:"1",type:"BANK_TRANSFER",
  display_name:"Staging Test Transfer",account_name:"TEST ONLY",destination:"NO REAL DESTINATION",
  instructions:"Synthetic staging instructions",active:true,display_order:0}],students:new Map([
   ["tg",{id:"10",telegramId:"10",examId:"1",language:"en",accessLevel:"FREE",practiceLimit:10,practiceUsed:0,mockLimit:2,mocksUsed:0,questionsPerMock:5}],
  ["tg2",{id:"11",telegramId:"11",examId:"1",language:"en",accessLevel:"FREE",practiceLimit:10,practiceUsed:0,mockLimit:2,mocksUsed:0,questionsPerMock:5,entitlements:{"1":"FREE","2":"FREE"}}],
  ])};state.students.get("tg").entitlements={"1":"FREE","2":"FREE"};state.exams=new Set(["1","2"]);
 const unit={
  async student(tg){return state.students.get(tg)??null;},
  async studentForExam(tg,exam){const s=state.students.get(tg);if(!s||!state.exams.has(String(exam)))return null;const grants=s.entitlements??{"1":s.accessLevel};
   if(!(exam in grants))return null;return {...s,examId:exam,accessLevel:grants[exam]};},
  async config(){return{payment_enabled:paymentEnabled,manual_payment_enabled:manualEnabled,lifetime_price:"123.45",currency:"ETB",support_info:""};},
  async request(id,user,exam){const r=state.requests.get(String(id));return r&&r.user_id===user&&r.target_exam_type_id===exam?r:null;},
  async requestOwned(id,user){const r=state.requests.get(String(id));return r&&r.user_id===user?r:null;},
  async open(user){return [...state.requests.values()].find(r=>r.open_user_id===user)??null;},
  async creation(user,exam,key){return [...state.requests.values()].find(r=>r.user_id===user&&r.target_exam_type_id===exam&&r.creation_key===key)??null;},
  async history(user){return [...state.requests.values()].filter(r=>r.user_id===user);},
  async exams(tg){const s=state.students.get(tg);if(!s)throw new PaymentError("student.register");return{language:s.language,exams:Object.entries(s.entitlements).filter(([id])=>state.exams.has(id)).map(([id,tier])=>({id,name:id==="1"?"Synthetic A":"Synthetic B",nameAm:"",current:id===s.examId,tier}))};},
  async methods(page=0){if(state.methodsError)throw state.methodsError;return state.methods.filter(m=>m.active)
   .sort((a,b)=>a.display_order-b.display_order||Number(a.id)-Number(b.id)).slice(page*20,page*20+20);},
  async method(id){return state.methods.find(m=>m.id===String(id))??null;},
  async create(s,key,c){const id=String(state.next++),r={id,user_id:s.id,target_exam_type_id:s.examId,open_user_id:s.id,creation_key:key,status:"SELECT_METHOD",
   amount:c.lifetime_price,currency:c.currency,method_id:null,method_type:null,method_name:null,account_name:null,destination:null,
   instructions:null,reference:null,normalized_reference:null,payment_proof_text:null,receipt_file_id:null,receipt_unique_id:null,receipt_type:null,
   receipt_filename:null,receipt_mime:null,receipt_size:null,created_at:new Date(1000).toISOString(),submitted_at:null,rejection_reason:null};
   state.requests.set(id,r);return id;},
  async snapshotMethod(id,method,m){Object.assign(state.requests.get(String(id)),{status:"AWAITING_REFERENCE",method_id:method,
   method_type:m.type,method_name:m.display_name,account_name:m.account_name,destination:m.destination,instructions:m.instructions});},
  async referenceExists(norm,except){return [...state.requests.values()].some(r=>r.id!==except&&r.normalized_reference===norm);},
  async saveReference(id,raw,norm){Object.assign(state.requests.get(String(id)),{reference:raw,normalized_reference:norm,status:"AWAITING_RECEIPT"});state.refs.add(norm);},
  async saveProof(id,proof,reference,norm){const r=state.requests.get(String(id));Object.assign(r,{payment_proof_text:proof,
   reference:reference??r.reference,normalized_reference:norm??r.normalized_reference,status:"PENDING_REVIEW",submitted_at:new Date().toISOString()});
   if(norm)state.refs.add(norm);},
  async recordProofSubmitted(user,id){state.audit.push({user,id,action:"PAYMENT_PROOF_SUBMITTED"},{user,id,action:"PAYMENT_SUBMITTED_FOR_REVIEW"});},
  async saveReceipt(id,r){Object.assign(state.requests.get(String(id)),{...{receipt_file_id:r.fileId,receipt_unique_id:r.uniqueId,
   receipt_type:r.type,receipt_filename:r.filename,receipt_mime:r.mime,receipt_size:r.size,status:"PENDING_REVIEW",
   submitted_at:new Date().toISOString()}});},
  async enqueueAdmin(id,language,adminId){if(!state.notifications.some(n=>n.id===id))state.notifications.push({id,language,adminId});},
  async cancel(id){Object.assign(state.requests.get(String(id)),{status:"CANCELLED",open_user_id:null});},
 };
 let tail=Promise.resolve();const store={async withAction(fn){const prior=tail;let release;tail=new Promise(r=>release=r);await prior;
  try{return await fn(unit);}finally{release();}}};
 return{state,service:new PaymentService(store,{adminId:null})};
}

test("mock preparation and repeated start keep one active attempt and charge zero",async()=>{
 const f=fixture();const a=await f.service.prepare("tg","start-key"),b=await f.service.prepare("tg","other-key");
 assert.equal(a.attempt.status,"READY");assert.equal(a.attempt.id,b.attempt.id);assert.equal(f.state.used,0);
 assert.equal(f.state.items.get(a.attempt.id).length,2);
});
test("opening is free, first accepted answer charges once, revisions do not charge again",async()=>{
 const f=fixture();const ready=await f.service.prepare("tg","answer-key"),id=ready.attempt.id;
 const open=await f.service.open("tg",id,0);assert.equal(open.secondsRemaining,120);assert.equal(f.state.used,0);
 await f.service.answer("tg",id,0,0,0);assert.equal(f.state.used,1);
 await f.service.answer("tg",id,0,1,0);assert.equal(f.state.used,1);
 await f.service.answer("tg",id,0,1,1);assert.equal(f.state.used,1);
});
test("mock answer and review render the localized selected-answer label",async()=>{
 assert.equal(message("en","student.selected","A"),"Your answer: A");
 assert.equal(message("am","student.selected","A"),"የእርስዎ መልስ፦ A");
 const f=fixture({questions:1}),sent=[];
 const flow=createMockFlow(f.service,{async sendMessage(_chat,text,markup){sent.push({text,markup});}});
 await flow.callback("chat","tg","m:s:ui-label","1");
 const openData=sent.at(-1).markup.inline_keyboard.flat().find(b=>b.callback_data.startsWith("m:o:")).callback_data;
 await flow.callback("chat","tg",openData,"2");
 const answerData=sent.at(-1).markup.inline_keyboard[0][0].callback_data;
 await flow.callback("chat","tg",answerData,"3");
 assert.match(sent.at(-1).text,/You reached the end of the mock/);
 const review=sent.at(-1).markup.inline_keyboard.flat().find(b=>b.callback_data.startsWith("m:o:"));
 await flow.callback("chat","tg",review.callback_data,"4");
 assert.match(sent.at(-1).text,/Your answer: A/);
 assert.doesNotMatch(sent.at(-1).text,/student\.selected/);
});
test("resume preserves same attempt, frozen items and server deadline; results count unanswered",async()=>{
 const f=fixture({questions:3});const ready=await f.service.prepare("tg","resume-key"),id=ready.attempt.id;
 await f.service.open("tg",id,0);await f.service.answer("tg",id,0,0,0);
 const active=(await f.service.intro("tg")).active;
 const resumed=await f.service.open("tg",active.id);
 assert.equal(resumed.attempt.id,id);assert.equal(resumed.item.sequence,1);assert.equal(resumed.attempt.deadline,(await f.service.open("tg",id,0)).attempt.deadline);
 await f.service.answer("tg",id,1,1,0);const result=await f.service.submit("tg",id);
 assert.equal(result.score.correct,1);assert.equal(result.score.incorrect,1);assert.equal(result.score.unanswered,1);
 assert.equal(f.state.used,1);
});
test("zero-answer timeout does not consume allowance and late answers are rejected",async()=>{
 const f=fixture({duration:1});const ready=await f.service.prepare("tg","timeout-key"),id=ready.attempt.id;
 await f.service.open("tg",id,0);f.state.now+=60000;
 const result=await f.service.answer("tg",id,0,0,0);
 assert.equal(result.attempt.status,"EXPIRED");assert.equal(f.state.used,0);assert.equal(result.question,null);
});
test("mock answers auto-advance once, retries stay on the persisted position, and last answer opens completion",async()=>{
 const f=fixture({questions:3,pool:8}),sent=[];
 const flow=createMockFlow(f.service,{async sendMessage(_chat,text,markup){sent.push({text,markup});}});
 await flow.callback("chat","tg","m:s:auto-next","1");
 const ready=sent.at(-1).markup.inline_keyboard.flat().find(b=>b.callback_data.startsWith("m:o:")).callback_data;
 await flow.callback("chat","tg",ready,"2");
 const answerButton=()=>sent.at(-1).markup.inline_keyboard.flat().find(b=>/^m:a:/.test(b.callback_data));
 const q1=answerButton().callback_data;
 await flow.callback("chat","tg",q1,"3");
 assert.match(sent.at(-1).text,/Question 2 of 3/);
 const q2=answerButton().callback_data;
 await flow.callback("chat","tg",q2,"4");
 assert.match(sent.at(-1).text,/Question 3 of 3/);
 const sentAfterAcceptedQ2=sent.length;
 await flow.callback("chat","tg",q2,"4");
 assert.equal(sent.length,sentAfterAcceptedQ2,"a retried stale callback does not render the next question twice");
 assert.equal(f.state.items.get("1").filter(i=>i.selected_option!==null).length,2);
 assert.equal(f.state.attempts.get("1").cursor_position,2);
 const q3=answerButton().callback_data;
 await flow.callback("chat","tg",q3,"5");
 assert.match(sent.at(-1).text,/You reached the end of the mock/);
 assert.match(sent.at(-1).text,/Answered: 3/);
 assert.equal(f.state.attempts.get("1").status,"IN_PROGRESS","last answer must not auto-submit");
 assert.equal(f.state.items.get("1").filter(i=>i.selected_option!==null).length,3);
});
test("mock Previous and Next skip without creating mock items",async()=>{
 const f=fixture({questions:3,pool:8});const ready=await f.service.prepare("tg","navigation");
 await f.service.open("tg",ready.attempt.id,0);
 const next=await f.service.open("tg",ready.attempt.id,1);
 assert.equal(next.item.sequence,1);assert.equal(next.item.selected,null);
 const previous=await f.service.open("tg",ready.attempt.id,0);
 assert.equal(previous.item.sequence,0);assert.equal(f.state.items.get(ready.attempt.id).length,3);
 assert.equal(f.state.items.get(ready.attempt.id).filter(i=>i.selected_option!==null).length,0);
});
test("two-hour countdown is formatted from the persisted server deadline and survives resume and language switch",async()=>{
 const f=fixture({duration:120,questions:2,pool:6});const ready=await f.service.prepare("tg","two-hours");
 const opened=await f.service.open("tg",ready.attempt.id,0),deadline=opened.attempt.deadline;
 assert.equal(opened.secondsRemaining,7200);
 const sent=[];const flow=createMockFlow(f.service,{async sendMessage(_chat,text,markup){sent.push({text,markup});}});
 await flow.callback("chat","tg","m:o:"+ready.attempt.id+":0","timer");
 assert.match(sent.at(-1).text,/Time remaining: 02:00:00/);
 f.state.now+=3660*1000;f.state.language="am";
 const resumed=await f.service.open("tg",ready.attempt.id);
 assert.equal(resumed.attempt.deadline,deadline);assert.equal(resumed.secondsRemaining,3540);
 assert.equal(resumed.student.language,"am");assert.equal(f.state.items.get(ready.attempt.id).length,2);
});
test("expiry rejects answer, navigation, resume and submit; zero answers stay free and restart preserves history",async()=>{
 const f=fixture({duration:1,questions:1,pool:3});const ready=await f.service.prepare("tg","expiry-old"),oldId=ready.attempt.id;
 await f.service.open("tg",oldId,0);f.state.now+=60000;
 const expired=await f.service.answer("tg",oldId,0,0,0);
 assert.equal(expired.attempt.status,"EXPIRED");assert.equal(f.state.used,0);
 assert.equal((await f.service.open("tg",oldId,0)).attempt.status,"EXPIRED");
 assert.equal((await f.service.submit("tg",oldId)).attempt.status,"EXPIRED");
 const sent=[];const flow=createMockFlow(f.service,{async sendMessage(_chat,text,markup){sent.push({text,markup});}});
 await flow.callback("chat","tg","m:o:"+oldId+":0","expired-screen");
 assert.match(sent.at(-1).text,/Time is up\. Your mock exam has expired/);
 const restart=sent.at(-1).markup.inline_keyboard.flat().find(b=>b.text==="Restart Mock Exam");
 await flow.callback("chat","tg",restart.callback_data,"restart");
 const newId=sent.at(-1).markup.inline_keyboard.flat().find(b=>b.callback_data.startsWith("m:o:")).callback_data.split(":")[2];
 assert.notEqual(newId,oldId);assert.equal(f.state.attempts.get(oldId).status,"EXPIRED");
 assert.equal((await f.service.history("tg")).rows.some(row=>row.id===oldId&&row.status==="EXPIRED"),true);
 assert.equal(f.state.used,0);
});
test("expired submission and all-expired navigation remain closed after one answer; charge is not refunded",async()=>{
 const f=fixture({duration:1,questions:2,pool:5}),ready=await f.service.prepare("tg","expiry-answered"),id=ready.attempt.id;
 await f.service.open("tg",id,0);await f.service.answer("tg",id,0,0,0);assert.equal(f.state.used,1);
 f.state.now+=60000;
 for(const v of [await f.service.answer("tg",id,1,0,0),await f.service.open("tg",id,0),await f.service.open("tg",id,1),await f.service.submit("tg",id)])
  assert.equal(v.attempt.status,"EXPIRED");
 assert.equal(f.state.used,1);
});
test("frozen logical questions are excluded across attempts per user and exam, but allowed for another user",async()=>{
 const f=fixture({duration:1,questions:2,pool:6});const first=await f.service.prepare("tg","unseen-one");
 const original=f.state.items.get(first.attempt.id).map(i=>i.question_id);
 await f.service.open("tg",first.attempt.id,0);f.state.now+=60000;
 await f.service.open("tg",first.attempt.id,0);
 const second=await f.service.prepare("tg","unseen-two");
 const nextIds=f.state.items.get(second.attempt.id).map(i=>i.question_id);
 assert.equal(original.some(id=>nextIds.includes(id)),false);
 await f.service.open("tg",second.attempt.id,0);f.state.now+=60000;await f.service.open("tg",second.attempt.id,0);
 const otherUser=await f.service.prepare("other","other-user");
 assert.ok(f.state.items.get(otherUser.attempt.id).some(i=>original.includes(i.question_id)));
 await f.service.open("other",otherUser.attempt.id,0);f.state.now+=60000;await f.service.open("other",otherUser.attempt.id,0);
 const otherExam=await f.service.prepare("exam-b","other-exam");
 assert.ok(f.state.items.get(otherExam.attempt.id).some(i=>original.includes(i.question_id)));
});
test("question-pool exhaustion gives exact counts and concurrent preparation remains one attempt",async()=>{
 const short=fixture({duration:1,questions:2,pool:3}),one=await short.service.prepare("tg","pool-first");
 await short.service.open("tg",one.attempt.id,0);short.state.now+=60000;await short.service.open("tg",one.attempt.id,0);
 await assert.rejects(short.service.prepare("tg","pool-short"),e=>e instanceof MockError&&e.key==="mock.insufficient"&&e.args[0]===1&&e.args[1]===2);
 assert.equal(short.state.attempts.size,1);
 const concurrent=fixture({questions:2,pool:5});
 const pair=await Promise.all([concurrent.service.prepare("tg","parallel-a"),concurrent.service.prepare("tg","parallel-b")]);
 assert.equal(pair[0].attempt.id,pair[1].attempt.id);assert.equal(concurrent.state.attempts.size,1);
 assert.equal(new Set(concurrent.state.items.get(pair[0].attempt.id).map(i=>i.question_id)).size,2);
});
test("new mock timer, expiry, completion and pool messages have English and Amharic translations",()=>{
 for(const lang of ["en","am"])for(const key of ["mock.countdown","mock.completion","mock.insufficient","mock.timeUp","mock.restart","mock.historyButton"])
  assert.notEqual(message(lang,key,"02:00:00",2,1),key,`${lang} translation missing for ${key}`);
});
test("mock ownership, invalid frozen option, and shortage fail closed",async()=>{
 const f=fixture();const a=await f.service.prepare("tg","owned-key");
 await assert.rejects(f.service.open("other",a.attempt.id),e=>e instanceof MockError&&e.key==="student.invalid");
 await f.service.open("tg",a.attempt.id,0);
 await assert.rejects(f.service.answer("tg",a.attempt.id,0,7,0),e=>e instanceof MockError&&e.key==="student.invalid");
 const short=fixture({questions:5});await assert.rejects(short.service.prepare("tg","short-key"),e=>e instanceof MockError&&e.key==="mock.insufficient"&&e.args[0]===3&&e.args[1]===5);
 assert.equal(short.state.used,0);assert.equal(short.state.attempts.size,0);
});
test("payment references mirror Spring normalization and validation",()=>{
 assert.deepEqual(PaymentService.normalizeReference(" ab-123 "),{raw:"ab-123",normalized:"AB-123"});
 for(const value of ["", "ab", "1 starts", "/start", "x".repeat(101)])
  assert.throws(()=>PaymentService.normalizeReference(value),e=>e instanceof PaymentError&&e.key==="payment.referenceInvalid");
});
test("receipt metadata accepts only bounded Telegram image and PDF metadata",()=>{
 const service=new PaymentService({});
 assert.equal(service.validateReceipt({fileId:"file-1",uniqueId:"unique-1",type:"PHOTO",filename:null,mime:"image/jpeg",size:100}).filename,"receipt.jpg");
 assert.equal(service.validateReceipt({fileId:"doc_1",uniqueId:"unique_1",type:"DOCUMENT",filename:"proof.pdf",mime:"application/pdf",size:100}).filename,"proof.pdf");
 for(const r of [
  {fileId:"../bad",uniqueId:"u",type:"PHOTO",mime:"image/jpeg",size:1},
  {fileId:"f",uniqueId:"u",type:"DOCUMENT",filename:"evil.txt",mime:"application/pdf",size:10},
  {fileId:"f",uniqueId:"u",type:"DOCUMENT",filename:"proof.pdf",mime:"application/pdf",size:10485761},
 ])assert.throws(()=>service.validateReceipt(r),e=>e instanceof PaymentError&&e.key==="payment.receiptInvalid");
});
test("payment settings gate new requests and price is read from staging settings",async()=>{
 const off=paymentFixture({paymentEnabled:false}),v=await off.service.status("tg");
 assert.equal(v.enabled,false);assert.equal(v.price,"123.45");assert.equal(v.currency,"ETB");
 await assert.rejects(off.service.start("tg","1","disabled-key"),e=>e instanceof PaymentError&&e.key==="payment.disabled");
 assert.equal(off.state.requests.size,0);
 const on=paymentFixture();const request=await on.service.start("tg","1","payment-key");
 assert.equal(request.request.amount,"123.45");assert.equal(request.request.currency,"ETB");
});
async function paymentStartMessage(fixture){
 const sent=[],flow=createPaymentFlow(fixture.service,{async sendMessage(...args){sent.push(args);}});
 await flow.callback("10","tg","pay:open","methods-open");
 await flow.callback("10","tg","pay:exam:1","methods-start");
 return sent.at(-1);
}
test("payment start reports zero active methods only after a successful empty query",async()=>{
 const f=paymentFixture({methods:[]}),[,text,markup]=await paymentStartMessage(f);
 assert.match(text,/No active payment methods are available/);
 assert.deepEqual(markup.inline_keyboard.flat().filter(b=>b.callback_data.startsWith("pay:method:")),[]);
});
const PROOF_GUIDANCE="After payment, send either the full transaction confirmation message you received from the bank/payment provider, including the official receipt link, OR send only the transaction/reference number.";
async function selectedPaymentFlow(fixture,target="2"){
 const sent=[],flow=createPaymentFlow(fixture.service,{async sendMessage(...args){sent.push(args);}});
 await flow.callback("10","tg","pay:open","proof-open");
 await flow.callback("10","tg","pay:exam:"+target,"proof-start");
 const req=[...fixture.state.requests.values()][0];
 await flow.callback("10","tg",`pay:method:${req.id}:1`,"proof-method");
 return {flow,sent,req};
}
test("exact English proof prompt follows payment instructions and an Amharic translation exists",async()=>{
 const f=paymentFixture(),{sent}=await selectedPaymentFlow(f);
 assert.ok(sent.at(-1)[1].includes(PROOF_GUIDANCE));
 assert.ok(sent.at(-1)[1].indexOf("Synthetic staging instructions")<sent.at(-1)[1].indexOf(PROOF_GUIDANCE));
 assert.equal(message("en","payment.proofPrompt"),PROOF_GUIDANCE);
 assert.notEqual(message("am","payment.proofPrompt"),"payment.proofPrompt");
});
test("full multiline transaction proof preserves newlines and receipt URL and becomes pending immediately",async()=>{
 const f=paymentFixture(),{flow,sent,req}=await selectedPaymentFlow(f);
 const proof="Dear Customer,\nA debit transaction of ETB 50.00 was made.\nReference: BANK-ABC/123\nhttps://provider.example/receipt?id=synthetic";
 await flow.message("10","tg",{text:proof},"synthetic-update-1");
 assert.equal(req.status,"PENDING_REVIEW");assert.equal(req.payment_proof_text,proof);
 assert.equal(req.reference,null);assert.equal(req.receipt_file_id,null);assert.equal(req.receipt_unique_id,null);
 assert.equal(sent.at(-1)[1].includes(PROOF_GUIDANCE),false);
 assert.ok(sent.at(-1)[1].includes("Status: Pending manual review.\n\nWe will review your request within 24 hours. If there is any issue, we will contact you directly.\n\nYour evidence is saved and cannot be edited."));
 assert.equal(f.state.notifications.length,1);assert.equal(f.state.audit.filter(e=>e.action==="PAYMENT_SUBMITTED_FOR_REVIEW").length,1);
 assert.equal(JSON.stringify(f.state.audit).includes("provider.example"),false);
 await flow.message("10","tg",{text:proof},"synthetic-update-1");
 assert.equal(f.state.notifications.length,1);assert.equal(f.state.audit.filter(e=>e.action==="PAYMENT_SUBMITTED_FOR_REVIEW").length,1);
 assert.equal(req.target_exam_type_id,"2");assert.equal(f.state.students.get("tg").examId,"1");
});
test("reference-only proof populates legacy reference fields and becomes pending",async()=>{
 const f=paymentFixture(),{flow,req}=await selectedPaymentFlow(f,"1");
 await flow.message("10","tg",{text:"  TEST-REF-001  "},"synthetic-reference-update");
 assert.equal(req.payment_proof_text,"TEST-REF-001");assert.equal(req.reference,"TEST-REF-001");
 assert.equal(req.normalized_reference,"TEST-REF-001");assert.equal(req.status,"PENDING_REVIEW");
 assert.equal(req.receipt_file_id,null);
});
test("Amharic pending confirmation promises review within 24 hours without promising approval",async()=>{
 const f=paymentFixture();f.state.students.get("tg").language="am";
 const {flow,sent,req}=await selectedPaymentFlow(f);
 await flow.message("10","tg",{text:"TEST-REF-AM-001"},"synthetic-am-proof");
 assert.equal(req.status,"PENDING_REVIEW");
 assert.match(sent.at(-1)[1],/24 ሰዓታት ውስጥ/);
 assert.match(sent.at(-1)[1],/በቀጥታ እናሳውቅዎታለን/);
 assert.match(sent.at(-1)[1],/ማስረጃዎ ተቀምጧል/);
 assert.doesNotMatch(sent.at(-1)[1],/payment\.status\.PENDING_REVIEW/);
});
const UNSUPPORTED_FILE_EN="This file type is not supported for payment proof.\n\nPlease send either the full transaction confirmation message you received from the bank/payment provider, including the official receipt link, OR send only the transaction/reference number.";
test("unsupported attachments and captions leave payment waiting and offer cancel/menu in both languages",async()=>{
 for(const [body,label] of [
  [{photo:[{file_id:"synthetic"}]} ,"photo"],
  [{document:{file_id:"synthetic",mime_type:"application/pdf",file_name:"proof.pdf"}},"PDF"],
  [{photo:[{file_id:"synthetic"}],caption:"FT123456789"},"caption"],
  [{document:{file_id:"synthetic",mime_type:"application/pdf",file_name:"proof.pdf"},caption:"/start"},"command caption"],
  [{video:{file_id:"synthetic"}},"video"],
  [{animation:{file_id:"synthetic"}},"animation"],
  [{audio:{file_id:"synthetic"}},"audio"],
  [{voice:{file_id:"synthetic"}},"voice"],
  [{sticker:{file_id:"synthetic"}},"sticker"],
  [{video_note:{file_id:"synthetic"}},"video note"],
 ]){
  const f=paymentFixture(),{flow,sent,req}=await selectedPaymentFlow(f);
  await flow.message("10","tg",body,"synthetic-invalid-proof");
  assert.equal(req.status,"AWAITING_REFERENCE",label);assert.equal(req.payment_proof_text,null,label);
  assert.equal(req.receipt_file_id,null,label);assert.equal(f.state.requests.size,1,label);
  assert.equal(f.state.notifications.length,0,label);assert.equal(f.state.audit.length,0,label);
  assert.equal(sent.at(-1)[1],UNSUPPORTED_FILE_EN,label);
  assert.deepEqual(sent.at(-1)[2].inline_keyboard.flat().map(b=>b.callback_data),[`pay:cancel:${req.id}`,"s:home"],label);
  assert.deepEqual((await f.service.status("tg")).student.entitlements,{"1":"FREE","2":"FREE"},label);
  await flow.message("10","tg",body,"synthetic-invalid-proof");
  assert.equal(req.status,"AWAITING_REFERENCE",`${label} duplicate update`);
  assert.equal(f.state.requests.size,1,`${label} duplicate update`);
 }
 assert.equal(message("en","payment.unsupportedFile"),UNSUPPORTED_FILE_EN);
 assert.match(message("am","payment.unsupportedFile"),/ለክፍያ ማስረጃ አይቀበልም/);
 assert.match(message("am","payment.unsupportedFile"),/ኦፊሴላዊ የደረሰኝ አገናኙን/);
 assert.notEqual(message("am","payment.unsupportedFile"),"payment.unsupportedFile");
});
test("attachment is rejected in the legacy waiting state; following reference text is accepted",async()=>{
 const f=paymentFixture(),started=await f.service.start("tg","1","legacy-waiting"),id=started.request.id,sent=[];
 await f.service.select("tg",id,"1");await f.service.reference("tg",id,"LEGACY-REF-001");
 const req=f.state.requests.get(id);
 const flow=createPaymentFlow(f.service,{async sendMessage(...args){sent.push(args);}});
 await flow.message("10","tg",{document:{file_id:"synthetic",mime_type:"application/pdf",file_name:"proof.pdf"},caption:"LEGACY-REF-001"},"legacy-file");
 assert.equal(req.status,"AWAITING_RECEIPT");assert.equal(req.receipt_file_id,null);assert.equal(req.payment_proof_text,null);
 assert.equal(sent.at(-1)[1],UNSUPPORTED_FILE_EN);
 await flow.message("10","tg",{text:"FT123456789"},"legacy-text");
 assert.equal(req.status,"PENDING_REVIEW");assert.equal(req.payment_proof_text,"FT123456789");
 assert.equal(req.receipt_file_id,null);assert.equal(f.state.requests.size,1);
});
test("unsupported file followed by reference-only text submits the same request",async()=>{
 const f=paymentFixture(),{flow,sent,req}=await selectedPaymentFlow(f);
 await flow.message("10","tg",{photo:[{file_id:"synthetic"}],caption:"FT123456789"},"photo-before-reference");
 assert.equal(req.status,"AWAITING_REFERENCE");assert.equal(req.payment_proof_text,null);
 await flow.message("10","tg",{text:"FT123456789"},"reference-after-photo");
 assert.equal(req.status,"PENDING_REVIEW");assert.equal(req.payment_proof_text,"FT123456789");
 assert.equal(req.receipt_file_id,null);assert.equal(f.state.requests.size,1);
 assert.equal(f.state.notifications.length,1);assert.equal(f.state.audit.filter(e=>e.action==="PAYMENT_SUBMITTED_FOR_REVIEW").length,1);
 assert.match(sent.at(-1)[1],/Pending manual review/);
});
test("payment start displays an active TELEBIRR method",async()=>{
 const f=paymentFixture({methods:[{id:"7",type:"TELEBIRR",display_name:"Telebirr",active:true,display_order:0}]});
 const [,text,markup]=await paymentStartMessage(f),buttons=markup.inline_keyboard.flat().filter(b=>b.callback_data.startsWith("pay:method:"));
 assert.doesNotMatch(text,/No active payment methods/);assert.deepEqual(buttons.map(b=>b.text),["Telebirr"]);
});
test("payment start displays an active BANK_TRANSFER method",async()=>{
 const f=paymentFixture({methods:[{id:"8",type:"BANK_TRANSFER",display_name:"CBE",active:true,display_order:0}]});
 const [,text,markup]=await paymentStartMessage(f),buttons=markup.inline_keyboard.flat().filter(b=>b.callback_data.startsWith("pay:method:"));
 assert.doesNotMatch(text,/No active payment methods/);assert.deepEqual(buttons.map(b=>b.text),["CBE"]);
});
test("payment start lists two active method types in configured order and excludes inactive methods",async()=>{
 const f=paymentFixture({methods:[
  {id:"1",type:"BANK_TRANSFER",display_name:"CBE",active:true,display_order:1},
  {id:"2",type:"TELEBIRR",display_name:"Telebirr",active:true,display_order:0},
  {id:"3",type:"BANK_TRANSFER",display_name:"Inactive Bank",active:false,display_order:2},
 ]});
 const [,text,markup]=await paymentStartMessage(f),buttons=markup.inline_keyboard.flat().filter(b=>b.callback_data.startsWith("pay:method:"));
 assert.doesNotMatch(text,/No active payment methods/);assert.deepEqual(buttons.map(b=>b.text),["Telebirr","CBE"]);
 assert.ok(buttons.every(b=>b.callback_data.startsWith("pay:method:")));
});
test("payment-method query failures propagate and are not rendered as zero methods",async()=>{
 const failure=new Error("SYNTHETIC_PAYMENT_METHOD_QUERY_FAILURE"),f=paymentFixture({methodsError:failure});
 const sent=[],flow=createPaymentFlow(f.service,{async sendMessage(...args){sent.push(args);}});
 await flow.callback("10","tg","pay:open","query-error-open");
 await assert.rejects(flow.callback("10","tg","pay:exam:1","query-error-start"),failure);
 assert.equal(sent.some(([,text])=>/No active payment methods are available/.test(text)),false);
});
test("explicit purchase targets preserve current exam and scope retry keys by exam",async()=>{
 const f=paymentFixture();const a=await f.service.start("tg","1","same-key");
 assert.equal((await f.service.start("tg","1","same-key")).request.id,a.request.id);
 assert.equal(f.state.students.get("tg").examId,"1");assert.equal(a.request.examId,"1");
 await f.service.cancel("tg",a.request.id);
 const b=await f.service.start("tg","2","same-key");
 assert.notEqual(b.request.id,a.request.id);assert.equal(b.request.examId,"2");
 assert.equal(f.state.students.get("tg").examId,"1");
});
test("payment target validation checks target tier, activity and entitlement",async()=>{
 const f=paymentFixture(),student=f.state.students.get("tg");student.entitlements["2"]="LIFETIME";
 await assert.rejects(f.service.start("tg","2","lifetime-target"),e=>e instanceof PaymentError&&e.key==="payment.lifetime");
 student.entitlements["1"]="LIFETIME";student.entitlements["2"]="FREE";
 const free=await f.service.start("tg","2","free-target");assert.equal(free.request.examId,"2");
 await f.service.cancel("tg",free.request.id);f.state.exams.delete("2");
 await assert.rejects(f.service.start("tg","2","inactive-target"),e=>e instanceof PaymentError&&e.key==="student.invalid");
 await assert.rejects(f.service.start("tg","999","missing-target"),e=>e instanceof PaymentError&&e.key==="student.invalid");
});
test("upgrade Telegram flow displays tiers and starts only the explicitly selected target",async()=>{
 const f=paymentFixture(),sent=[];
 const flow=createPaymentFlow(f.service,{async sendMessage(...args){sent.push(args);}});
 await flow.callback("10","tg","pay:open","edge-open");
 const exams=sent.at(-1)[2].inline_keyboard.flat();
 assert.ok(exams.some(b=>b.text.includes("Synthetic A — Free")&&b.callback_data==="pay:exam:1"));
 assert.ok(exams.some(b=>b.text==="Synthetic B — Free"&&b.callback_data==="pay:exam:2"));
 await flow.callback("10","tg","pay:exam:2","edge-target");
 const req=[...f.state.requests.values()][0];
 assert.equal(req.target_exam_type_id,"2");
 assert.equal(f.state.students.get("tg").examId,"1");
 const methodButton=sent.at(-1)[2].inline_keyboard.flat().find(b=>b.callback_data.startsWith("pay:method:"));
 assert.ok(methodButton);await flow.callback("10","tg",methodButton.callback_data,"edge-select-target-method");
 assert.equal(req.target_exam_type_id,"2");assert.equal(req.status,"AWAITING_REFERENCE");
 assert.equal(f.state.students.get("tg").examId,"1");
});
test("payment method, reference, receipt and duplicate update handling preserve request identity",async()=>{
 const f=paymentFixture(),started=await f.service.start("tg","1","request-key"),id=started.request.id;
 await f.service.select("tg",id,"1");
 const ref=await f.service.reference("tg",id," Stg_Ref-001 ");
 assert.equal(ref.request.status,"AWAITING_RECEIPT");assert.equal(ref.request.reference,"Stg_Ref-001");
 const other=await f.service.start("tg2","1","other-key");await f.service.select("tg2",other.request.id,"1");
 await assert.rejects(f.service.reference("tg2",other.request.id,"stg_ref-001"),
  e=>e instanceof PaymentError&&e.key==="payment.duplicateReference");
 const receipt={fileId:"file1",uniqueId:"unique1",type:"PHOTO",filename:null,mime:"image/jpeg",size:2048};
 const first=await f.service.receipt("tg",id,receipt),again=await f.service.receipt("tg",id,receipt);
 assert.equal(first.request.status,"PENDING_REVIEW");assert.equal(again.request.id,id);
 assert.equal(f.state.requests.size,2);assert.equal(f.state.notifications.length,1);
});
