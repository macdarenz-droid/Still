-- S01: local control plane only. No media bytes, URLs, device catalogue or tokens.
CREATE TABLE IF NOT EXISTS consent (
  owner_id TEXT PRIMARY KEY,
  revision INTEGER NOT NULL CHECK (revision > 0),
  record_json TEXT NOT NULL
) STRICT;
CREATE TABLE IF NOT EXISTS backup_policy (
  owner_id TEXT PRIMARY KEY,
  revision INTEGER NOT NULL CHECK (revision > 0),
  record_json TEXT NOT NULL
) STRICT;
CREATE TABLE IF NOT EXISTS jobs (
  job_id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK (revision > 0),
  state TEXT NOT NULL CHECK (state IN ('queued', 'running', 'cancelling', 'cancelled', 'succeeded', 'failed')),
  record_json TEXT NOT NULL,
  lease_token TEXT,
  lease_until INTEGER
) STRICT;
CREATE INDEX IF NOT EXISTS jobs_owner ON jobs(owner_id, job_id);
CREATE INDEX IF NOT EXISTS jobs_dispatch ON jobs(state, lease_until);
CREATE TABLE IF NOT EXISTS attempt_receipts (
  job_id TEXT NOT NULL REFERENCES jobs(job_id),
  attempt INTEGER NOT NULL CHECK (attempt > 0),
  owner_id TEXT NOT NULL,
  receipt_id TEXT NOT NULL,
  incurred_minor INTEGER NOT NULL CHECK (incurred_minor >= 0),
  PRIMARY KEY(job_id, attempt)
) STRICT;
CREATE TABLE IF NOT EXISTS operations (
  owner_id TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  result_json TEXT NOT NULL,
  PRIMARY KEY(owner_id, idempotency_key)
) STRICT;
CREATE TABLE IF NOT EXISTS outbox (
  offset INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE,
  owner_id TEXT NOT NULL,
  event_json TEXT NOT NULL
) STRICT;
PRAGMA user_version = 1;
