export class HttpError extends Error {
  constructor(status, code, message = code) { super(message); this.status = status; this.code = code; }
}
export function reply(data, status = 200) {
  return Response.json(data, { status, headers: { "Cache-Control": "no-store" } });
}
export async function readJson(request) {
  if ((request.headers.get("Content-Type") || "").split(";")[0].trim().toLowerCase() !== "application/json")
    throw new HttpError(415, "JSON_REQUIRED");
  const reader = request.body?.getReader();
  if (!reader) throw new HttpError(400, "INVALID_JSON");
  const chunks = []; let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read(); if (done) break;
      length += value.byteLength;
      if (length > 8192) { await reader.cancel(); throw new HttpError(413, "REQUEST_TOO_LARGE"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length); let offset = 0;
  for (const c of chunks) { bytes.set(c, offset); offset += c.length; }
  try {
    const data = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error();
    return data;
  } catch { throw new HttpError(400, "INVALID_JSON"); }
}
export function number(value, key, max = 1440, integer = false) {
  if (!Number.isFinite(value) || value < 0 || value > max || (integer && !Number.isInteger(value)))
    throw new HttpError(400, "INVALID_INPUT", key);
  return value;
}
export function text(value, key, max = 100, optional = false) {
  if (optional && (value === undefined || value === null)) return null;
  if (typeof value !== "string" || !value.trim() || value.length > max)
    throw new HttpError(400, "INVALID_INPUT", key);
  return value;
}
export function id(value, key) {
  const s = text(value, key, 100);
  if (!/^[A-Za-z0-9_.:-]+$/.test(s)) throw new HttpError(400, "INVALID_INPUT", key);
  return s;
}
export function timestamp(value) {
  if (typeof value !== "string" || value.length > 40 || !Number.isFinite(Date.parse(value)))
    throw new HttpError(400, "INVALID_TIMESTAMP");
  return new Date(value).toISOString();
}
export function identity(data, uuid) {
  const legacy = data.protocol_version === undefined;
  if (!legacy && data.protocol_version !== 2) throw new HttpError(400, "UNSUPPORTED_PROTOCOL");
  const tester = legacy ? "LEGACY" : text(data.tester_id, "tester_id", 3);
  if (!legacy && !/^T0[1-7]$/.test(tester)) throw new HttpError(400, "INVALID_TESTER");
  const source = legacy ? "LEGACY" : data.source;
  if (!["LIVE", "MANUAL", "LEGACY"].includes(source) || (!legacy && source === "LEGACY"))
    throw new HttpError(400, "INVALID_SOURCE");
  return { legacy, tester, source, eventId: legacy ? uuid() : id(data.id, "id") };
}
export function evaluation(data, uuid) {
  const who = identity(data, uuid);
  const bedtimeEnabled = data.bedtime_goal_enabled !== false;
  if (data.bedtime_goal_enabled !== undefined && typeof data.bedtime_goal_enabled !== "boolean")
    throw new HttpError(400, "INVALID_INPUT", "bedtime_goal_enabled");
  const minutes = number(data.session_minutes, "session_minutes");
  const returns = number(data.returns_10m, "returns_10m", 1440, true);
  const bedtime = bedtimeEnabled ? number(data.bedtime_passed_minutes, "bedtime_passed_minutes", 1440, true) : null;
  const continuous = data.continuous_minutes == null ? minutes : number(data.continuous_minutes, "continuous_minutes");
  if (continuous > minutes) throw new HttpError(400, "INVALID_INPUT", "continuous_minutes");
  const coverage = data.returns_observed_seconds == null ? 600 : number(data.returns_observed_seconds,"returns_observed_seconds",600,true);
  if (!who.legacy && !data.observed_at) throw new HttpError(400,"OBSERVED_AT_REQUIRED");
  const settings = data.settings ?? {};
  if (!settings || typeof settings !== "object" || Array.isArray(settings) || JSON.stringify(settings).length > 2000)
    throw new HttpError(400, "INVALID_SETTINGS");
  return { ...who, context: { session_minutes: minutes, continuous_minutes: continuous, returns_10m: returns,
    bedtime_passed_minutes: bedtime, bedtime_goal_enabled: bedtimeEnabled, returns_observed_seconds: coverage },
    settings, trigger: text(data.trigger_reason, "trigger_reason", 200, true),
    packageName: text(data.package_name, "package_name", 100, true),
    sessionId: data.session_id == null ? null : id(data.session_id, "session_id"),
    appVersion: text(data.app_version, "app_version", 60, true),
    observedAt: data.observed_at ? timestamp(data.observed_at) : null };
}
export function canonical(value) {
  if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
  if (value !== null && typeof value === "object")
    return "{" + Object.keys(value).sort().map(k => JSON.stringify(k) + ":" + canonical(value[k])).join(",") + "}";
  return JSON.stringify(value);
}
export async function hash(value) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(canonical(value)));
  return [...new Uint8Array(bytes)].map(x => x.toString(16).padStart(2, "0")).join("");
}
export function limit(value, fallback, max) {
  const n = Number(value ?? fallback);
  if (!Number.isInteger(n) || n < 1 || n > max) throw new HttpError(503, "INVALID_SERVER_LIMIT");
  return n;
}
