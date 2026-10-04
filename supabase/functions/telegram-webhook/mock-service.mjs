export class MockError extends Error {
  constructor(key) { super(key); this.name = "MockError"; this.key = key; }
}
const percent = (correct,total) => total===0 ? 0 : Math.round(correct*10000/total)/100;
const isActive = a => a.status==="READY"||a.status==="IN_PROGRESS";
const attemptView = a => ({
  id:String(a.id),userId:String(a.user_id),examId:String(a.exam_type_id),status:a.status,
  count:Number(a.question_count),duration:a.duration_minutes===null?null:Number(a.duration_minutes),
  cursor:Number(a.cursor_position),started:a.started_at,deadline:a.deadline_at,
  firstAnswer:a.first_answer_at,submitted:a.submitted_at,correct:a.correct_count===null?null:Number(a.correct_count),
  incorrect:a.incorrect_count===null?null:Number(a.incorrect_count),unanswered:a.unanswered_count===null?null:Number(a.unanswered_count),
});
function scoreView(raw) {
  return {...raw,percentage:percent(raw.correct,raw.total),
    categories:raw.categories.map(c=>({...c,percentage:percent(c.correct,c.total)}))};
}
function display(student,a,item,question,score,review,secondsRemaining) {
  return {student,attempt:attemptView(a),item,question,score:score?scoreView(score):null,review,secondsRemaining};
}
export class MockService {
  constructor(store) { this.store=store; }
  async withStudent(tg,operation) {
    return await this.store.withAction(async unit=>{
      const student=await unit.student(tg); if(!student)throw new MockError("student.register");
      return await operation(unit,student);
    });
  }
  async expire(unit,a) {
    if(isActive(a)&&await unit.expired(String(a.id))) {
      const score=await unit.score(String(a.id)); await unit.finish(a,"EXPIRED",score);
      return {...a,status:"EXPIRED",active_user_id:null,submitted_at:new Date().toISOString(),
        correct_count:score.correct,incorrect_count:score.incorrect,unanswered_count:score.unanswered};
    }
    return a;
  }
  async intro(tg) {
    return await this.withStudent(tg,async(unit,student)=>{
      let active=await unit.active(student);
      if(active){active=await this.expire(unit,active);if(!isActive(active))active=null;}
      return {student,active:active?attemptView(active):null,duration:await unit.duration()};
    });
  }
  async prepare(tg,key) {
    if(typeof key!=="string"||!/^[A-Za-z0-9-]{1,64}$/.test(key))throw new MockError("student.invalid");
    return await this.withStudent(tg,async(unit,s)=>{
      const prior=await unit.creation(s,key);
      if(prior)return await this.view(unit,s,await this.expire(unit,prior),null,false);
      let active=await unit.active(s);
      if(active){active=await this.expire(unit,active);return await this.view(unit,s,active,null,false);}
      if(s.accessLevel!=="LIFETIME"&&s.mockLimit-s.mocksUsed<=0)throw new MockError("mock.limit");
      const selected=await unit.eligible(s,s.questionsPerMock);
      if(selected.length!==s.questionsPerMock)throw new MockError("mock.empty");
      const id=await unit.create(s,key,await unit.duration());await unit.freeze(id,selected);
      const created=await unit.own(s,id);if(!created)throw new Error("MOCK_CREATE_FAILED");
      return await this.view(unit,s,created,null,false);
    });
  }
  /**
   * @param {string} tg
   * @param {string} attemptId
   * @param {number | null | undefined} [position]
   * @param {boolean} [review]
   */
  async open(tg,attemptId,position=null,review=false) {
    return await this.withStudent(tg,async(unit,s)=>{
      let a=await unit.own(s,attemptId);if(!a)throw new MockError("student.invalid");
      a=await this.expire(unit,a);
      if(a.status==="READY"){await unit.open(attemptId);a=await unit.own(s,attemptId);}
      if(!a)throw new MockError("student.invalid");
      return await this.view(unit,s,a,position,review);
    });
  }
  async answer(tg,attemptId,sequence,option,revision) {
    if(!Number.isInteger(sequence)||sequence<0||!Number.isInteger(option)||option<0||option>7||
      !Number.isInteger(revision)||revision<0)throw new MockError("student.invalid");
    return await this.withStudent(tg,async(unit,s)=>{
      let a=await unit.own(s,attemptId);if(!a)throw new MockError("student.invalid");
      a=await this.expire(unit,a);
      if(!isActive(a))return await this.view(unit,s,a,null,false);
      if(a.status!=="IN_PROGRESS")throw new MockError("mock.openFirst");
      const item=await unit.item(attemptId,sequence);if(!item)throw new MockError("student.invalid");
      const q=await unit.frozen(item.version_id);if(!q||!q.options.some(o=>o.position===option))throw new MockError("student.invalid");
      if(item.answer_revision===revision){
        try { await unit.answer(s,attemptId,sequence,option,revision); }
        catch(e){if(e instanceof Error&&e.message==="MOCK_LIMIT")throw new MockError("mock.limit");throw e;}
      }
      a=await unit.own(s,attemptId);if(!a)throw new MockError("student.invalid");
      return await this.view(unit,s,a,sequence,false);
    });
  }
  async submit(tg,attemptId) {
    return await this.withStudent(tg,async(unit,s)=>{
      let a=await unit.own(s,attemptId);if(!a)throw new MockError("student.invalid");
      a=await this.expire(unit,a);
      if(isActive(a)){
        if(a.first_answer_at===null)throw new MockError("mock.answerFirst");
        const score=await unit.score(attemptId);await unit.finish(a,"SUBMITTED",score);
        a={...a,status:"SUBMITTED",active_user_id:null,correct_count:score.correct,
          incorrect_count:score.incorrect,unanswered_count:score.unanswered};
      }
      return await this.view(unit,s,a,null,false);
    });
  }
  async history(tg,page=0) {
    if(!Number.isSafeInteger(page)||page<0||page>100000)throw new MockError("student.invalid");
    return await this.withStudent(tg,async(unit,student)=>({student,page,...await unit.history(student,page)}));
  }
  async view(unit,student,a,position,review) {
    if(!isActive(a)&&(!review||a.first_answer_at===null))
      return display(student,a,null,null,await unit.score(String(a.id)),false,null);
    const items=await unit.items(String(a.id));
    if(!items.length)throw new MockError("student.invalid");
    let seq=position;
    if(seq===null||seq===undefined){
      seq=isActive(a)?(items.find(x=>x.selected_option===null)?.sequence_number??Number(a.cursor_position)):0;
    }
    if(!Number.isInteger(seq)||seq<0||seq>=items.length)throw new MockError("student.invalid");
    const item=items[seq],question=await unit.frozen(item.version_id);
    if(!question)throw new MockError("student.invalid");
    if(isActive(a))await unit.move(String(a.id),seq);
    const secondsRemaining=isActive(a)?await unit.secondsRemaining(String(a.id)):null;
    return display(student,a,{sequence:Number(item.sequence_number),versionId:String(item.version_id),
      selected:item.selected_option===null?null:Number(item.selected_option),revision:Number(item.answer_revision)},question,null,
      !isActive(a)&&review,secondsRemaining);
  }
}
