import { HttpError, reply, readJson, evaluation, identity, id, timestamp, hash } from "./protocol.js";
import { existing, reserve, finish, resultFor, recordEvent } from "./repository.js";

export const PROMPT_VERSION = "itv-break-v2";
export function createWorker({ upstreamFetch = globalThis.fetch, now = Date.now, uuid = () => crypto.randomUUID() } = {}) {
 return {
  async fetch(request, env) {
   const url = new URL(request.url);
   if (request.method === "GET" && url.pathname === "/health") return reply({ status: "ok" });
   if (!["/api/jev/evaluate","/api/events","/api/status"].includes(url.pathname)) return reply({error:"Not found"},404);
   if (request.method !== (url.pathname === "/api/status" ? "GET" : "POST")) return reply({error:"Method not allowed"},405);
   try {
    if (!env.ITV_TEST_TOKEN || !env.DB) throw new HttpError(503,"SERVER_NOT_CONFIGURED");
    if (request.headers.get("Authorization") !== `Bearer ${env.ITV_TEST_TOKEN}`) throw new HttpError(401,"UNAUTHORIZED");
    if (url.pathname === "/api/status") {
     await env.DB.prepare("SELECT 1 FROM relay_quotas LIMIT 1").first();
     return reply({status:"ok",protocol_version:2,jev_enabled:env.JEV_ENABLED !== "false",model:env.JEV_MODEL || "jev-1.14.0"});
    }
    const data = await readJson(request);
    if (url.pathname === "/api/events") {
     const who = identity({ ...data, id: data.intervention_id }, uuid);
     if (who.legacy) throw new HttpError(400,"PROTOCOL_REQUIRED");
     const message = { event_id:id(data.event_id,"event_id"),intervention_id:who.eventId,
      tester_id:who.tester,source:who.source,event_type:data.event_type,
      occurred_at:timestamp(data.occurred_at),data:data.data };
     return reply(await recordEvent(env.DB,message,env,now()));
    }
    const input = evaluation(data,uuid);
    const digest = await hash(input);
    const previous = await existing(env.DB,input.eventId);
    if (previous) {
     if (previous.request_hash !== digest) throw new HttpError(409,"ID_REUSED_WITH_DIFFERENT_INPUT");
     return reply(resultFor(previous,true),previous.request_state === "PENDING" ? 202 : 200);
    }
    if (!input.legacy && Math.abs(now()-Date.parse(input.observedAt)) > 300000) throw new HttpError(400,"STALE_CONTEXT");
    if (env.JEV_ENABLED === "false") throw new HttpError(503,"JEV_DISABLED");
    if (!env.JEV_API_KEY) throw new HttpError(503,"SERVER_NOT_CONFIGURED");
    const started = now(), token = uuid();
    const reservation = await reserve(env.DB,input,env,started,token,digest,PROMPT_VERSION);
    if (!reservation.owner) return reply(resultFor(reservation.row,true),reservation.row.request_state === "PENDING" ? 202 : 200);
    const c = input.context;
    const payload = {
     model:env.JEV_MODEL || "jev-1.14.0",
     state:{
      current_session_minutes:c.session_minutes,continuous_shortform_minutes:c.continuous_minutes,
      returns_in_last_10_minutes:c.returns_10m,returns_observation_coverage_seconds:c.returns_observed_seconds,bedtime_goal_enabled:c.bedtime_goal_enabled,
      minutes_after_bedtime_goal:c.bedtime_passed_minutes
     },
     questions:{intervene:{type:"noul",
      instructions:"Based only on these reported short-form viewing measures, would offering an optional short break now be appropriate? Consider sustained viewing, repeated app returns, and an enabled bedtime goal. A disabled bedtime goal provides no evidence. This is appropriateness of timing, not a diagnosis or a prediction that the user will comply. Prefer no when the evidence is insufficient.",
      criteria:{true:"The observed pattern supports a timely, voluntary break suggestion.",false:"The pattern is brief or insufficient to justify a break suggestion now."}}}
    };
    let outcome;
    try {
     const response = await upstreamFetch("https://api.typesafe.ai/v1/systemone",{
      method:"POST",headers:{Authorization:`Bearer ${env.JEV_API_KEY}`,"Content-Type":"application/json"},
      body:JSON.stringify(payload),signal:AbortSignal.timeout(8000)
     });
     if (!response.ok) {
      const error = response.status===402 ? "JEV_CREDITS_EXHAUSTED" : response.status===429 ? "JEV_RATE_LIMITED" : "JEV_REQUEST_FAILED";
      await response.body?.cancel();
      outcome={status:"FAILED",called:true,error};
     } else {
      const result=await response.json(),p=result.answers?.intervene?.noul;
      if (!Number.isFinite(p)||p<0||p>1||typeof result.model!=="string") outcome={status:"FAILED",called:true,error:"INVALID_JEV_RESPONSE"};
      else outcome={status:"SUCCEEDED",called:true,probability:p,model:result.model,
       tokens:Number.isInteger(result.usage?.input_tokens)&&result.usage.input_tokens>=0 ? result.usage.input_tokens : null};
     }
    } catch { outcome={status:"UNKNOWN",called:true,error:"JEV_UNAVAILABLE_OR_TIMEOUT"}; }
    outcome.latency=Math.max(0,now()-started);
    // Failure after an upstream call is never retried automatically: the reservation remains.
    const row=await finish(env.DB,input.eventId,token,outcome);
    return reply(resultFor(row),outcome.status==="SUCCEEDED"?200:502);
   } catch (error) {
    if (error instanceof HttpError) return reply({error:error.code,message:error.message},error.status);
    // Do not log request bodies, tokens or provider credentials.
    return reply({error:"STORAGE_OR_SERVER_ERROR"},503);
   }
  }
 };
}
export default createWorker();
