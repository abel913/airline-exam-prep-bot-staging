import { PostgresDatabase, type QueryClient } from "./postgres-database.ts";
import type { PracticeStudent, FrozenQuestion } from "./practice-store.ts";

function statement(sql: string): TemplateStringsArray {
  const parts = sql.split("?") as unknown as TemplateStringsArray;
  Object.defineProperty(parts, "raw", { value: parts });
  return parts;
}
async function rows<T>(db: QueryClient, sql: string, ...values: unknown[]): Promise<T[]> {
  return (await db.queryObject<T>(statement(sql), ...values)).rows;
}
async function run(db: QueryClient, sql: string, ...values: unknown[]): Promise<void> {
  await db.queryArray(statement(sql), ...values);
}
const sid = (n: string | bigint | number) => String(n);
type Attempt = { id:string|bigint; user_id:string|bigint; exam_type_id:string|bigint; status:string;
  question_count:number; duration_minutes:number|null; cursor_position:number; started_at:Date|string|null;
  deadline_at:Date|string|null; first_answer_at:Date|string|null; submitted_at:Date|string|null;
  correct_count:number|null; incorrect_count:number|null; unanswered_count:number|null };
type Item = { sequence_number:number; version_id:string|bigint; selected_option:number|null;
  answer_revision:number; question_id:string|bigint };
type Score = { total:number; correct:number; incorrect:number; unanswered:number;
  categories:Array<{id:string;name:string;total:number;correct:number;incorrect:number;unanswered:number}> };

export class MockUnitOfWork {
  constructor(private readonly db: QueryClient) {}
  async student(tg:string):Promise<PracticeStudent|null> {
    const u=(await rows<{id:string|bigint;telegram_user_id:string|bigint;preferred_language:string|null;
      selected_exam_type_id:string|bigint|null;registration_status:string}>(this.db,
      "SELECT id,telegram_user_id,preferred_language,selected_exam_type_id,registration_status FROM bot_users WHERE telegram_user_id=?",tg))[0];
    if(!u||u.registration_status!=="COMPLETED"||u.selected_exam_type_id===null)return null;
    const g=(await rows<{access_level:string;practice_limit:number;practice_used:number;mock_limit:number;mocks_used:number;questions_per_mock:number}>(
      this.db,"SELECT access_level,practice_limit,practice_used,mock_limit,mocks_used,questions_per_mock FROM access_entitlements WHERE user_id=? AND exam_type_id=? FOR UPDATE",sid(u.id),sid(u.selected_exam_type_id)))[0];
    if(!g)return null;
    return {id:sid(u.id),telegramId:sid(u.telegram_user_id),examId:sid(u.selected_exam_type_id),language:u.preferred_language==="am"?"am":"en",
      accessLevel:g.access_level as PracticeStudent["accessLevel"],practiceLimit:Number(g.practice_limit),practiceUsed:Number(g.practice_used),
      mockLimit:Number(g.mock_limit),mocksUsed:Number(g.mocks_used),questionsPerMock:Number(g.questions_per_mock)};
  }
  async duration():Promise<number|null>{return (await rows<{mock_duration_minutes:number|null}>(this.db,
    "SELECT mock_duration_minutes FROM app_settings WHERE id=1"))[0]?.mock_duration_minutes??null;}
  async expired(a:string):Promise<boolean>{return (await rows<{expired:boolean}>(this.db,
    "SELECT deadline_at IS NOT NULL AND deadline_at<=clock_timestamp() AS expired FROM mock_attempts WHERE id=?",a))[0]?.expired===true;}
  async secondsRemaining(a:string):Promise<number|null>{const r=(await rows<{seconds:number|null}>(this.db,
    "SELECT CASE WHEN deadline_at IS NULL THEN NULL ELSE GREATEST(0,FLOOR(EXTRACT(EPOCH FROM(deadline_at-clock_timestamp()))))::integer END seconds FROM mock_attempts WHERE id=?",a))[0];return r?.seconds??null;}
  async own(s:PracticeStudent,id:string):Promise<Attempt|null>{return (await rows<Attempt>(this.db,
    "SELECT * FROM mock_attempts WHERE id=? AND user_id=? AND exam_type_id=? FOR UPDATE",id,s.id,s.examId))[0]??null;}
  async active(s:PracticeStudent):Promise<Attempt|null>{return (await rows<Attempt>(this.db,
    "SELECT * FROM mock_attempts WHERE active_user_id=? AND exam_type_id=? FOR UPDATE",s.id,s.examId))[0]??null;}
  async creation(s:PracticeStudent,key:string):Promise<Attempt|null>{return (await rows<Attempt>(this.db,
    "SELECT * FROM mock_attempts WHERE user_id=? AND exam_type_id=? AND creation_key=? FOR UPDATE",s.id,s.examId,key))[0]??null;}
  async eligibleCount(s:PracticeStudent):Promise<number>{return Number((await rows<{count:number|bigint}>(this.db,
    "SELECT COUNT(*) AS count FROM questions q JOIN question_versions v ON v.id=q.current_version_id JOIN exam_types e ON e.id=v.exam_type_id JOIN categories c ON c.id=v.category_id AND c.exam_type_id=e.id WHERE q.status='PUBLISHED' AND v.mock_pool=TRUE AND (v.free_pool=TRUE OR (?=TRUE AND v.premium_pool=TRUE)) AND e.active=TRUE AND c.active=TRUE AND e.id=? AND NOT EXISTS (SELECT 1 FROM mock_items mi JOIN mock_attempts ma ON ma.id=mi.attempt_id WHERE ma.user_id=? AND ma.exam_type_id=? AND mi.question_id=q.id)",
    s.accessLevel==="LIFETIME",s.examId,s.id,s.examId))[0]?.count??0);}
  async eligible(s:PracticeStudent,count:number){return await rows<{question_id:string|bigint;version_id:string|bigint}>(this.db,
    "SELECT q.id question_id,v.id version_id FROM questions q JOIN question_versions v ON v.id=q.current_version_id JOIN exam_types e ON e.id=v.exam_type_id JOIN categories c ON c.id=v.category_id AND c.exam_type_id=e.id WHERE q.status='PUBLISHED' AND v.mock_pool=TRUE AND (v.free_pool=TRUE OR (?=TRUE AND v.premium_pool=TRUE)) AND e.active=TRUE AND c.active=TRUE AND e.id=? AND NOT EXISTS (SELECT 1 FROM mock_items mi JOIN mock_attempts ma ON ma.id=mi.attempt_id WHERE ma.user_id=? AND ma.exam_type_id=? AND mi.question_id=q.id) ORDER BY RANDOM() LIMIT ?",
    s.accessLevel==="LIFETIME",s.examId,s.id,s.examId,count);}
  async create(s:PracticeStudent,key:string,duration:number|null):Promise<string>{const x=(await rows<{id:string|bigint}>(this.db,
    "INSERT INTO mock_attempts(user_id,active_user_id,exam_type_id,creation_key,status,question_count,duration_minutes,cursor_position,created_at) VALUES(?,?,?,?,'READY',?,?,0,CURRENT_TIMESTAMP) RETURNING id",
    s.id,s.id,s.examId,key,s.questionsPerMock,duration))[0];return sid(x.id);}
  async freeze(attempt:string,selected:Array<{question_id:string|bigint;version_id:string|bigint}>){for(let i=0;i<selected.length;i++)
    await run(this.db,"INSERT INTO mock_items(attempt_id,sequence_number,question_id,version_id,answer_revision) VALUES(?,?,?,?,0)",
      attempt,i,sid(selected[i].question_id),sid(selected[i].version_id));}
  async items(attempt:string):Promise<Item[]>{return await rows<Item>(this.db,
    "SELECT sequence_number,version_id,selected_option,answer_revision,question_id FROM mock_items WHERE attempt_id=? ORDER BY sequence_number",attempt);}
  async item(attempt:string,sequence:number):Promise<Item|null>{return (await rows<Item>(this.db,
    "SELECT sequence_number,version_id,selected_option,answer_revision,question_id FROM mock_items WHERE attempt_id=? AND sequence_number=? FOR UPDATE",attempt,sequence))[0]??null;}
  async frozen(version:string|bigint):Promise<FrozenQuestion|null>{
    const v=(await rows<{id:string|bigint;question_id:string|bigint;exam_type_id:string|bigint;category_id:string|bigint;
      category_name:string;question_text:string;explanation:string}>(this.db,
      "SELECT id,question_id,exam_type_id,category_id,category_name,question_text,explanation FROM question_versions WHERE id=?",sid(version)))[0];
    if(!v)return null;
    const opts=await rows<{position:number;option_text:string;correct:boolean}>(this.db,
      "SELECT position,option_text,correct FROM question_options WHERE version_id=? ORDER BY position",sid(version));
    return {id:sid(v.question_id),versionId:sid(v.id),examId:sid(v.exam_type_id),categoryId:sid(v.category_id),
      categoryName:v.category_name,text:v.question_text,explanation:v.explanation,
      options:opts.map(o=>({position:Number(o.position),text:o.option_text,correct:o.correct}))};
  }
  async open(a:string){await run(this.db,
    "WITH t AS (SELECT clock_timestamp() AS now) UPDATE mock_attempts SET status='IN_PROGRESS',started_at=t.now,deadline_at=CASE WHEN duration_minutes IS NULL THEN NULL ELSE t.now+duration_minutes*INTERVAL '1 minute' END FROM t WHERE id=? AND status='READY'",a);}
  async answer(s:PracticeStudent,a:string,seq:number,opt:number,rev:number):Promise<boolean>{
    const item=await this.item(a,seq);if(!item||item.answer_revision!==rev)return false;
    const q=await this.frozen(item.version_id);if(!q||!q.options.some(o=>o.position===opt))throw new Error("STUDENT_INVALID");
    const state=(await rows<{first_answer_at:Date|string|null;within_deadline:boolean;status:string}>(this.db,
      "SELECT first_answer_at,(deadline_at IS NULL OR deadline_at>clock_timestamp()) AS within_deadline,status FROM mock_attempts WHERE id=? FOR UPDATE",a))[0];
    if(!state)throw new Error("STUDENT_INVALID");
    if(state.status!=="IN_PROGRESS"||!state.within_deadline)return false;
    if(state.first_answer_at===null){
      if(s.accessLevel!=="LIFETIME"){
        const g=(await rows<{mocks_used:number;mock_limit:number;access_level:string}>(this.db,
          "SELECT mocks_used,mock_limit,access_level FROM access_entitlements WHERE user_id=? AND exam_type_id=? FOR UPDATE",s.id,s.examId))[0];
        if(!g||(g.access_level!=="LIFETIME"&&Number(g.mocks_used)>=Number(g.mock_limit)))throw new Error("MOCK_LIMIT");
        if(g.access_level!=="LIFETIME")await run(this.db,"UPDATE access_entitlements SET mocks_used=mocks_used+1,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND exam_type_id=?",s.id,s.examId);
      }
      await run(this.db,"UPDATE mock_attempts SET first_answer_at=clock_timestamp() WHERE id=? AND first_answer_at IS NULL",a);
    }
    const saved=(await rows<{sequence_number:number}>(this.db,
      "UPDATE mock_items SET selected_option=?,answered_at=clock_timestamp(),answer_revision=answer_revision+1 WHERE attempt_id=? AND sequence_number=? AND answer_revision=? RETURNING sequence_number",
      opt,a,seq,rev)).length===1;
    if(saved){
      const attempt=(await rows<{question_count:number}>(this.db,"SELECT question_count FROM mock_attempts WHERE id=?",a))[0];
      await this.move(a,seq+1<Number(attempt.question_count)?seq+1:seq);
    }
    return saved;
  }
  async move(a:string,seq:number):Promise<boolean>{return (await rows<{id:string|bigint}>(this.db,
    "UPDATE mock_attempts SET cursor_position=? WHERE id=? AND status='IN_PROGRESS' AND active_user_id IS NOT NULL AND (deadline_at IS NULL OR deadline_at>clock_timestamp()) RETURNING id",seq,a)).length===1;}
  async answerCounts(a:string):Promise<{answered:number;unanswered:number}>{const r=(await rows<{answered:number|bigint;unanswered:number|bigint}>(this.db,
    "SELECT COUNT(*) FILTER(WHERE selected_option IS NOT NULL) AS answered,COUNT(*) FILTER(WHERE selected_option IS NULL) AS unanswered FROM mock_items WHERE attempt_id=?",a))[0];
    return {answered:Number(r?.answered??0),unanswered:Number(r?.unanswered??0)};}
  async score(a:string):Promise<Score>{
    const c=await rows<{category_id:string|bigint;category_name:string;total:number|bigint;correct:number|bigint;incorrect:number|bigint;unanswered:number|bigint}>(this.db,
      "SELECT v.category_id,v.category_name,COUNT(*) total,COUNT(*) FILTER(WHERE o.correct=TRUE) correct,COUNT(*) FILTER(WHERE i.selected_option IS NOT NULL AND o.correct=FALSE) incorrect,COUNT(*) FILTER(WHERE i.selected_option IS NULL) unanswered FROM mock_items i JOIN question_versions v ON v.id=i.version_id LEFT JOIN question_options o ON o.version_id=i.version_id AND o.position=i.selected_option WHERE i.attempt_id=? GROUP BY v.category_id,v.category_name ORDER BY v.category_id,v.category_name",a);
    const categories=c.map(x=>({id:sid(x.category_id),name:x.category_name,total:Number(x.total),correct:Number(x.correct),incorrect:Number(x.incorrect),unanswered:Number(x.unanswered)}));
    return categories.reduce((r,x)=>({total:r.total+x.total,correct:r.correct+x.correct,incorrect:r.incorrect+x.incorrect,
      unanswered:r.unanswered+x.unanswered,categories:[...r.categories,x]}),{total:0,correct:0,incorrect:0,unanswered:0,categories:[] as Score["categories"]});
  }
  async finish(a:Attempt,status:"SUBMITTED"|"EXPIRED",score:Score):Promise<boolean>{return (await rows<{id:string|bigint}>(this.db,
    "UPDATE mock_attempts SET status=?,active_user_id=NULL,submitted_at=clock_timestamp(),correct_count=?,incorrect_count=?,unanswered_count=? WHERE id=? AND status IN ('READY','IN_PROGRESS') AND (?='EXPIRED' OR deadline_at IS NULL OR deadline_at>clock_timestamp()) RETURNING id",
    status,score.correct,score.incorrect,score.unanswered,sid(a.id),status)).length===1;}
  async history(s:PracticeStudent,page:number){const r=await rows<{id:string|bigint;status:string;created_at:Date|string;question_count:number;
    correct_count:number|null;incorrect_count:number|null;unanswered_count:number|null;first_answer_at:Date|string|null}>(this.db,
    "SELECT id,status,created_at,question_count,correct_count,incorrect_count,unanswered_count,first_answer_at FROM mock_attempts WHERE user_id=? AND exam_type_id=? ORDER BY id DESC LIMIT 6 OFFSET ?",s.id,s.examId,page*5);
    return {rows:r.slice(0,5).map(x=>({id:sid(x.id),status:x.status,date:new Date(x.created_at).toISOString(),total:Number(x.question_count),
      correct:x.correct_count===null?null:Number(x.correct_count),incorrect:x.incorrect_count===null?null:Number(x.incorrect_count),
      unanswered:x.unanswered_count===null?null:Number(x.unanswered_count),hasAnswers:x.first_answer_at!==null})),more:r.length>5};}
}
export class PostgresMockStore {
  constructor(private readonly database:PostgresDatabase){}
  async withAction<T>(operation:(unit:MockUnitOfWork)=>Promise<T>):Promise<T>{
    return await this.database.transaction(async(client)=>await operation(new MockUnitOfWork(client)));
  }
}
