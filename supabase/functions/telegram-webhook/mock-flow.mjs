import { completedMenu, message } from "./domain.mjs";
import { MockError } from "./mock-service.mjs";

function button(lang,key,data,fallback=key) {
  const text=message(lang,key);
  if(new TextEncoder().encode(data).length>64)throw new Error("CALLBACK_DATA_TOO_LONG");
  return {text:text===key?fallback:text,callback_data:data};
}
const home=lang=>[[button(lang,"student.menu","s:home","Menu")]];
const allowance=s=>s.accessLevel==="LIFETIME"?message(s.language,"student.unlimited"):
  message(s.language,"student.remaining",Math.max(0,s.mockLimit-s.mocksUsed),s.mockLimit);
const content=q=>q.text+"\n\n"+q.options.map(o=>String.fromCharCode(65+o.position)+". "+o.text).join("\n");
function introView(v){
  const s=v.student,lang=s.language;
  if(v.active)return {text:message(lang,"mock.active"),reply_markup:{inline_keyboard:[
    [button(lang,"mock.resume","m:o:"+v.active.id+":-1"),button(lang,"student.menu","s:home")]]}};
  if(s.accessLevel!=="LIFETIME"&&s.mockLimit-s.mocksUsed<=0)
    return {text:message(lang,"mock.limit"),reply_markup:{inline_keyboard:home(lang)}};
  const timer=v.duration===null?message(lang,"mock.untimed"):message(lang,"mock.minutes",v.duration);
  return {text:message(lang,"mock.intro",s.questionsPerMock,timer,allowance(s)),
    reply_markup:{inline_keyboard:[[button(lang,"mock.prepare","m:s:"+crypto.randomUUID())],...home(lang)]}};
}
function attemptView(v){
  const s=v.student,a=v.attempt,i=v.item,q=v.question,lang=s.language;
  if(a.status==="READY")return {text:message(lang,"mock.ready",a.count),reply_markup:{inline_keyboard:[
    [button(lang,"mock.open","m:o:"+a.id+":0")],...home(lang)]}};
  if(v.score){
    let text=message(lang,"mock.result",v.score.correct,v.score.total,v.score.percentage,v.score.incorrect,v.score.unanswered)+"\n"+allowance(s);
    for(const c of v.score.categories)text+="\n"+message(lang,"mock.category",c.name,c.correct,c.total,c.incorrect,c.unanswered,c.percentage);
    if(a.status==="EXPIRED")text=message(lang,"mock.expired")+"\n"+text;
    const rows=[];if(a.firstAnswer!==null)rows.push([button(lang,"mock.review","m:r:"+a.id+":0")]);
    rows.push([button(lang,"student.progress","s:progress")],...home(lang));
    return {text,reply_markup:{inline_keyboard:rows}};
  }
  let text=message(lang,"mock.position",i.sequence+1,a.count)+"\n"+content(q),rows=[];
  if(v.review){
    const correct=q.options.find(o=>o.correct),selected=i.selected===null?null:q.options.find(o=>o.position===i.selected);
    text+="\n"+message(lang,"student.selected",selected===null?message(lang,"mock.unanswered"):String.fromCharCode(65+i.selected))+
      "\n"+message(lang,"student.correctAnswer",String.fromCharCode(65+correct.position))+
      "\n"+message(lang,i.selected===null?"mock.unanswered":selected.correct?"practice.correct":"practice.incorrect")+"\n"+q.explanation;
  }else{
    if(v.secondsRemaining!==null)text+="\n"+message(lang,"mock.seconds",v.secondsRemaining);
    if(i.selected!==null)text+="\n"+message(lang,"student.selected",String.fromCharCode(65+i.selected));
    rows.push(q.options.map(o=>button(lang,String.fromCharCode(65+o.position),"m:a:"+a.id+":"+i.sequence+":"+o.position+":"+i.revision,String.fromCharCode(65+o.position))));
  }
  const prefix=v.review?"m:r:":"m:o:",nav=[];
  if(i.sequence>0)nav.push(button(lang,"student.previous",prefix+a.id+":"+(i.sequence-1)));
  if(i.sequence+1<a.count)nav.push(button(lang,"student.next",prefix+a.id+":"+(i.sequence+1)));
  if(nav.length)rows.push(nav);
  rows.push([button(lang,v.review?"mock.resultButton":"mock.submit","m:f:"+a.id)],...home(lang));
  return {text,reply_markup:{inline_keyboard:rows}};
}
function historyView(r){
  const lang=r.student.language;let text=message(lang,"history.mock")+"\nUTC\n";const rows=[];
  for(const h of r.rows){
    text+=h.date+"\n"+message(lang,"history.status."+h.status)+"\n";
    if(h.correct!==null&&h.total>0)text+=message(lang,"history.score",h.correct,h.total,h.incorrect,h.unanswered,Math.round(h.correct*10000/h.total)/100)+"\n";
    rows.push([button(lang,h.hasAnswers?"mock.resultButton":"mock.resume","m:o:"+h.id+":-1")]);
  }
  if(!r.rows.length)text+=message(lang,"history.empty");
  if(r.page>0)rows.push([button(lang,"student.previous","s:mh:"+(r.page-1))]);
  if(r.more)rows.push([button(lang,"student.next","s:mh:"+(r.page+1))]);
  rows.push(...home(lang));return {text,reply_markup:{inline_keyboard:rows}};
}
const validId=s=>/^[1-9][0-9]{0,18}$/.test(s)&&BigInt(s)<=9223372036854775807n;
async function send(telegram,chat,view){
  let text=view.text;
  while(text.length>3500){let end=3500;if(text.charCodeAt(end-1)>=0xd800&&text.charCodeAt(end-1)<=0xdbff)end--;
    await telegram.sendMessage(chat,text.slice(0,end));text=text.slice(end);}
  await telegram.sendMessage(chat,text,view.reply_markup);
}
export function createMockFlow(service,telegram){
  return {async callback(chat,tg,data,updateId){
    let lang="en";
    try{
      const intro=await service.intro(tg);lang=intro.student.language;
      if(data==="s:home"){
        const s=intro.student,menu=completedMenu({language:lang,grant:{accessLevel:s.accessLevel,
          practiceLimit:s.practiceLimit,practiceUsed:s.practiceUsed,mockLimit:s.mockLimit,mocksUsed:s.mocksUsed,
          activeMockId:intro.active?.id??null}});
        await send(telegram,chat,menu);return;
      }
      if(data==="m:intro"){await send(telegram,chat,introView(intro));return;}
      let m;
      if((m=/^s:mh:([0-9]{1,6})$/.exec(data))){
        await send(telegram,chat,historyView(await service.history(tg,Number(m[1]))));return;
      }
      if((m=/^m:s:([A-Za-z0-9-]{1,64})$/.exec(data))){
        await send(telegram,chat,attemptView(await service.prepare(tg,m[1])));return;
      }
      if((m=/^m:(o|r):([1-9][0-9]{0,18}):(-1|[0-9]{1,3})$/.exec(data))){
        if(!validId(m[2]))throw new MockError("student.invalid");
        await send(telegram,chat,attemptView(await service.open(tg,m[2],m[3]==="-1"?null:Number(m[3]),m[1]==="r")));return;
      }
      if((m=/^m:a:([1-9][0-9]{0,18}):([0-9]{1,3}):([0-7]):([0-9]{1,9})$/.exec(data))){
        if(!validId(m[1]))throw new MockError("student.invalid");
        await send(telegram,chat,attemptView(await service.answer(tg,m[1],Number(m[2]),Number(m[3]),Number(m[4]))));return;
      }
      if((m=/^m:f:([1-9][0-9]{0,18})$/.exec(data))){
        if(!validId(m[1]))throw new MockError("student.invalid");
        await send(telegram,chat,attemptView(await service.submit(tg,m[1])));return;
      }
      throw new MockError("student.invalid");
    }catch(e){
      if(!(e instanceof MockError))throw e;
      await telegram.sendMessage(chat,message(lang,e.key),{inline_keyboard:home(lang)});
    }
  }};
}
