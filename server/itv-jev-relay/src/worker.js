import { HttpError, reply, readJson, evaluation, identity, id, timestamp, hash } from "./protocol.js";
import { existing, reserve, finish, resultFor, recordEvent } from "./repository.js";

export const PROMPT_VERSION = "itv-coaching-v5";
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
    const isCombinedMode = input.packageName === "ALL" || (c.raw_general_minutes !== undefined && c.raw_general_minutes > 0);
    const resolvedCategory = c.app_category || (
      input.packageName === "com.zhiliaoapp.musically" || input.packageName === "com.instagram.android"
        ? "SHORTFORM"
        : input.packageName === "com.google.android.youtube"
          ? (c.youtube_watch_mode === "SHORTS" ? "SHORTFORM" : "LONGFORM_VIDEO")
          : "GENERAL"
    );
    const state = {
      app_package: input.packageName || "UNKNOWN",
      app_activity_category: resolvedCategory,
      activity_phase: c.activity_phase || "UNKNOWN",
      usage_mode: isCombinedMode ? "COMBINED_TOTAL_USAGE" : "APP_SPECIFIC",
      current_session_minutes: c.session_minutes,
      continuous_equivalent_minutes: c.continuous_equivalent_minutes ?? c.continuous_minutes,
      returns_in_last_10_minutes: c.returns_10m,
      returns_observation_coverage_seconds: c.returns_observed_seconds,
      bedtime_goal_enabled: c.bedtime_goal_enabled,
      minutes_after_bedtime_goal: c.bedtime_passed_minutes
    };
    if (c.raw_shortform_minutes !== undefined) state.raw_shortform_minutes = c.raw_shortform_minutes;
    if (c.raw_general_minutes !== undefined) state.raw_general_phone_usage_minutes = c.raw_general_minutes;
    if (c.longform_watch_minutes !== undefined) state.raw_longform_video_minutes = c.longform_watch_minutes;
    if (c.current_video_watch_minutes !== undefined) state.current_video_watch_minutes = c.current_video_watch_minutes;
    if (c.completed_longform_count !== undefined) state.completed_longform_video_count = c.completed_longform_count;
    if (c.youtube_watch_mode !== undefined) state.youtube_watch_mode = c.youtube_watch_mode;
    if (c.audio_pipeline_kind !== undefined) state.active_audio_pipeline = c.audio_pipeline_kind;
    if (c.general_app_idle_seconds !== undefined) state.idle_seconds_without_interaction = c.general_app_idle_seconds;
    if (c.personal_avg_session_minutes !== undefined) state.personal_avg_session_minutes = c.personal_avg_session_minutes;
    if (c.session_vs_personal_avg_ratio !== undefined) state.session_vs_personal_avg_ratio = c.session_vs_personal_avg_ratio;
    if (c.today_shortform_minutes !== undefined) state.today_shortform_minutes = c.today_shortform_minutes;
    if (c.avg_7d_daily_shortform_minutes !== undefined) state.avg_7d_daily_shortform_minutes = c.avg_7d_daily_shortform_minutes;
    if (c.today_vs_7d_avg_ratio !== undefined) state.today_vs_7d_avg_ratio = c.today_vs_7d_avg_ratio;
    if (c.shortform_share_of_phone_ratio !== undefined) state.shortform_share_of_phone_ratio = c.shortform_share_of_phone_ratio;
    if (c.effective_spm_1m !== undefined) state.effective_swipes_per_minute_1m = c.effective_spm_1m;
    if (c.effective_session_spm !== undefined) state.effective_session_swipes_per_minute = c.effective_session_spm;
    if (c.personal_avg_spm !== undefined) state.personal_avg_swipes_per_minute = c.personal_avg_spm;
    if (c.current_vs_personal_avg_spm_ratio !== undefined) state.current_vs_personal_avg_spm_ratio = c.current_vs_personal_avg_spm_ratio;
    if (c.swipe_accel_1m !== undefined) state.swipe_acceleration_1m = c.swipe_accel_1m;
    if (c.fast_swipe_streak !== undefined) state.consecutive_fast_swipe_streak = c.fast_swipe_streak;
    if (c.swipe_overheated !== undefined) state.swipe_overheated = c.swipe_overheated;
    if (c.ambient_lux !== undefined) state.ambient_lux = c.ambient_lux;
    if (c.screen_brightness !== undefined) state.screen_brightness = c.screen_brightness;
    if (c.bedtime_distance_minutes !== undefined) state.bedtime_distance_minutes = c.bedtime_distance_minutes;
    if (c.user_environment_state !== undefined) state.user_environment_state = c.user_environment_state;
    const payload = {
     model:env.JEV_MODEL || "jev-1.14.0",
     state,
     questions:{intervene:{type:"noul",
      instructions:"Evaluate whether suggesting an optional short eye/mental rest break is appropriate based on the user's pre-normalized sustained smartphone immersion.\n\n1. IMMERSION CONVERSION (PRE-NORMALIZED BY CLIENT):\n- 'continuous_equivalent_minutes' is ALREADY normalized by the client into equivalent high-immersion short-form minutes using category weighting.\n- Use 'continuous_equivalent_minutes' directly as the primary fatigue/immersion duration metric. DO NOT divide or re-discount 'continuous_equivalent_minutes' again by 3, even when 'raw_general_phone_usage_minutes' is present.\n- Treat 'raw_shortform_minutes', 'raw_general_phone_usage_minutes', and 'raw_longform_video_minutes' strictly as secondary context.\n\n2. PROBABILITY CALIBRATION (DURATION & CATEGORY):\n- Anchor your probability smoothly and monotonically on 'continuous_equivalent_minutes':\n  * <= 5 equivalent minutes: brief usage, rest is premature (low probability, ~0.10-0.25).\n  * ~10 equivalent minutes: noticeable sustained immersion, early fatigue forming (moderate probability, ~0.50-0.65).\n  * ~15 equivalent minutes: substantial continuous immersion where an optional short rest is clearly appropriate (high probability, ~0.75-0.85).\n  * >= 20-30 equivalent minutes: prolonged continuous immersion with clear cognitive/visual fatigue (very high probability, ~0.88-0.96).\n- Apply only a modest secondary nuance by 'app_activity_category' at the same 'continuous_equivalent_minutes':\n  * SHORTFORM and COMMUNITY_SNS (passive infinite scroll / high dopamine): lean slightly higher (+0.03 to +0.06).\n  * GAME and LONGFORM_VIDEO: baseline calibration above.\n  * DOCUMENT, MESSENGER, BROWSER_SEARCH, GENERAL (purposeful/active tasks): lean slightly more conservative (-0.05 to -0.10) unless equivalent duration >= 15-20 minutes.\n\n3. PURE REST NEED (TIMING MANAGED BY CLIENT):\n- Do NOT worry about whether the user is in the middle of a game match, video, or typing. The on-device client handles non-intrusive presentation timing (holding the break suggestion until a match ends, video pauses, or idle state).\n- Your role is solely to judge whether the accumulated equivalent immersion warrants offering an optional break.\n\n4. CRITICAL ANTI-DOUBLE-COUNTING REQUIREMENT:\n- The on-device mobile client already dynamically shifts its acceptance threshold (-40%p to +25%p) for personal baseline ratios, real-time swipe or video-switching dynamics, and ambient darkness/bedtime.\n- DO NOT adjust your intervention probability for those client-side factors. Focus strictly on 'continuous_equivalent_minutes', 'app_activity_category', and 'current_session_minutes'.\n\nA disabled bedtime goal provides no evidence.",
      criteria:{true:"Continuous equivalent immersion has reached a sustained duration (around 15+ equivalent minutes, or slightly earlier for high-dopamine short-form/SNS feeds) where offering a brief optional rest is appropriate.",false:"Continuous equivalent usage is still brief (under ~10 equivalent minutes) and does not yet warrant offering a rest."}}}
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
