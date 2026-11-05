import { HttpError, canonical, hash, limit } from "./protocol.js";

const INSERT_COLUMNS = [
 "id","tester_id","session_minutes","returns_10m","bedtime_passed_minutes","trigger_reason",
 "source","protocol_version","context_json","settings_json","app_version","package_name","session_id",
 "observed_at","server_received_at","request_state","request_hash","reservation_token","bedtime_goal_enabled","prompt_version"
];
function stmt(db, sql, ...values) { return db.prepare(sql).bind(...values); }
export async function existing(db, id) {
 return stmt(db, "SELECT * FROM intervention_tests WHERE id = ?", id).first();
}
export function resultFor(row, cached = false) {
 const result = { id: row.id, status: row.request_state, cached, probability: row.jev_probability,
  model: row.jev_model, input_tokens: row.input_tokens, prompt_version: row.prompt_version };
 if (row.error_code) result.error = row.error_code;
 return result;
}
export async function reserve(db, input, env, now, token, digest, promptVersion) {
 const iso = new Date(now).toISOString();
 const day = iso.slice(0,10), minute = iso.slice(0,16);
 const quotas = [
  [`jev:day:${day}`, limit(env.JEV_DAILY_LIMIT, 300, 10000)],
  [`jev:tester:${input.tester}:${day}`, limit(env.JEV_TESTER_DAILY_LIMIT, 40, 1000)],
  [`jev:minute:${minute}`, limit(env.JEV_MINUTE_LIMIT, 20, 120)]
 ];
 const c = input.context;
 // Legacy NOT NULL bedtime column uses 0 only with the explicit disabled flag.
 // context_json carries null for an absent goal; Jev also receives null, never a fabricated measurement.
 const values = [input.eventId,input.tester,c.session_minutes,c.returns_10m,c.bedtime_passed_minutes ?? 0,
  input.trigger,input.source,input.legacy ? 1 : 2,canonical(c),canonical(input.settings),
  input.appVersion,input.packageName,input.sessionId,input.observedAt,iso,"PENDING",digest,token,
  c.bedtime_goal_enabled ? 1 : 0,promptVersion];
 const checks = quotas.map(() => "(SELECT used FROM relay_quotas WHERE bucket = ?) < ?").join(" AND ");
 const statements = quotas.map(([key]) => stmt(db, "INSERT OR IGNORE INTO relay_quotas(bucket,used) VALUES(?,0)", key));
 statements.push(stmt(db,
  `INSERT OR IGNORE INTO intervention_tests(${INSERT_COLUMNS.join(",")})
    SELECT ${values.map(()=>"?").join(",")} WHERE ${checks}`,
  ...values, ...quotas.flat()));
 for (const [key] of quotas) statements.push(stmt(db,
  "UPDATE relay_quotas SET used = used + 1 WHERE bucket = ? AND EXISTS(SELECT 1 FROM intervention_tests WHERE id = ? AND reservation_token = ?)",
  key,input.eventId,token));
 await db.batch(statements);
 const row = await existing(db,input.eventId);
 if (!row) throw new HttpError(429,"CALL_LIMIT_REACHED");
 if (row.request_hash !== digest) throw new HttpError(409,"ID_REUSED_WITH_DIFFERENT_INPUT");
 return { owner: row.reservation_token === token, row };
}
export async function finish(db, id, token, fields) {
 await stmt(db,`UPDATE intervention_tests SET request_state=?, jev_called=?, jev_probability=?, jev_model=?,
   input_tokens=?, error_code=?, latency_ms=? WHERE id=? AND reservation_token=? AND request_state='PENDING'`,
  fields.status,fields.called ? 1 : 0,fields.probability ?? null,fields.model ?? null,fields.tokens ?? null,
  fields.error ?? null,fields.latency,id,token).run();
 return existing(db,id);
}
const KINDS = new Set(["displayed","suppressed","response","feedback","candidate","session_summary","mission"]);
const ACTIONS = new Set(["REST","LATER","DISMISS","NO_RESPONSE","CANCELLED"]);
export async function recordEvent(db, message, env, now) {
 const { event_id: eventId, intervention_id: parent, tester_id: tester, source, event_type: kind, occurred_at: at, data } = message;
 if (!KINDS.has(kind) || !data || typeof data !== "object" || Array.isArray(data))
  throw new HttpError(400,"INVALID_EVENT");
 const allowed = new Set(["reason","action","score","feedback_status","context","settings","result","duration_ms","deferred"]);
 if (Object.keys(data).some(k=>!allowed.has(k)) || canonical(data).length > 4000)
  throw new HttpError(400,"INVALID_EVENT_DATA");
 if (data.reason != null && (typeof data.reason !== "string" || data.reason.length > 250))
  throw new HttpError(400,"INVALID_EVENT_DATA");
 if (kind === "response" && !ACTIONS.has(data.action)) throw new HttpError(400,"INVALID_ACTION");
 if (kind === "feedback") {
  if (!["SUBMITTED","SKIPPED"].includes(data.feedback_status)) throw new HttpError(400,"INVALID_FEEDBACK");
  if (data.feedback_status === "SUBMITTED" && (!Number.isInteger(data.score) || data.score < 0 || data.score > 10))
   throw new HttpError(400,"INVALID_SCORE");
  if (data.feedback_status === "SKIPPED" && data.score !== null) throw new HttpError(400,"SKIP_REQUIRES_NULL");
 }
 const digest = await hash(message);
 const old = await stmt(db,"SELECT content_hash FROM intervention_events WHERE event_id=?",eventId).first();
 if (old) {
  if (old.content_hash !== digest) throw new HttpError(409,"EVENT_ID_CONFLICT");
  return { recorded: true, duplicate: true };
 }
 const row = await existing(db,parent);
 if (["displayed","suppressed","response","feedback","mission"].includes(kind)) {
  if (!row || row.tester_id !== tester || row.source !== source) throw new HttpError(409,"PARENT_NOT_READY");
  if (kind === "displayed" && row.request_state !== "SUCCEEDED") throw new HttpError(409,"JUDGMENT_NOT_SUCCEEDED");
  if (kind === "feedback" && !row.displayed_at) throw new HttpError(409,"INTERVENTION_NOT_DISPLAYED");
 }
 const received = new Date(now).toISOString();
 const key = "events:day:" + received.slice(0,10);
 const max = limit(env.EVENT_DAILY_LIMIT,5000,50000);
 const receipt = crypto.randomUUID();
 const statements = [
  stmt(db,"INSERT OR IGNORE INTO relay_quotas(bucket,used) VALUES(?,0)",key),
  stmt(db,`INSERT OR IGNORE INTO intervention_events(event_id,intervention_id,tester_id,source,event_type,occurred_at,received_at,data_json,content_hash,receipt_token)
    SELECT ?,?,?,?,?,?,?,?,?,? WHERE (SELECT used FROM relay_quotas WHERE bucket=?) < ?`,
    eventId,parent,tester,source,kind,at,received,canonical(data),digest,receipt,key,max),
  stmt(db,"UPDATE relay_quotas SET used=used+1 WHERE bucket=? AND EXISTS(SELECT 1 FROM intervention_events WHERE event_id=? AND receipt_token=?)",key,eventId,receipt)
 ];
 const accepted = " AND EXISTS(SELECT 1 FROM intervention_events WHERE event_id=? AND receipt_token=?)";
 if (kind === "displayed") statements.push(stmt(db,
  "UPDATE intervention_tests SET final_intervene=1,displayed_at=COALESCE(displayed_at,?),feedback_status=COALESCE(feedback_status,'PENDING') WHERE id=?" + accepted,
  at,parent,eventId,receipt));
 if (kind === "suppressed") statements.push(stmt(db,
  "UPDATE intervention_tests SET final_intervene=0,suppression_reason=? WHERE id=? AND displayed_at IS NULL" + accepted,
  data.reason ?? "UNSPECIFIED",parent,eventId,receipt));
 if (kind === "response") statements.push(stmt(db,
  "UPDATE intervention_tests SET user_action=? WHERE id=? AND user_action IS NULL" + accepted,
  data.action,parent,eventId,receipt));
 if (kind === "feedback") statements.push(stmt(db,
  "UPDATE intervention_tests SET feedback_score=?,feedback_recorded_at=?,feedback_status=? WHERE id=? AND (feedback_status IS NULL OR feedback_status='PENDING')" + accepted,
  data.score,at,data.feedback_status,parent,eventId,receipt));
 await db.batch(statements);
 const stored = await stmt(db,"SELECT content_hash,receipt_token FROM intervention_events WHERE event_id=?",eventId).first();
 if (!stored) throw new HttpError(429,"EVENT_LIMIT_REACHED");
 if (stored.content_hash !== digest) throw new HttpError(409,"EVENT_ID_CONFLICT");
 return { recorded: true, duplicate: stored.receipt_token !== receipt };
}
