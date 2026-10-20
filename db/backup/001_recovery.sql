-- Isolated CP13 generated-fixture proof. No production backup or existing schema.
CREATE TABLE backup_proof_meta (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  identity TEXT NOT NULL CHECK (identity = 'still-synthetic-backup-recovery'),
  schema_version INTEGER NOT NULL CHECK (schema_version = 1),
  record_count INTEGER NOT NULL CHECK (record_count >= 0),
  event_count INTEGER NOT NULL CHECK (event_count >= 0),
  operation_count INTEGER NOT NULL CHECK (operation_count >= 0),
  reserved_events INTEGER NOT NULL CHECK (reserved_events >= 0),
  reserved_operations INTEGER NOT NULL CHECK (reserved_operations >= 0)
) STRICT;
INSERT INTO backup_proof_meta VALUES (1, 'still-synthetic-backup-recovery', 1, 0, 0, 0, 0, 0);
CREATE TABLE backup_proof_records (
  owner_id TEXT NOT NULL,
  recovery_id TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK (revision > 0),
  record_json TEXT NOT NULL,
  PRIMARY KEY (owner_id, recovery_id)
) STRICT;
CREATE TABLE backup_proof_events (
  owner_id TEXT NOT NULL,
  recovery_id TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK (revision > 0),
  kind TEXT NOT NULL CHECK (kind IN ('requested', 'prepared', 'intent', 'completed', 'uncertain', 'reconciled')),
  operation_key TEXT NOT NULL,
  record_json TEXT NOT NULL,
  PRIMARY KEY (owner_id, recovery_id, revision)
) STRICT;
CREATE TABLE backup_proof_operations (
  owner_id TEXT NOT NULL,
  operation_key TEXT NOT NULL,
  recovery_id TEXT NOT NULL,
  action TEXT NOT NULL CHECK (action IN ('request', 'prepare', 'transfer', 'verify', 'restore', 'reconcile')),
  command_json TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  result_revision INTEGER,
  PRIMARY KEY (owner_id, operation_key)
) STRICT;
PRAGMA application_id = 1398032946;
PRAGMA user_version = 1;
