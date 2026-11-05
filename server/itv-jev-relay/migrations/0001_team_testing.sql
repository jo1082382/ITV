-- The imported production table is preserved. Legacy columns retain their meaning.
CREATE TABLE IF NOT EXISTS intervention_tests (
 id TEXT PRIMARY KEY, tester_id TEXT NOT NULL, recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 session_minutes REAL NOT NULL, returns_10m INTEGER NOT NULL, bedtime_passed_minutes INTEGER NOT NULL,
 trigger_reason TEXT, jev_called INTEGER NOT NULL DEFAULT 0, jev_probability REAL, jev_model TEXT, input_tokens INTEGER,
 final_intervene INTEGER, suppression_reason TEXT, user_action TEXT,
 feedback_score INTEGER CHECK(feedback_score BETWEEN 0 AND 10), feedback_recorded_at TEXT, feedback_status TEXT,
 CHECK(jev_called IN (0,1)), CHECK(final_intervene IN (0,1)),
 CHECK(jev_probability BETWEEN 0 AND 1), CHECK(session_minutes >= 0), CHECK(returns_10m >= 0)
);
ALTER TABLE intervention_tests ADD COLUMN source TEXT NOT NULL DEFAULT 'LEGACY';
ALTER TABLE intervention_tests ADD COLUMN protocol_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE intervention_tests ADD COLUMN context_json TEXT;
ALTER TABLE intervention_tests ADD COLUMN settings_json TEXT;
ALTER TABLE intervention_tests ADD COLUMN app_version TEXT;
ALTER TABLE intervention_tests ADD COLUMN package_name TEXT;
ALTER TABLE intervention_tests ADD COLUMN session_id TEXT;
ALTER TABLE intervention_tests ADD COLUMN observed_at TEXT;
ALTER TABLE intervention_tests ADD COLUMN server_received_at TEXT;
ALTER TABLE intervention_tests ADD COLUMN request_state TEXT NOT NULL DEFAULT 'LEGACY';
ALTER TABLE intervention_tests ADD COLUMN request_hash TEXT;
ALTER TABLE intervention_tests ADD COLUMN reservation_token TEXT;
ALTER TABLE intervention_tests ADD COLUMN error_code TEXT;
ALTER TABLE intervention_tests ADD COLUMN latency_ms INTEGER;
ALTER TABLE intervention_tests ADD COLUMN displayed_at TEXT;
ALTER TABLE intervention_tests ADD COLUMN bedtime_goal_enabled INTEGER;
ALTER TABLE intervention_tests ADD COLUMN prompt_version TEXT;
CREATE TABLE intervention_events (
 event_id TEXT PRIMARY KEY, intervention_id TEXT NOT NULL, tester_id TEXT NOT NULL,
 source TEXT NOT NULL, event_type TEXT NOT NULL, occurred_at TEXT NOT NULL,
 received_at TEXT NOT NULL, data_json TEXT NOT NULL, content_hash TEXT NOT NULL
);
CREATE INDEX intervention_events_parent ON intervention_events(intervention_id, received_at);
CREATE INDEX intervention_tests_tester ON intervention_tests(tester_id, recorded_at);
CREATE TABLE relay_quotas (bucket TEXT PRIMARY KEY, used INTEGER NOT NULL DEFAULT 0);
