-- Separate CP15 identity/protocol proof. Never migrate an existing backup store.
CREATE TABLE transfer_proof_meta (
  singleton INTEGER PRIMARY KEY CHECK(singleton=1),
  identity TEXT NOT NULL CHECK(identity='still-synthetic-async-transfer'),
  schema_version INTEGER NOT NULL CHECK(schema_version=1),
  high_water INTEGER NOT NULL CHECK(high_water>=0),
  event_count INTEGER NOT NULL CHECK(event_count>=0),
  operation_count INTEGER NOT NULL CHECK(operation_count>=0),
  attempt_count INTEGER NOT NULL CHECK(attempt_count>=0)
) STRICT;
INSERT INTO transfer_proof_meta VALUES(1,'still-synthetic-async-transfer',1,0,0,0,0);
CREATE TABLE transfer_proof_attempts (
  owner_id TEXT NOT NULL, attempt_id TEXT NOT NULL, source_key TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK(revision>0), record_json TEXT NOT NULL,
  PRIMARY KEY(owner_id,attempt_id), UNIQUE(owner_id,source_key)
) STRICT;
CREATE TABLE transfer_proof_events (
  owner_id TEXT NOT NULL, attempt_id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision>0),
  kind TEXT NOT NULL CHECK(kind IN('create','dispatch','acknowledge','cancel','fence','reconcile')),
  operation_key TEXT, record_json TEXT NOT NULL, PRIMARY KEY(owner_id,attempt_id,revision)
) STRICT;
CREATE TABLE transfer_proof_operations (
  owner_id TEXT NOT NULL, operation_key TEXT NOT NULL, descriptor_json TEXT NOT NULL,
  attempt_id TEXT NOT NULL, result_revision INTEGER NOT NULL CHECK(result_revision>0),
  PRIMARY KEY(owner_id,operation_key)
) STRICT;
PRAGMA application_id=1398032947;
PRAGMA user_version=1;
