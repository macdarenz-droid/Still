-- CP9 local-development metadata only; no execution, media or control-plane tables.
CREATE TABLE cleanup_meta (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  identity TEXT NOT NULL CHECK (identity = 'still-cleanup-journal'),
  schema_version INTEGER NOT NULL CHECK (schema_version = 1),
  clock_high_water INTEGER NOT NULL CHECK (clock_high_water >= 0),
  operation_count INTEGER NOT NULL CHECK (operation_count >= 0)
) STRICT;
INSERT INTO cleanup_meta VALUES (1, 'still-cleanup-journal', 1, 0, 0);
CREATE TABLE cleanup_plans (
  owner_id TEXT NOT NULL,
  plan_id TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK (revision > 0),
  state TEXT NOT NULL CHECK (state IN ('blocked', 'reviewable', 'confirmed_nonexecuting', 'uncertain', 'cancelled', 'expired')),
  expires_at INTEGER NOT NULL CHECK (expires_at > 0),
  record_json TEXT NOT NULL,
  PRIMARY KEY (owner_id, plan_id)
) STRICT;
CREATE TABLE cleanup_operations (
  owner_id TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  command_json TEXT NOT NULL,
  result_json TEXT NOT NULL,
  PRIMARY KEY (owner_id, idempotency_key)
) STRICT;
PRAGMA application_id = 1398032945;
PRAGMA user_version = 1;
