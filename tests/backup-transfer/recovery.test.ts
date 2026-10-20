import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import type { TestContext } from "node:test";
import { TransferProofError } from "../../packages/contracts/src/backup-transfer-recovery.ts";
import type { SyntheticTransferStatus, TransferAttempt, TransferKind } from "../../packages/contracts/src/backup-transfer-recovery.ts";
import { TransferRecoveryLedger, createTransferJournalFile, disposeTransferJournalFile, inspectTransferJournalForTest, transferJournalBytesForTest } from "../../services/backup-transfer/ledger.ts";
import { canonical, SyntheticTransferWorld } from "../../services/backup-transfer/synthetic-world.ts";

const OWNER = "synthetic_owner";
function fails(action: () => unknown, code: string): void { assert.throws(action, error => error instanceof TransferProofError && error.code === code); }
function fixture(t: TestContext, totalBytes = 30) {
  const world = new SyntheticTransferWorld(), binding = world.generate(OWNER, totalBytes), file = createTransferJournalFile(), clock = { at: 10_000 };
  const ledgers: TransferRecoveryLedger[] = []; let serial = 0;
  const open = (authority = true) => { const ledger = new TransferRecoveryLedger(file, { ...(authority ? { world } : {}), now: () => clock.at }); ledgers.push(ledger); return ledger; };
  const ledger = open();
  const command = (record: TransferAttempt, key = `command_${++serial}`) => ({ idempotencyKey: key, expectedRevision: record.revision });
  const create = (current = ledger, key = `create_${++serial}`) => current.create(OWNER, { idempotencyKey: key, binding }).attempt;
  const dispatch = (record: TransferAttempt, kind: TransferKind, byteLength = 0, current = ledger) => current.dispatch(OWNER, record.id, { ...command(record), kind, byteLength, leaseMs: 1000 });
  const complete = async (record: TransferAttempt, kind: TransferKind, byteLength = 0, outcome: "applied" | "not_applied" = "applied", response: "known" | "unknown" = "known", current = ledger) => {
    const issued = dispatch(record, kind, byteLength, current), waiting = world.launch(issued.dispatch!);
    world.settle(issued.attempt.pending!.requestId, outcome, response);
    const receipt = await waiting, acknowledgment = command(issued.attempt);
    return { attempt: current.acknowledge(OWNER, record.id, acknowledgment, receipt).attempt, issued, receipt, acknowledgment };
  };
  const object = async () => (await complete(create(), "create")).attempt;
  t.after(() => { for (const ledger of ledgers) ledger.close(); disposeTransferJournalFile(file); });
  return { world, binding, file, clock, ledger, open, command, create, dispatch, complete, object };
}

test("S53 default authority denial and synthetic-only location/identity boundary", t => {
  const f = fixture(t), denied = f.open(false);
  fails(() => denied.create(OWNER, { idempotencyKey: "denied", binding: f.binding }), "transfer_authority_unavailable");
  fails(() => new TransferRecoveryLedger("/tmp/unowned.sqlite" as never), "transfer_fixture_location_denied");
  fails(() => new TransferRecoveryLedger({ scope: "generated_async_transfer_journal" }), "transfer_fixture_location_denied");
  const forged = structuredClone(f.binding); forged.source.versionId = "untrusted_version";
  fails(() => f.ledger.create(OWNER, { idempotencyKey: "forged", binding: forged }), "transfer_authority_denied");
  fails(() => f.ledger.create("different_owner", { idempotencyKey: "wrong_owner", binding: f.binding }), "transfer_authority_denied");
});

test("S54 deferred create/append/finalize uses confirmed offsets and ends only uploaded-unverified", async t => {
  const f = fixture(t), initial = f.create();
  const issued = f.dispatch(initial, "create"), waiting = f.world.launch(issued.dispatch!);
  assert.equal(f.ledger.get(OWNER, initial.id).acknowledgedOffset, 0);
  assert.deepEqual(f.world.countsForTest(), { requests: 1, objects: 0 });
  f.world.settle(issued.attempt.pending!.requestId, "applied");
  let record = f.ledger.acknowledge(OWNER, initial.id, f.command(issued.attempt), await waiting).attempt;
  record = (await f.complete(record, "append", 10)).attempt;
  assert.equal(record.acknowledgedOffset, 10);
  record = (await f.complete(record, "append", 20)).attempt;
  assert.equal(record.acknowledgedOffset, 30); assert.equal(record.state, "ready");
  record = (await f.complete(record, "finalize")).attempt;
  assert.equal(record.state, "uploaded_unverified"); assert.equal(record.lastObservation!.finalized, true);
  assert.equal(record.testOnly, true); assert.equal(record.productionCapability, false);
  assert.deepEqual(f.world.countsForTest(), { requests: 4, objects: 1 });
});

test("S54 cancellation keeps pending remote work and records late completion without active progress", async t => {
  const f = fixture(t), initial = await f.object(), issued = f.dispatch(initial, "append", 10), waiting = f.world.launch(issued.dispatch!);
  let record = f.ledger.cancel(OWNER, initial.id, f.command(issued.attempt)).attempt;
  assert.equal(record.stopRequested, true); assert.equal(record.state, "uncertain"); assert.ok(record.pending);
  fails(() => f.dispatch(record, "append", 10), "transfer_reconciliation_required");
  record = f.ledger.reconcile(OWNER, record.id, f.command(record)).attempt;
  assert.equal(record.state, "uncertain"); assert.equal(record.acknowledgedOffset, 0);
  f.world.settle(issued.attempt.pending!.requestId, "applied"); const receipt = await waiting;
  fails(() => f.ledger.acknowledge(OWNER, initial.id, f.command(issued.attempt), receipt), "transfer_revision_conflict");
  fails(() => f.ledger.acknowledge(OWNER, initial.id, f.command(record), receipt), "transfer_late_response_reconcile_required");
  record = f.ledger.reconcile(OWNER, record.id, f.command(record)).attempt;
  assert.equal(record.state, "cancelled"); assert.equal(record.pending, null);
  assert.equal(record.acknowledgedOffset, 0); assert.equal(record.lastObservation!.offset, 10); assert.equal(record.lastObservation!.outcome, "applied");
});

test("S54 fresh epoch and disconnect never retarget old work or count late bytes as current", async t => {
  for (const change of ["reauthorize", "disconnect"] as const) {
    const f = fixture(t), initial = await f.object(), issued = f.dispatch(initial, "append", 10), waiting = f.world.launch(issued.dispatch!);
    const epoch = initial.binding.connectionEpoch; f.world[change](OWNER);
    let record = f.ledger.get(OWNER, initial.id);
    assert.equal(record.state, "uncertain"); assert.equal(record.binding.connectionEpoch, epoch);
    assert.equal(record.fenceReason, change === "reauthorize" ? "connection_changed" : "disconnected");
    f.world.settle(issued.attempt.pending!.requestId, "applied");
    const receipt = await waiting;
    fails(() => f.ledger.acknowledge(OWNER, record.id, f.command(record), receipt), "transfer_late_response_reconcile_required");
    record = f.ledger.reconcile(OWNER, record.id, f.command(record)).attempt;
    assert.equal(record.state, "fenced"); assert.equal(record.acknowledgedOffset, 0); assert.equal(record.lastObservation!.offset, 10);
    fails(() => f.dispatch(record, "append", 10), "transfer_reconciliation_required");
    const fresh = f.world.generate(OWNER, 30);
    const newer = f.ledger.create(OWNER, { idempotencyKey: "new_epoch", binding: fresh }).attempt;
    assert.notEqual(newer.binding.connectionEpoch, epoch); assert.equal(f.ledger.get(OWNER, record.id).binding.connectionEpoch, epoch);
  }
});

test("S54 restart with a live request preserves uncertainty and rejects the pre-restart callback", async t => {
  const f = fixture(t), initial = f.create(), issued = f.dispatch(initial, "create"), waiting = f.world.launch(issued.dispatch!);
  f.ledger.close(); const reopened = f.open(); let record = reopened.get(OWNER, initial.id);
  assert.equal(record.state, "uncertain"); assert.ok(record.pending); assert.equal(record.acknowledgedOffset, 0);
  record = reopened.reconcile(OWNER, record.id, f.command(record)).attempt;
  assert.equal(record.state, "uncertain"); assert.equal(f.world.countsForTest().objects, 0);
  f.world.settle(issued.attempt.pending!.requestId, "applied");
  const receipt = await waiting;
  fails(() => reopened.acknowledge(OWNER, record.id, f.command(record), receipt), "transfer_late_response_reconcile_required");
  record = reopened.reconcile(OWNER, record.id, f.command(record)).attempt;
  assert.equal(record.state, "ready"); assert.ok(record.object); assert.equal(f.world.countsForTest().objects, 1);
});

test("S54 local lease expiry and clock rollback do not release a live provider request", async t => {
  const f = fixture(t), initial = f.create(), issued = f.dispatch(initial, "create"), waiting = f.world.launch(issued.dispatch!);
  f.clock.at += 2000; let record = f.ledger.get(OWNER, initial.id);
  assert.equal(record.state, "uncertain"); assert.equal(record.leaseExpired, true);
  f.clock.at = 1; f.ledger.close(); const reopened = f.open();
  record = reopened.get(OWNER, record.id); assert.equal(record.leaseExpired, true);
  fails(() => f.dispatch(record, "create", 0, reopened), "transfer_reconciliation_required");
  record = reopened.reconcile(OWNER, record.id, f.command(record)).attempt;
  assert.ok(record.pending); assert.equal(record.state, "uncertain");
  f.world.settle(issued.attempt.pending!.requestId, "applied"); await waiting;
  record = reopened.reconcile(OWNER, record.id, f.command(record)).attempt;
  assert.equal(record.state, "ready"); assert.equal(record.leaseExpired, false); assert.equal(f.world.countsForTest().objects, 1);
});

test("S54 reopened ledger rejects the original callback before any status read or expiry", async t => {
  const f = fixture(t), initial = f.create(), issued = f.dispatch(initial, "create"), waiting = f.world.launch(issued.dispatch!);
  f.ledger.close(); f.world.settle(issued.attempt.pending!.requestId, "applied"); const receipt = await waiting;
  const reopened = f.open();
  fails(() => reopened.acknowledge(OWNER, initial.id, f.command(issued.attempt), receipt), "transfer_late_response_reconcile_required");
  const current = reopened.get(OWNER, initial.id); assert.equal(current.object, null); assert.equal(current.state, "uncertain");
  assert.ok(reopened.reconcile(OWNER, current.id, f.command(current)).attempt.object);
});

test("S54 ambiguous create remains uncertain until exact settled operation is visible", async t => {
  const f = fixture(t), response = await f.complete(f.create(), "create", 0, "applied", "unknown");
  let record = response.attempt; assert.equal(record.state, "uncertain"); assert.equal(record.object, null);
  fails(() => f.dispatch(record, "create"), "transfer_reconciliation_required");
  record = f.ledger.reconcile(OWNER, record.id, f.command(record)).attempt; assert.equal(record.state, "uncertain");
  f.world.reveal(record.pending!.requestId); record = f.ledger.reconcile(OWNER, record.id, f.command(record)).attempt;
  assert.ok(record.object); assert.equal(record.state, "ready"); assert.equal(f.world.countsForTest().objects, 1);
});

test("S54 ambiguous finalization is not verification and cannot start a replacement upload", async t => {
  const f = fixture(t); let record = await f.object(); record = (await f.complete(record, "append", 30)).attempt;
  record = (await f.complete(record, "finalize", 0, "applied", "unknown")).attempt;
  assert.equal(record.state, "uncertain"); assert.equal(record.acknowledgedOffset, 30);
  fails(() => f.dispatch(record, "create"), "transfer_reconciliation_required");
  f.world.reveal(record.pending!.requestId); record = f.ledger.reconcile(OWNER, record.id, f.command(record)).attempt;
  assert.equal(record.state, "uploaded_unverified"); assert.equal(record.productionCapability, false);
});

test("S54 unknown request absence never proves a create was not applied", t => {
  const f = fixture(t), initial = f.create(), issued = f.dispatch(initial, "create");
  f.ledger.close(); const reopened = f.open(); let record = reopened.get(OWNER, initial.id);
  record = reopened.reconcile(OWNER, record.id, f.command(record)).attempt;
  assert.equal(record.state, "uncertain"); assert.equal(record.pending!.requestId, issued.attempt.pending!.requestId);
  assert.deepEqual(f.world.countsForTest(), { requests: 0, objects: 0 });
  fails(() => f.dispatch(record, "create", 0, reopened), "transfer_reconciliation_required");
});

test("S54 explicit settled no-change permits retry with the original creation identity", async t => {
  const f = fixture(t), first = await f.complete(f.create(), "create", 0, "not_applied");
  assert.equal(first.attempt.state, "ready"); assert.equal(first.attempt.pending, null);
  const second = await f.complete(first.attempt, "create");
  assert.equal(second.issued.attempt.pending!.operationId, first.issued.attempt.pending!.operationId);
  assert.notEqual(second.issued.attempt.pending!.requestId, first.issued.attempt.pending!.requestId);
  assert.equal(f.world.countsForTest().objects, 1);
});

test("S54 duplicate dispatch never redispatches and replayed progress is historical", async t => {
  const f = fixture(t), initial = f.create(), command = { ...f.command(initial, "once"), kind: "create", byteLength: 0, leaseMs: 1000 };
  const issued = f.ledger.dispatch(OWNER, initial.id, command), waiting = f.world.launch(issued.dispatch!);
  assert.equal(f.world.launch(issued.dispatch!), waiting);
  const replay = f.ledger.dispatch(OWNER, initial.id, command); assert.equal(replay.replayed, true); assert.equal(replay.dispatch, null);
  f.world.settle(issued.attempt.pending!.requestId, "applied");
  const receipt = await waiting, ack = f.command(issued.attempt, "ack_once");
  const completed = f.ledger.acknowledge(OWNER, initial.id, ack, receipt).attempt;
  assert.deepEqual(f.ledger.acknowledge(OWNER, initial.id, ack, receipt).attempt, completed);
  assert.equal(f.ledger.dispatch(OWNER, initial.id, command).attempt.state, "in_flight");
  assert.equal(f.ledger.get(OWNER, initial.id).state, "ready");
  fails(() => f.ledger.dispatch(OWNER, initial.id, { ...command, leaseMs: 2 }), "transfer_idempotency_conflict");
  assert.equal(f.world.countsForTest().requests, 1);
});

test("S54 caller JSON cannot mint status authority even with valid test-only fields", async t => {
  const f = fixture(t), initial = f.create(), issued = f.dispatch(initial, "create"), waiting = f.world.launch(issued.dispatch!);
  f.world.settle(issued.attempt.pending!.requestId, "applied"); const receipt = await waiting;
  fails(() => f.ledger.acknowledge(OWNER, initial.id, f.command(issued.attempt), structuredClone(receipt)), "transfer_status_authority_denied");
  assert.equal(f.ledger.get(OWNER, initial.id).object, null);
});

test("S54 cancellation, withdrawal, expiry and closed origin revoke permits before physical admission", t => {
  for (const invalidation of ["cancel", "reauthorize", "disconnect", "source", "expiry", "close"] as const) {
    const f = fixture(t), initial = f.create(), issued = f.dispatch(initial, "create");
    if (invalidation === "cancel") f.ledger.cancel(OWNER, initial.id, f.command(issued.attempt));
    else if (invalidation === "source") f.world.changeSource(OWNER, f.binding, "componentRevision");
    else if (invalidation === "expiry") f.clock.at += 2000;
    else if (invalidation === "close") f.ledger.close();
    else f.world[invalidation](OWNER);
    fails(() => f.world.launch(issued.dispatch!), "transfer_dispatch_not_current");
    assert.deepEqual(f.world.countsForTest(), { requests: 0, objects: 0 }, invalidation);
  }
});

test("S54 expiry during acknowledgement admission persists time despite rejected write and clock rollback", async t => {
  const f = fixture(t), initial = f.create(), issued = f.dispatch(initial, "create"), waiting = f.world.launch(issued.dispatch!);
  f.world.settle(issued.attempt.pending!.requestId, "applied"); const receipt = await waiting;
  const now = Reflect.get(f.ledger, "now"); let calls = 0;
  Reflect.set(f.ledger, "now", () => ++calls === 2 ? 20_000 : 10_000);
  try { fails(() => f.ledger.acknowledge(OWNER, initial.id, f.command(issued.attempt), receipt), "transfer_late_response_reconcile_required"); }
  finally { Reflect.set(f.ledger, "now", now); }
  f.ledger.close(); const reopened = f.open();
  const current = reopened.get(OWNER, initial.id);
  assert.equal(current.leaseExpired, true); assert.equal(current.object, null); assert.equal(current.state, "uncertain");
  assert.ok(reopened.reconcile(OWNER, current.id, f.command(current)).attempt.object);
});

test("S54 wrong owner/attempt/epoch/source/request/session and revision cannot acknowledge", async t => {
  const f = fixture(t), initial = await f.object(), issued = f.dispatch(initial, "append", 10);
  f.world.launch(issued.dispatch!); f.world.settle(issued.attempt.pending!.requestId, "applied");
  const pending = issued.attempt.pending!;
  const changes: Partial<SyntheticTransferStatus>[] = [
    { ownerId: "wrong_owner" }, { attemptId: "wrong_attempt" }, { requestId: "wrong_request" }, { operationId: "wrong_operation" }, { intentRevision: 1 },
    { binding: { ...initial.binding, connectionEpoch: "wrong_epoch" } },
    { binding: { ...initial.binding, source: { ...initial.binding.source, componentRevision: "wrong_component" } } },
    { before: { ...pending.before!, sessionId: "wrong_session" } },
    { before: { ...pending.before!, objectRevision: "wrong_revision" } },
  ];
  for (const change of changes) {
    const status = f.world.statusForTest(issued.dispatch!, change);
    fails(() => f.ledger.acknowledge(OWNER, initial.id, f.command(issued.attempt), status), "transfer_status_identity_mismatch");
  }
  assert.deepEqual(f.ledger.get(OWNER, initial.id), issued.attempt);
});

test("S54 skipped/backwards/unknown offsets and wrong output session stay pending", async t => {
  const f = fixture(t), initial = await f.object(), issued = f.dispatch(initial, "append", 10);
  f.world.launch(issued.dispatch!); f.world.settle(issued.attempt.pending!.requestId, "applied");
  for (const offset of [0, 9, 11, 30, null]) {
    const status = f.world.statusForTest(issued.dispatch!, { offset });
    assert.throws(() => f.ledger.acknowledge(OWNER, initial.id, f.command(issued.attempt), status), TransferProofError);
  }
  const actual = f.world.status(OWNER, initial.id, issued.attempt.binding, issued.attempt.pending!);
  fails(() => f.ledger.acknowledge(OWNER, initial.id, f.command(issued.attempt), f.world.statusForTest(issued.dispatch!, { settled: false })), "transfer_remote_not_settled");
  for (const field of ["objectId", "sessionId", "objectRevision"] as const) {
    const status = f.world.statusForTest(issued.dispatch!, { object: { ...actual.object!, [field]: field === "objectRevision" ? initial.object!.objectRevision : "wrong_handle" } });
    fails(() => f.ledger.acknowledge(OWNER, initial.id, f.command(issued.attempt), status), "transfer_status_session_mismatch");
  }
  assert.equal(f.ledger.get(OWNER, initial.id).acknowledgedOffset, 0);
});

test("S54 every source/component revision fences a late reply without granting resume", async t => {
  for (const field of ["sourceId", "versionId", "sourceRevision", "componentSetRevision", "componentId", "componentRevision"] as const) {
    const f = fixture(t), initial = f.create(), issued = f.dispatch(initial, "create"), waiting = f.world.launch(issued.dispatch!);
    f.world.changeSource(OWNER, f.binding, field); f.world.settle(issued.attempt.pending!.requestId, "applied"); const receipt = await waiting;
    fails(() => f.ledger.acknowledge(OWNER, initial.id, f.command(issued.attempt), receipt), "transfer_late_response_reconcile_required");
    let record = f.ledger.get(OWNER, initial.id); assert.equal(record.fenceReason, "source_changed");
    record = f.ledger.reconcile(OWNER, record.id, f.command(record)).attempt;
    assert.equal(record.state, "fenced"); assert.equal(record.acknowledgedOffset, 0); assert.ok(record.lastObservation!.object);
  }
});

test("S54 concurrent instances and delayed earlier receipts cannot overwrite newer intent", async t => {
  const f = fixture(t), initial = f.create(), other = f.open(), issued = f.dispatch(initial, "create");
  fails(() => f.dispatch(initial, "create", 0, other), "transfer_revision_conflict");
  const waiting = f.world.launch(issued.dispatch!); f.world.settle(issued.attempt.pending!.requestId, "applied"); const old = await waiting;
  let record = f.ledger.acknowledge(OWNER, initial.id, f.command(issued.attempt), old).attempt;
  const next = f.dispatch(record, "append", 10);
  fails(() => f.ledger.acknowledge(OWNER, record.id, f.command(next.attempt), old), "transfer_status_identity_mismatch");
  assert.deepEqual(f.ledger.get(OWNER, record.id), next.attempt);
  fails(() => other.get("other_owner", record.id), "transfer_not_found");
});

test("S54 cancellation after ambiguous finalize records completed historical output without verification", async t => {
  const f = fixture(t); let record = await f.object(); record = (await f.complete(record, "append", 30)).attempt;
  record = (await f.complete(record, "finalize", 0, "applied", "unknown")).attempt;
  record = f.ledger.cancel(OWNER, record.id, f.command(record)).attempt;
  f.world.reveal(record.pending!.requestId); record = f.ledger.reconcile(OWNER, record.id, f.command(record)).attempt;
  assert.equal(record.state, "cancelled"); assert.equal(record.lastObservation!.finalized, true); assert.equal(record.productionCapability, false);
});

test("S54 failed durable dispatch returns no permit and atomically rolls back intent/idempotency", t => {
  const f = fixture(t), record = f.create(), db = Reflect.get(f.ledger, "db") as DatabaseSync, original = db.prepare.bind(db);
  Reflect.set(db, "prepare", (sql: string) => sql.startsWith("INSERT INTO transfer_proof_events") ? { run() { throw new Error("synthetic disk full"); } } : original(sql));
  try { assert.throws(() => f.dispatch(record, "create"), /synthetic disk full/); } finally { Reflect.set(db, "prepare", original); }
  assert.deepEqual(f.ledger.get(OWNER, record.id), record); assert.deepEqual(f.world.countsForTest(), { requests: 0, objects: 0 });
});

test("S54 failed acknowledgement persistence leaves original request recoverable after reopen", async t => {
  const f = fixture(t), record = f.create(), issued = f.dispatch(record, "create"), waiting = f.world.launch(issued.dispatch!);
  f.world.settle(issued.attempt.pending!.requestId, "applied"); const receipt = await waiting;
  const db = Reflect.get(f.ledger, "db") as DatabaseSync, original = db.prepare.bind(db); let armed = true;
  Reflect.set(db, "prepare", (sql: string) => armed && sql.startsWith("INSERT INTO transfer_proof_operations") ? { run() { armed = false; throw new Error("synthetic completion write failure"); } } : original(sql));
  try { assert.throws(() => f.ledger.acknowledge(OWNER, record.id, f.command(issued.attempt), receipt), /synthetic completion write failure/); } finally { Reflect.set(db, "prepare", original); }
  f.ledger.close(); const reopened = f.open(); let current = reopened.get(OWNER, record.id);
  assert.equal(current.state, "uncertain"); assert.equal(current.object, null);
  current = reopened.reconcile(OWNER, current.id, f.command(current)).attempt;
  assert.ok(current.object); assert.equal(f.world.countsForTest().objects, 1);
});

test("S54 durable records and idempotent replay do not retain mutable caller aliases", t => {
  const f = fixture(t), input = { idempotencyKey: "create_once", binding: structuredClone(f.binding) }, created = f.ledger.create(OWNER, input).attempt;
  input.binding.source.versionId = "mutated"; created.binding.connectionEpoch = "mutated";
  const saved = f.ledger.get(OWNER, created.id); assert.deepEqual(saved.binding, f.binding);
  saved.binding.source.componentRevision = "mutated";
  f.ledger.close(); const reopened = f.open();
  const replay = reopened.create(OWNER, { idempotencyKey: "create_once", binding: f.binding }); assert.equal(replay.replayed, true); assert.deepEqual(replay.attempt.binding, f.binding);
});

test("S54 current-row corruption and missing retained history fail reopen without repair", t => {
  for (const change of ["record", "events", "operations", "everything"] as const) {
    const f = fixture(t), record = f.create(); f.ledger.cancel(OWNER, record.id, f.command(record)); f.ledger.close();
    inspectTransferJournalForTest(f.file, db => {
      if (change === "record") {
        const row = db.prepare("SELECT record_json FROM transfer_proof_attempts").get()!, value = JSON.parse(String(row.record_json)) as TransferAttempt;
        value.stopRequested = false; value.state = "ready"; db.prepare("UPDATE transfer_proof_attempts SET record_json=?").run(canonical(value));
      } else if (change === "everything") db.exec("DELETE FROM transfer_proof_attempts; DELETE FROM transfer_proof_events; DELETE FROM transfer_proof_operations");
      else db.exec(`DELETE FROM transfer_proof_${change} WHERE rowid=1`);
    });
    const bytes = transferJournalBytesForTest(f.file); fails(() => f.open(), "transfer_journal_invalid_retain_for_investigation"); assert.deepEqual(transferJournalBytesForTest(f.file), bytes);
  }
});

test("S54 foreign identity, extra DDL and initialized empty databases are never reused", t => {
  for (const sql of ["CREATE TABLE foreign_data(value); PRAGMA application_id=1398032946", "CREATE TABLE old_data(value); DROP TABLE old_data; VACUUM", "CREATE VIEW foreign_view AS SELECT 1"]) {
    const file = createTransferJournalFile(); t.after(() => disposeTransferJournalFile(file));
    inspectTransferJournalForTest(file, db => db.exec(sql)); const before = transferJournalBytesForTest(file);
    fails(() => new TransferRecoveryLedger(file), "transfer_journal_invalid_retain_for_investigation"); assert.deepEqual(transferJournalBytesForTest(file), before);
  }
});

test("S54 reopen binds dispatched range and lease to original command despite matching current/event tampering", async t => {
  for (const field of ["range", "lease"] as const) {
    const f = fixture(t), initial = await f.object(), issued = f.dispatch(initial, "append", 10); f.ledger.close();
    inspectTransferJournalForTest(f.file, db => {
      const changed = structuredClone(issued.attempt);
      if (field === "range") changed.pending!.toOffset = 20; else changed.pending!.leaseExpiresAt++;
      const text = canonical(changed);
      db.prepare("UPDATE transfer_proof_attempts SET record_json=? WHERE attempt_id=?").run(text, changed.id);
      db.prepare("UPDATE transfer_proof_events SET record_json=? WHERE attempt_id=? AND revision=?").run(text, changed.id, changed.revision);
    });
    const before = transferJournalBytesForTest(f.file);
    fails(() => f.open(), "transfer_journal_invalid_retain_for_investigation");
    assert.deepEqual(transferJournalBytesForTest(f.file), before);
  }
});
