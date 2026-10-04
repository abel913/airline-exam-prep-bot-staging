import { PostgresDatabase,type QueryClient } from "./postgres-database.ts";
import type { PracticeStudent } from "./practice-store.ts";
function statement(sql:string):TemplateStringsArray{const p=sql.split("?") as unknown as TemplateStringsArray;Object.defineProperty(p,"raw",{value:p});return p;}
async function rows<T>(db:QueryClient,sql:string,...v:unknown[]):Promise<T[]>{return(await db.queryObject<T>(statement(sql),...v)).rows;}
async function run(db:QueryClient,sql:string,...v:unknown[]):Promise<void>{await db.queryArray(statement(sql),...v);}
const sid=(v:string|bigint|number)=>String(v);
type Req={id:string|bigint;user_id:string|bigint;status:string;amount:string|number;currency:string;method_id:string|bigint|null;
 method_type:string|null;method_name:string|null;account_name:string|null;destination:string|null;instructions:string|null;
 reference:string|null;normalized_reference:string|null;receipt_file_id:string|null;receipt_unique_id:string|null;
 receipt_type:string|null;receipt_filename:string|null;receipt_mime:string|null;receipt_size:number|null;created_at:Date|string;
 submitted_at:Date|string|null;reviewed_at:Date|string|null;reviewed_by:string|null;rejection_reason:string|null;creation_key:string};
export function requestView(r:Req|null){return r?{id:sid(r.id),userId:sid(r.user_id),status:r.status,amount:String(r.amount),currency:r.currency,
 methodId:r.method_id===null?null:sid(r.method_id),methodType:r.method_type,methodName:r.method_name,accountName:r.account_name,
 destination:r.destination,instructions:r.instructions,reference:r.reference,receipt:r.receipt_file_id===null?null:{
 fileId:r.receipt_file_id,uniqueId:r.receipt_unique_id,type:r.receipt_type,filename:r.receipt_filename,mime:r.receipt_mime,size:r.receipt_size},
 created:r.created_at,submitted:r.submitted_at,rejectionReason:r.rejection_reason}:null;}
export class PaymentUnitOfWork{
 constructor(private readonly db:QueryClient){}
 async student(tg:string):Promise<PracticeStudent|null>{
  const u=(await rows<{id:string|bigint;telegram_user_id:string|bigint;preferred_language:string|null;selected_exam_type_id:string|bigint|null;registration_status:string}>(this.db,
   "SELECT id,telegram_user_id,preferred_language,selected_exam_type_id,registration_status FROM bot_users WHERE telegram_user_id=?",tg))[0];
  if(!u||u.registration_status!=="COMPLETED"||u.selected_exam_type_id===null)return null;
  const g=(await rows<{access_level:string;practice_limit:number;practice_used:number;mock_limit:number;mocks_used:number;questions_per_mock:number}>(this.db,
   "SELECT access_level,practice_limit,practice_used,mock_limit,mocks_used,questions_per_mock FROM access_entitlements WHERE user_id=? FOR UPDATE",sid(u.id)))[0];
  if(!g)return null;
  return{id:sid(u.id),telegramId:sid(u.telegram_user_id),examId:sid(u.selected_exam_type_id),language:u.preferred_language==="am"?"am":"en",
   accessLevel:g.access_level as PracticeStudent["accessLevel"],practiceLimit:Number(g.practice_limit),practiceUsed:Number(g.practice_used),
   mockLimit:Number(g.mock_limit),mocksUsed:Number(g.mocks_used),questionsPerMock:Number(g.questions_per_mock)};
 }
 async config(){return(await rows<{payment_enabled:boolean;manual_payment_enabled:boolean;lifetime_price:string|number;currency:string;support_info:string|null}>(this.db,
  "SELECT payment_enabled,manual_payment_enabled,lifetime_price,currency,support_info FROM app_settings WHERE id=1"))[0];}
 async request(id:string,user:string,owned=true):Promise<Req|null>{return(await rows<Req>(this.db,
  "SELECT * FROM payment_requests WHERE id=?"+(owned?" AND user_id=?":"")+" FOR UPDATE",...(owned?[id,user]:[id])))[0]??null;}
 async open(user:string):Promise<Req|null>{return(await rows<Req>(this.db,"SELECT * FROM payment_requests WHERE open_user_id=? FOR UPDATE",user))[0]??null;}
 async creation(user:string,key:string):Promise<Req|null>{return(await rows<Req>(this.db,
  "SELECT * FROM payment_requests WHERE user_id=? AND creation_key=? FOR UPDATE",user,key))[0]??null;}
 async history(user:string):Promise<Req[]>{return await rows<Req>(this.db,"SELECT * FROM payment_requests WHERE user_id=? ORDER BY id DESC LIMIT 5",user);}
 async methods(page:number){return await rows<{id:string|bigint;display_name:string;type:string;account_name:string;destination:string;instructions:string}>(this.db,
  "SELECT id,display_name,type,account_name,destination,instructions FROM payment_methods WHERE active=TRUE ORDER BY display_order,id LIMIT 20 OFFSET ?",page*20);}
 async method(id:string){return(await rows<{id:string|bigint;type:string;display_name:string;account_name:string;destination:string;instructions:string;active:boolean}>(this.db,
  "SELECT id,type,display_name,account_name,destination,instructions,active FROM payment_methods WHERE id=?",id))[0]??null;}
 async create(s:PracticeStudent,key:string,cfg:{lifetime_price:string|number;currency:string}):Promise<string>{
  const r=(await rows<{id:string|bigint}>(this.db,
   "INSERT INTO payment_requests(user_id,open_user_id,creation_key,status,amount,currency,created_at) VALUES(?,?,?,'SELECT_METHOD',?,?,CURRENT_TIMESTAMP) RETURNING id",
   s.id,s.id,key,cfg.lifetime_price,cfg.currency))[0];
  if(!r)throw new Error("PAYMENT_REQUEST_CREATE_FAILED");return sid(r.id);}
 async snapshotMethod(req:string,method:string,m:{type:string;display_name:string;account_name:string;destination:string;instructions:string}){
  await run(this.db,"UPDATE payment_requests SET status='AWAITING_REFERENCE',method_id=?,method_type=?,method_name=?,account_name=?,destination=?,instructions=? WHERE id=?",
   method,m.type,m.display_name,m.account_name,m.destination,m.instructions,req);}
 async saveReference(req:string,raw:string,norm:string){await run(this.db,
  "UPDATE payment_requests SET reference=?,normalized_reference=?,status='AWAITING_RECEIPT' WHERE id=?",raw,norm,req);}
 async referenceExists(norm:string,except:string){return (await rows<{id:string|bigint}>(this.db,
  "SELECT id FROM payment_requests WHERE normalized_reference=? AND id<>? LIMIT 1",norm,except)).length>0;}
 async saveReceipt(req:string,r:{fileId:string;uniqueId:string;type:string;filename:string|null;mime:string;size:number}){
  await run(this.db,"UPDATE payment_requests SET receipt_file_id=?,receipt_unique_id=?,receipt_type=?,receipt_filename=?,receipt_mime=?,receipt_size=?,status='PENDING_REVIEW',submitted_at=CURRENT_TIMESTAMP WHERE id=?",
   r.fileId,r.uniqueId,r.type,r.filename,r.mime,r.size,req);}
 async enqueueAdmin(req:string,language:string,adminId:string|null){
  const status=adminId?"NEW":"SKIPPED";
  await run(this.db,"INSERT INTO payment_notifications(request_id,kind,recipient_id,language,status,attempts,next_attempt_at,created_at) VALUES(?,'ADMIN_PENDING',?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON CONFLICT(request_id,kind) DO NOTHING",
   req,adminId,language,status);
  if(adminId)await run(this.db,"UPDATE payment_notifications SET recipient_id=?,language=?,status='NEW',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,claim_token=NULL WHERE request_id=? AND kind='ADMIN_PENDING' AND status='SKIPPED'",
   adminId,language,req);
 }
 async cancel(req:string){await run(this.db,"UPDATE payment_requests SET status='CANCELLED',open_user_id=NULL WHERE id=?",req);}
}
export class PostgresPaymentStore{
 constructor(private readonly database:PostgresDatabase){}
 async withAction<T>(operation:(unit:PaymentUnitOfWork)=>Promise<T>):Promise<T>{
  return await this.database.transaction(async client=>await operation(new PaymentUnitOfWork(client)));
 }
}
