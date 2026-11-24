import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { createWorker } from "../src/worker.js";

const migration=["0001_team_testing.sql","0002_event_receipts.sql"].map(name=>readFileSync(new URL("../migrations/"+name,import.meta.url),"utf8")).join("\n");
const TEST_TIME=Date.now();
class D1 {
 constructor(){this.sqlite=new DatabaseSync(":memory:");this.sqlite.exec(migration);this.tail=Promise.resolve();}
 prepare(sql){
  const db=this.sqlite;
  const bound=(values=[])=>({
   bind(...next){return bound(next);},
   async first(){return db.prepare(sql).get(...values)??null;},
   async run(){const result=db.prepare(sql).run(...values);return {success:true,meta:{changes:Number(result.changes)}};}
  });return bound();
 }
 async batch(statements){
  const previous=this.tail;let release;this.tail=new Promise(resolve=>release=resolve);await previous;
  this.sqlite.exec("BEGIN IMMEDIATE");
  try {const result=[];for(const s of statements)result.push(await s.run());this.sqlite.exec("COMMIT");return result;}
  catch(e){this.sqlite.exec("ROLLBACK");throw e;}finally{release();}
 }
 close(){this.sqlite.close();}
}
function fixture(options={}){
 const db=new D1();let calls=0;
 const worker=createWorker({now:()=>TEST_TIME,upstreamFetch:async(...args)=>{calls++;return options.upstream?.(...args) ?? Response.json({
  model:"jev-1.14.0",answers:{intervene:{noul:0.86}},usage:{input_tokens:327}
 });}});
 const env={DB:db,ITV_TEST_TOKEN:"local-test-only",JEV_API_KEY:"not-a-real-key",...options.env};
 return {db,env,get calls(){return calls;},async request(path,data,auth=true){
  const response=await worker.fetch(new Request("https://local.test"+path,{
   method:data===undefined?"GET":"POST",headers:{"Content-Type":"application/json",...(auth?{Authorization:"Bearer local-test-only"}:{})},
   ...(data===undefined?{}:{body:typeof data==="string"?data:JSON.stringify(data)})
  }),env);return {status:response.status,body:await response.json()};
 }};
}
const context=(id="trial-1",source="LIVE")=>({protocol_version:2,id,tester_id:"T01",source,
 session_minutes:18.5,continuous_minutes:18,returns_10m:4,bedtime_passed_minutes:20,
 trigger_reason:"CONTINUOUS",observed_at:new Date(TEST_TIME).toISOString(),settings:{probabilityThreshold:0.8}});
const event=(parent,type,data={},eventId=parent+"-"+type)=>({protocol_version:2,event_id:eventId,
 intervention_id:parent,tester_id:"T01",source:"LIVE",event_type:type,occurred_at:"2026-11-05T10:00:00Z",data});

test("health and auth checks never call Jev",async()=>{
 const f=fixture();try {
  assert.equal((await f.request("/health")).status,200);
  assert.equal((await f.request("/api/jev/evaluate",context(),false)).status,401);
  assert.equal((await f.request("/api/status")).status,200);assert.equal(f.calls,0);
 }finally{f.db.close();}
});
test("successful evaluation is cached; ID conflicts cannot call upstream",async()=>{
 const f=fixture();try {
  const a=await f.request("/api/jev/evaluate",context());assert.equal(a.status,200);assert.equal(a.body.probability,0.86);
  const b=await f.request("/api/jev/evaluate",context());assert.equal(b.body.cached,true);
  assert.equal((await f.request("/api/jev/evaluate",{...context(),session_minutes:19})).status,409);
  assert.equal(f.calls,1);
 }finally{f.db.close();}
});
test("legacy three-field payload remains compatible",async()=>{
 const f=fixture();try {
  const r=await f.request("/api/jev/evaluate",{session_minutes:18,returns_10m:4,bedtime_passed_minutes:20});
  assert.equal(r.status,200);assert.equal(r.body.input_tokens,327);
 }finally{f.db.close();}
});
test("invalid and oversized input is rejected without upstream usage",async()=>{
 const f=fixture();try {
  assert.equal((await f.request("/api/jev/evaluate",{...context(),returns_10m:-1})).status,400);
  assert.equal((await f.request("/api/jev/evaluate",{...context(),tester_id:"T08"})).status,400);
  assert.equal((await f.request("/api/jev/evaluate",JSON.stringify({pad:"x".repeat(9000)}))).status,413);
  assert.equal(f.calls,0);
 }finally{f.db.close();}
});
test("unknown bedtime is explicit, never evidence fabricated as zero",async()=>{
 let sent;const f=fixture({upstream:async(_url,init)=>{sent=JSON.parse(init.body);return Response.json({
  model:"jev-1.14.0",answers:{intervene:{noul:0.2}},usage:{input_tokens:100}
 });}});
 try {
  assert.equal((await f.request("/api/jev/evaluate",{...context(),bedtime_goal_enabled:false,bedtime_passed_minutes:null})).status,200);
  assert.equal(sent.state.minutes_after_bedtime_goal,null);
  assert.equal(f.db.sqlite.prepare("SELECT bedtime_goal_enabled FROM intervention_tests").get().bedtime_goal_enabled,0);
 }finally{f.db.close();}
});
test("quota includes manual requests and reserves before upstream",async()=>{
 const f=fixture({env:{JEV_DAILY_LIMIT:"1"}});try {
  assert.equal((await f.request("/api/jev/evaluate",context("manual","MANUAL"))).status,200);
  assert.equal((await f.request("/api/jev/evaluate",context("live"))).status,429);
  assert.equal(f.calls,1);
 }finally{f.db.close();}
});
test("same in-flight request cannot start a second upstream call",async()=>{
 let finish;const waiting=new Promise(resolve=>finish=resolve);
 const f=fixture({upstream:async()=>{await waiting;return Response.json({model:"jev-1.14.0",answers:{intervene:{noul:0.9}}});}});
 try {
  const first=f.request("/api/jev/evaluate",context());
  // Wait until the reservation has committed and the fake upstream is running.
  for(let i=0;i<30 && f.calls===0;i++)await new Promise(resolve=>setImmediate(resolve));
  const second=await f.request("/api/jev/evaluate",context());assert.equal(second.status,202);
  finish();assert.equal((await first).status,200);assert.equal(f.calls,1);
 }finally{finish();f.db.close();}
});
test("uncertain upstream result is not billed again on retry",async()=>{
 const f=fixture({upstream:async()=>{throw new Error("network failed");}});try {
  assert.equal((await f.request("/api/jev/evaluate",context())).status,502);
  const retried=await f.request("/api/jev/evaluate",context());assert.equal(retried.body.status,"UNKNOWN");
  assert.equal(f.calls,1);
 }finally{f.db.close();}
});
test("zero feedback, duplicate delivery and late suppression preserve displayed truth",async()=>{
 const f=fixture();try {
  await f.request("/api/jev/evaluate",context());
  assert.equal((await f.request("/api/events",event("trial-1","feedback",{score:0,feedback_status:"SUBMITTED"}))).status,409);
  await f.request("/api/events",event("trial-1","displayed"));
  const feedback=event("trial-1","feedback",{score:0,feedback_status:"SUBMITTED"});
  assert.equal((await f.request("/api/events",feedback)).status,200);
  assert.equal((await f.request("/api/events",feedback)).body.duplicate,true);
  await f.request("/api/events",event("trial-1","suppressed",{reason:"late stale message"}));
  const row=f.db.sqlite.prepare("SELECT * FROM intervention_tests").get();
  assert.equal(row.feedback_score,0);assert.equal(row.final_intervene,1);assert.equal(row.feedback_status,"SUBMITTED");
  assert.equal(f.db.sqlite.prepare("SELECT COUNT(*) AS n FROM intervention_events").get().n,3);
 }finally{f.db.close();}
});
test("skipped feedback is null; out-of-range scores are rejected",async()=>{
 const f=fixture();try {
  await f.request("/api/jev/evaluate",context());await f.request("/api/events",event("trial-1","displayed"));
  assert.equal((await f.request("/api/events",event("trial-1","feedback",{score:11,feedback_status:"SUBMITTED"}))).status,400);
  await f.request("/api/events",event("trial-1","feedback",{score:null,feedback_status:"SKIPPED"}));
  assert.equal(f.db.sqlite.prepare("SELECT feedback_score FROM intervention_tests").get().feedback_score,null);
 }finally{f.db.close();}
});
test("concurrent distinct requests cannot exceed the shared quota",async()=>{
 const f=fixture({env:{JEV_DAILY_LIMIT:"1"}});try {
  const results=await Promise.all(Array.from({length:8},(_,i)=>f.request("/api/jev/evaluate",context("parallel-"+i))));
  assert.equal(results.filter(r=>r.status===200).length,1);
  assert.equal(results.filter(r=>r.status===429).length,7);assert.equal(f.calls,1);
  assert.equal(f.db.sqlite.prepare("SELECT used FROM relay_quotas WHERE bucket LIKE 'jev:day:%'").get().used,1);
 }finally{f.db.close();}
});
test("concurrent duplicate events consume one receipt and one event quota",async()=>{
 const f=fixture({env:{EVENT_DAILY_LIMIT:"1"}});try {
  const message=event("sample","candidate",{reason:"BELOW_THRESHOLD"});
  const results=await Promise.all(Array.from({length:8},()=>f.request("/api/events",message)));
  assert.ok(results.every(r=>r.status===200));
  assert.equal(results.filter(r=>r.body.duplicate===false).length,1);
  assert.equal(f.db.sqlite.prepare("SELECT used FROM relay_quotas WHERE bucket LIKE 'events:day:%'").get().used,1);
 }finally{f.db.close();}
});
test("stale or inconsistent context never consumes paid quota",async()=>{
 const f=fixture();try {
  assert.equal((await f.request("/api/jev/evaluate",{...context(),observed_at:new Date(TEST_TIME-300001).toISOString()})).status,400);
  assert.equal((await f.request("/api/jev/evaluate",{...context(),continuous_minutes:19})).status,400);
  assert.equal((await f.request("/api/jev/evaluate",{...context(),returns_observed_seconds:601})).status,400);
  assert.equal(f.calls,0);
 }finally{f.db.close();}
});
test("additive migrations preserve existing table rows and zero ratings",()=>{
 const db=new DatabaseSync(":memory:");
 try {
  const boundary=migration.indexOf("ALTER TABLE");
  db.exec(migration.slice(0,boundary));
  db.exec("INSERT INTO intervention_tests(id,tester_id,session_minutes,returns_10m,bedtime_passed_minutes,feedback_score) VALUES('old','T01',18,4,20,0)");
  db.exec(migration.slice(boundary));
  const row=db.prepare("SELECT * FROM intervention_tests WHERE id='old'").get();
  assert.equal(row.feedback_score,0);assert.equal(row.session_minutes,18);assert.equal(row.source,"LEGACY");
 }finally{db.close();}
});
test("kill switch blocks new Jev calls while event collection remains available",async()=>{
 const f=fixture({env:{JEV_ENABLED:"false"}});try {
  assert.equal((await f.request("/api/jev/evaluate",context())).status,503);
  assert.equal((await f.request("/api/events",event("sample","candidate",{reason:"BELOW_THRESHOLD"}))).status,200);
  assert.equal(f.calls,0);
 }finally{f.db.close();}
});
test("optional personalized and swipe metrics are forwarded when present and omitted for un-updated clients",async()=>{
 let sent=[];const f=fixture({upstream:async(_url,init)=>{sent.push(JSON.parse(init.body));return Response.json({
  model:"jev-1.14.0",answers:{intervene:{noul:0.78}},usage:{input_tokens:210}
 });}});
 try {
  assert.equal((await f.request("/api/jev/evaluate",context("plain-teammate"))).status,200);
  assert.equal(sent[0].state.session_vs_personal_avg_ratio,undefined);
  assert.equal((await f.request("/api/jev/evaluate",{
   ...context("enriched-client"),
   personal_avg_session_minutes:12.5,
   session_vs_personal_avg_ratio:1.35,
   today_shortform_minutes:45.2,
   avg_7d_daily_shortform_minutes:31.8,
   today_vs_7d_avg_ratio:1.42,
   effective_spm_1m:12.5,
   effective_session_spm:10.4,
   personal_avg_spm:7.8,
   current_vs_personal_avg_spm_ratio:1.6,
   swipe_accel_1m:3.5,
   fast_swipe_streak:4,
   swipe_overheated:true
  })).status,200);
  assert.equal(sent[1].state.personal_avg_session_minutes,12.5);
  assert.equal(sent[1].state.session_vs_personal_avg_ratio,1.35);
  assert.equal(sent[1].state.today_shortform_minutes,45.2);
  assert.equal(sent[1].state.avg_7d_daily_shortform_minutes,31.8);
  assert.equal(sent[1].state.today_vs_7d_avg_ratio,1.42);
  assert.equal(sent[1].state.effective_swipes_per_minute_1m,12.5);
  assert.equal(sent[1].state.effective_session_swipes_per_minute,10.4);
  assert.equal(sent[1].state.personal_avg_swipes_per_minute,7.8);
  assert.equal(sent[1].state.current_vs_personal_avg_spm_ratio,1.6);
  assert.equal(sent[1].state.swipe_acceleration_1m,3.5);
  assert.equal(sent[1].state.consecutive_fast_swipe_streak,4);
  assert.equal(sent[1].state.swipe_overheated,true);
 }finally{f.db.close();}
});

test("category, activity phase, and video/game metrics are forwarded in state with prompt v10",async()=>{
 let sent;const f=fixture({upstream:async(_url,init)=>{sent=JSON.parse(init.body);return Response.json({
  model:"jev-1.14.0",answers:{intervene:{noul:0.65}},usage:{input_tokens:280}
 });}});
 try {
  const r = await f.request("/api/jev/evaluate",{
   ...context("game-session"),
   app_category:"GAME",
   activity_phase:"LOBBY_OR_IDLE",
   audio_pipeline_kind:"GAME_AUDIO",
   general_app_idle_seconds:25,
   continuous_equivalent_minutes:12.0,
   raw_general_minutes:36.0,
   longform_watch_minutes:15.5,
   current_video_watch_minutes:8.0,
   completed_longform_count:1,
   youtube_watch_mode:"NORMAL"
  });
  assert.equal(r.status, 200);
  assert.equal(r.body.prompt_version, "itv-coaching-v10");
  assert.equal(sent.state.app_activity_category, "GAME");
  assert.equal(sent.state.activity_phase, "LOBBY_OR_IDLE");
  assert.equal(sent.state.active_audio_pipeline, "GAME_AUDIO");
  assert.equal(sent.state.idle_seconds_without_interaction, 25);
  assert.equal(sent.state.continuous_equivalent_minutes, 12.0);
  assert.equal(sent.state.raw_general_phone_usage_minutes, 36.0);
  assert.equal(sent.state.raw_longform_video_minutes, 15.5);
  assert.equal(sent.state.current_video_watch_minutes, 8.0);
  assert.equal(sent.state.completed_longform_video_count, 1);
  assert.equal(sent.state.youtube_watch_mode, "NORMAL");
  assert.match(sent.questions.intervene.instructions, /IMMERSION CONVERSION/);
  assert.match(sent.questions.intervene.instructions, /PROBABILITY CALIBRATION/);
  assert.match(sent.questions.intervene.instructions, /TIMING MANAGED BY CLIENT/);
  assert.match(sent.questions.intervene.instructions, /ANTI-DOUBLE-COUNTING/);
 }finally{f.db.close();}
});
