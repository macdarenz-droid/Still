import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import type { TestContext } from "node:test";
import { BackupRecoveryError } from "../../packages/contracts/src/backup-recovery.ts";
import type { RecoveryRecord } from "../../packages/contracts/src/backup-recovery.ts";
import {
  BackupRecoveryJournal, createSyntheticJournalFile, disposeSyntheticJournalFile,
  inspectSyntheticJournalForTest, syntheticJournalBytesForTest,
} from "../../services/backup/recovery-journal.ts";
import { canonical, digest, generatedBytes, SyntheticObjectStore, SyntheticSources } from "../../services/backup/synthetic-store.ts";
import type { TrustedSyntheticSourceVerifier } from "../../services/backup/synthetic-store.ts";

const OWNER = "synthetic_owner", DESTINATION = "synthetic_destination";
function fixture(t: TestContext, sizes = [70_003, 37, 211]) {
  const file = createSyntheticJournalFile(), sourceVerifier = new SyntheticSources(), destination = new SyntheticObjectStore();
  const definitions = sizes.map((byteLength, i) => ({ role: (["primary", "motion", "sidecar"] as const)[i], seed: i + 71, byteLength }));
  const source = sourceVerifier.generate(OWNER, definitions);
  const journals: BackupRecoveryJournal[] = [];
  const open = (verifier: TrustedSyntheticSourceVerifier | undefined = sourceVerifier, store = destination.reopen()) => {
    const journal = new BackupRecoveryJournal(file, { sourceVerifier: verifier, destination: store }); journals.push(journal); return journal;
  };
  const journal = open(); let counter = 0;
  const command = (record: RecoveryRecord, key = `operation_${++counter}`) => ({ expectedRevision: record.revision, idempotencyKey: key });
  const request = (current = journal, key = `request_${++counter}`) => current.request(OWNER, { source, destinationId: DESTINATION, idempotencyKey: key }).record;
  const prepare = (current = journal) => { const record = request(current); return current.prepare(OWNER, record.id, command(record)).record; };
  const upload = (record = prepare(), current = journal) => {
    for (const component of record.manifest!.components) {
      while (record.progress.find(item => item.componentId === component.componentId)!.uploadedBytes < component.byteLength) {
        record = current.transferChunk(OWNER, record.id, { ...command(record), componentId: component.componentId, maxBytes: 32_768 }).record;
      }
    }
    return record;
  };
  const verify = (record = upload(), current = journal) => current.verify(OWNER, record.id, command(record)).record;
  t.after(() => { for (const journal of journals) journal.close(); destination.settleForTest(); destination.disposeForTest(); disposeSyntheticJournalFile(file); });
  return { file, sourceVerifier, destination, source, definitions, journal, open, command, request, prepare, upload, verify };
}
function fails(action: () => unknown, expected: string): void {
  assert.throws(action, error => error instanceof BackupRecoveryError && error.code === expected);
}
function denied(record: RecoveryRecord): void { assert.equal(record.testOnly, true); assert.equal(record.productionCapability, false); }
function failResultCommitOnce(journal: BackupRecoveryJournal): () => void {
  const db = Reflect.get(journal, "db") as DatabaseSync, prepare = db.prepare.bind(db); let armed = true;
  Reflect.set(db, "prepare", (sql: string) => {
    if (armed && sql.startsWith("UPDATE backup_proof_operations SET result_revision")) return { run() { armed = false; throw new Error("synthetic SQL failure after destination effect"); } };
    return prepare(sql);
  });
  return () => { Reflect.set(db, "prepare", prepare); };
}
/** Valid denied-prepare history, bulk inserted to avoid O(n²) setup through public validation. */
function fillEvents(f: ReturnType<typeof fixture>, target: number): void {
  const denied = new BackupRecoveryJournal(f.file), first = f.request(denied);
  const record = denied.prepare(OWNER, first.id, f.command(first)).record; denied.close();
  inspectSyntheticJournalForTest(f.file, db => {
    const count = Number(db.prepare("SELECT event_count FROM backup_proof_meta").get()!.event_count);
    const insertEvent = db.prepare("INSERT INTO backup_proof_events VALUES (?, ?, ?, 'prepared', ?, ?)");
    const insertOperation = db.prepare("INSERT INTO backup_proof_operations VALUES (?, ?, ?, 'prepare', ?, ?, ?)");
    db.exec("BEGIN IMMEDIATE");
    for (let i = count; i < target; i++) {
      const key = `capacity_${i}`, command = { expectedRevision: record.revision, idempotencyKey: key };
      const descriptor = { recoveryId: record.id, action: "prepare", command }, text = canonical(descriptor);
      record.revision++;
      insertEvent.run(OWNER, record.id, record.revision, key, canonical(record));
      insertOperation.run(OWNER, key, record.id, text, digest(text), record.revision);
    }
    db.prepare("UPDATE backup_proof_records SET revision=?, record_json=? WHERE recovery_id=?").run(record.revision, canonical(record), record.id);
    db.prepare("UPDATE backup_proof_meta SET event_count=?, operation_count=operation_count+? WHERE singleton=1").run(target, target - count);
    db.exec("COMMIT");
  });
}

test("S44 default denial and caller JSON cannot mint fixture authority", t => {
  const f = fixture(t), journal = f.open(undefined);
  // Explicitly absent verifier; the fixture helper defaults to its generator, so install none directly.
  const deniedJournal = new BackupRecoveryJournal(":memory:", { destination: f.destination }); t.after(() => deniedJournal.close());
  let record = deniedJournal.request(OWNER, { source: f.source, destinationId: DESTINATION, idempotencyKey: "request" }).record;
  record = deniedJournal.prepare(OWNER, record.id, f.command(record)).record;
  assert.equal(record.state, "failed"); assert.equal(record.failure, "backup_source_authority_unavailable"); denied(record);
  const plainEvidence = structuredClone(f.sourceVerifier.inspect(OWNER, f.source));
  const fake: TrustedSyntheticSourceVerifier = { inspect: () => plainEvidence, capture: () => { throw new Error("must not read"); } };
  const forgedJournal = f.open(fake), forged = f.request(forgedJournal);
  const result = forgedJournal.prepare(OWNER, forged.id, f.command(forged)).record;
  assert.equal(result.state, "failed"); assert.equal(result.failure, "backup_source_authority_denied");
  fails(() => journal.request(OWNER, { source: f.source, destinationId: DESTINATION, idempotencyKey: "forged", evidence: plainEvidence, immutable: true }), "backup_invalid_contract");
});

test("S44 only minted temporary journal locations and generated bytes are admitted", t => {
  const f = fixture(t);
  for (const location of ["/tmp/user-database.sqlite", { scope: "generated_backup_journal" }, { path: "/tmp/user-database.sqlite" }]) {
    fails(() => new BackupRecoveryJournal(location as never), "backup_fixture_location_denied");
  }
  fails(() => new SyntheticObjectStore({ path: "/tmp", unsettled: new Set(), fault: null } as never), "backup_fixture_root_denied");
  const record = f.prepare();
  fails(() => f.destination.append(OWNER, DESTINATION, record.progress[0], { componentId: record.progress[0].componentId, offset: 0, byteLength: 1 } as never, record.manifest!), "backup_fixture_bytes_denied");
  const chunk = f.sourceVerifier.capture(OWNER, f.source, record.progress[0].componentId, 0, 1);
  fails(() => f.destination.append("other_owner", DESTINATION, record.progress[0], chunk, record.manifest!), "backup_fixture_owner_denied");
});

test("S44 source verifier must match every requested identity and revision field", t => {
  const f = fixture(t);
  for (const field of ["sourceId", "versionId", "sourceRevision", "componentSetRevision"] as const) {
    const source = { ...f.source, [field]: "different_revision" };
    let record = f.journal.request(OWNER, { source, destinationId: DESTINATION, idempotencyKey: field }).record;
    record = f.journal.prepare(OWNER, record.id, f.command(record)).record;
    assert.equal(record.state, "failed", field); assert.match(record.failure!, /backup_source_(authority_denied|changed)/);
  }
});

test("S45 complete generated multi-component bytes restore after original input is absent", t => {
  const f = fixture(t), verified = f.verify();
  assert.equal(verified.state, "verified"); denied(verified);
  const exported = f.journal.exportManifest(OWNER, verified.id);
  assert.equal(exported.testOnly, true); assert.equal(exported.productionCapability, false);
  assert.equal("ownerId" in exported.manifest, false);
  const json = JSON.stringify(exported);
  for (const forbidden of ["content://", "https://", "file://", "ownerId", "filename", "credential", "token", "path"]) assert.equal(json.includes(forbidden), false, forbidden);
  f.sourceVerifier.removeForTest(f.source); f.journal.close();
  const reopened = f.open(), record = reopened.get(OWNER, verified.id);
  const restored = reopened.restore(OWNER, record.id, { ...f.command(record), restoreId: "restored_without_source" }).record;
  assert.equal(restored.state, "restore_verified"); denied(restored);
  exported.manifest.components.forEach((component, i) => {
    assert.deepEqual(f.destination.readRestoredForTest(OWNER, "restored_without_source", component.componentId), generatedBytes(f.definitions[i].seed, f.definitions[i].byteLength));
  });
});

test("S45 offsets resume after reopen and duplicate intent replays historical results", t => {
  const f = fixture(t), first = f.request(f.journal, "initial_request");
  let record = f.journal.prepare(OWNER, first.id, f.command(first)).record;
  const transfer = { ...f.command(record, "first_chunk"), componentId: record.progress[0].componentId, maxBytes: 10_000 };
  record = f.journal.transferChunk(OWNER, record.id, transfer).record;
  assert.equal(record.progress[0].uploadedBytes, 10_000);
  f.journal.close(); const reopened = f.open();
  const replay = reopened.transferChunk(OWNER, record.id, transfer);
  assert.equal(replay.replayed, true); assert.deepEqual(replay.record, record);
  record = f.upload(reopened.get(OWNER, record.id), reopened);
  assert.equal(record.state, "uploaded_unverified");
  const historic = reopened.request(OWNER, { source: f.source, destinationId: DESTINATION, idempotencyKey: "initial_request" });
  assert.equal(historic.replayed, true); assert.equal(historic.record.state, "requested");
  assert.equal(reopened.get(OWNER, record.id).state, "uploaded_unverified");
  fails(() => reopened.transferChunk(OWNER, record.id, { ...transfer, maxBytes: 9999 }), "backup_idempotency_conflict");
});

test("S44 uploaded unverified never exports or restores and stale revision cannot verify", t => {
  const f = fixture(t), record = f.upload();
  fails(() => f.journal.exportManifest(OWNER, record.id), "backup_not_verified");
  fails(() => f.journal.restore(OWNER, record.id, { ...f.command(record), restoreId: "forbidden" }), "backup_transition_denied");
  fails(() => f.journal.verify(OWNER, record.id, { ...f.command(record), expectedRevision: record.revision - 1 }), "backup_revision_conflict");
  assert.equal(f.journal.get(OWNER, record.id).state, "uploaded_unverified");
});

test("S44 owner isolation covers journal lookup, commands and destination objects", t => {
  const f = fixture(t), record = f.upload();
  fails(() => f.journal.get("other_owner", record.id), "backup_not_found");
  fails(() => f.journal.prepare("other_owner", record.id, f.command(record)), "backup_not_found");
  fails(() => f.journal.verify("other_owner", record.id, f.command(record)), "backup_not_found");
  assert.equal(f.destination.inspect("other_owner", DESTINATION, record.progress[0].objectId), null);
});

test("S45 ambiguous committed chunk blocks blind retry and exact readback adopts it once", t => {
  const f = fixture(t), initial = f.prepare(); f.destination.injectFailureForTest("after_object_commit");
  const command = { ...f.command(initial, "ambiguous_chunk"), componentId: initial.progress[0].componentId, maxBytes: 10_000 };
  let record = f.journal.transferChunk(OWNER, initial.id, command).record;
  assert.equal(record.state, "uncertain"); assert.equal(record.progress[0].uploadedBytes, 0);
  fails(() => f.journal.transferChunk(OWNER, record.id, { ...f.command(record), componentId: command.componentId, maxBytes: 10_000 }), "backup_transition_denied");
  record = f.journal.reconcile(OWNER, record.id, f.command(record)).record;
  assert.equal(record.state, "uploading"); assert.equal(record.progress[0].uploadedBytes, 10_000); assert.equal(record.progress[0].objectRevision, 1);
  const replay = f.journal.transferChunk(OWNER, record.id, command);
  assert.equal(replay.replayed, true); assert.equal(replay.record.state, "uncertain");
  assert.equal(f.journal.get(OWNER, record.id).progress[0].objectRevision, 1);
});

test("S45 disk-full no-change reconciliation permits an explicit safe resumed chunk", t => {
  const f = fixture(t), initial = f.prepare(); f.destination.injectFailureForTest("disk_full");
  let record = f.journal.transferChunk(OWNER, initial.id, { ...f.command(initial), componentId: initial.progress[0].componentId, maxBytes: 10 }).record;
  assert.equal(record.state, "uncertain"); assert.equal(record.failure, "backup_disk_full");
  record = f.journal.reconcile(OWNER, record.id, f.command(record)).record;
  assert.equal(record.state, "preparing");
  record = f.journal.transferChunk(OWNER, record.id, { ...f.command(record), componentId: record.progress[0].componentId, maxBytes: 10 }).record;
  assert.equal(record.progress[0].uploadedBytes, 10); assert.equal(record.progress[0].objectRevision, 1);
});

test("S45 interrupted replacement retains higher-revision and metadata-temp uncertainty after reopen", t => {
  const f = fixture(t), initial = f.prepare();
  let record = f.journal.transferChunk(OWNER, initial.id, { ...f.command(initial), componentId: initial.progress[0].componentId, maxBytes: 10 }).record;
  f.destination.injectFailureForTest("before_metadata_replace");
  record = f.journal.transferChunk(OWNER, record.id, { ...f.command(record), componentId: record.progress[0].componentId, maxBytes: 10 }).record;
  assert.equal(record.state, "uncertain"); assert.equal(record.progress[0].uploadedBytes, 10);
  f.journal.close(); const reopened = f.open();
  record = reopened.reconcile(OWNER, record.id, f.command(record)).record;
  assert.equal(record.state, "uncertain"); assert.equal(record.failure, "backup_outcome_unknown");
  assert.equal(record.progress[0].uploadedBytes, 10); assert.equal(record.progress[0].objectRevision, 1);
  fails(() => reopened.transferChunk(OWNER, record.id, { ...f.command(record), componentId: record.progress[0].componentId, maxBytes: 10 }), "backup_transition_denied");
});

test("S45 failed write close retains actual handle and uncommitted file uncertainty", t => {
  const f = fixture(t), initial = f.prepare(); f.destination.injectFailureForTest("write_close");
  let record = f.journal.transferChunk(OWNER, initial.id, { ...f.command(initial), componentId: initial.progress[0].componentId, maxBytes: 10 }).record;
  assert.equal(record.state, "uncertain"); assert.equal(record.failure, "backup_close_failed"); assert.equal(f.destination.unsettledHandlesForTest(), 1);
  record = f.journal.reconcile(OWNER, record.id, f.command(record)).record;
  assert.equal(record.state, "uncertain"); assert.equal(record.failure, "backup_close_unsettled");
  f.destination.settleForTest();
  record = f.journal.reconcile(OWNER, record.id, f.command(record)).record;
  assert.equal(record.state, "uncertain"); assert.equal(record.failure, "backup_outcome_unknown");
  fails(() => f.journal.transferChunk(OWNER, record.id, { ...f.command(record), componentId: record.progress[0].componentId, maxBytes: 10 }), "backup_transition_denied");
});

test("S45 readback close failure cannot verify until the actual handle settles", t => {
  const f = fixture(t), initial = f.upload(); f.destination.injectFailureForTest("read_close");
  let record = f.journal.verify(OWNER, initial.id, f.command(initial)).record;
  assert.equal(record.state, "uncertain"); assert.equal(f.destination.unsettledHandlesForTest(), 1);
  record = f.journal.reconcile(OWNER, record.id, f.command(record)).record;
  assert.equal(record.state, "uncertain"); f.destination.settleForTest();
  record = f.journal.reconcile(OWNER, record.id, f.command(record)).record;
  assert.equal(record.state, "verified");
});

test("S45 failed source close prevents destination admission and requires settlement", t => {
  const f = fixture(t), record = f.prepare(); f.sourceVerifier.failNextCloseForTest();
  const command = { ...f.command(record), componentId: record.progress[0].componentId, maxBytes: 10 };
  fails(() => f.journal.transferChunk(OWNER, record.id, command), "backup_close_failed");
  assert.equal(f.journal.get(OWNER, record.id).revision, record.revision);
  assert.equal(f.destination.inspect(OWNER, DESTINATION, record.progress[0].objectId), null);
  fails(() => f.journal.transferChunk(OWNER, record.id, command), "backup_close_unsettled");
  f.sourceVerifier.settleForTest(); assert.equal(f.journal.transferChunk(OWNER, record.id, command).record.progress[0].uploadedBytes, 10);
});

test("S44 changed source, component-set or component revision cannot reuse prepared evidence", t => {
  for (const change of ["source", "components", "component"] as const) {
    const f = fixture(t), record = f.prepare(); f.sourceVerifier.changeForTest(f.source, change);
    fails(() => f.journal.transferChunk(OWNER, record.id, { ...f.command(record), componentId: record.progress[0].componentId, maxBytes: 10 }), change === "component" ? "backup_component_changed" : "backup_source_changed");
    assert.equal(f.journal.get(OWNER, record.id).state, "preparing");
  }
});

test("S44 whole-transfer source recheck retains uncertain outcome if source changes during append", t => {
  const f = fixture(t), record = f.prepare(); const append = f.destination.append.bind(f.destination);
  f.destination.append = (...args) => { const object = append(...args); f.sourceVerifier.changeForTest(f.source, "component"); return object; };
  // Use the same trusted port instance so the injected source change occurs after the real write.
  const journal = new BackupRecoveryJournal(f.file, { sourceVerifier: f.sourceVerifier, destination: f.destination }); t.after(() => journal.close());
  const uncertain = journal.transferChunk(OWNER, record.id, { ...f.command(record), componentId: record.progress[0].componentId, maxBytes: 10 }).record;
  assert.equal(uncertain.state, "uncertain"); assert.equal(uncertain.failure, "backup_component_changed");
  const still = journal.reconcile(OWNER, record.id, f.command(uncertain)).record;
  assert.equal(still.state, "uncertain"); assert.equal(still.progress[0].uploadedBytes, 0);
  journal.close();
});

test("S45 corrupt, truncated, missing and wrong-identity/revision components fail verification", t => {
  for (const damage of ["corrupt", "truncate", "missing", "revision", "identity"] as const) {
    const f = fixture(t), record = f.upload();
    f.destination.damageForTest(OWNER, DESTINATION, record.progress[1].objectId, damage);
    const result = f.journal.verify(OWNER, record.id, f.command(record)).record;
    assert.equal(result.state, "uncertain", damage);
    fails(() => f.journal.restore(OWNER, record.id, { ...f.command(result), restoreId: "must_not_restore" }), "backup_transition_denied");
    assert.equal(f.journal.reconcile(OWNER, record.id, f.command(result)).record.state, "uncertain");
  }
});

test("S45 old verified status cannot bypass current object validation on export or restore", t => {
  const f = fixture(t), record = f.verify();
  f.destination.damageForTest(OWNER, DESTINATION, record.progress[0].objectId, "corrupt");
  fails(() => f.journal.exportManifest(OWNER, record.id), "backup_object_corrupt");
  const result = f.journal.restore(OWNER, record.id, { ...f.command(record), restoreId: "invalid_archive" }).record;
  assert.equal(result.state, "uncertain"); assert.equal(result.failure, "backup_object_corrupt");
});

test("S45 destination collision never overwrites a previous restored component set", t => {
  const f = fixture(t), first = f.verify();
  const restored = f.journal.restore(OWNER, first.id, { ...f.command(first), restoreId: "same_target" }).record;
  const before = f.destination.readRestoredForTest(OWNER, "same_target", first.manifest!.components[0].componentId);
  const second = f.verify();
  const collision = f.journal.restore(OWNER, second.id, { ...f.command(second), restoreId: "same_target" }).record;
  assert.equal(restored.state, "restore_verified"); assert.equal(collision.state, "uncertain"); assert.equal(collision.failure, "backup_restore_collision");
  assert.deepEqual(f.destination.readRestoredForTest(OWNER, "same_target", first.manifest!.components[0].componentId), before);
  assert.equal(f.journal.reconcile(OWNER, second.id, f.command(collision)).record.state, "uncertain");
});

test("S45 restore disk-full distinguishes no output from verified restoration", t => {
  const f = fixture(t), initial = f.verify(); f.destination.injectFailureForTest("disk_full");
  let record = f.journal.restore(OWNER, initial.id, { ...f.command(initial), restoreId: "retry_target" }).record;
  assert.equal(record.state, "uncertain");
  record = f.journal.reconcile(OWNER, record.id, f.command(record)).record;
  assert.equal(record.state, "verified"); assert.equal(record.restoreId, null);
  record = f.journal.restore(OWNER, record.id, { ...f.command(record), restoreId: "retry_target" }).record;
  assert.equal(record.state, "restore_verified");
});

test("S45 ambiguous completed restore is adopted by independent readback, not overwritten", t => {
  const f = fixture(t), initial = f.verify(); f.sourceVerifier.removeForTest(f.source); f.destination.injectFailureForTest("after_restore_commit");
  let record = f.journal.restore(OWNER, initial.id, { ...f.command(initial), restoreId: "ambiguous_restore" }).record;
  assert.equal(record.state, "uncertain"); assert.equal(record.restoreId, null);
  record = f.journal.reconcile(OWNER, record.id, f.command(record)).record;
  assert.equal(record.state, "restore_verified"); assert.equal(record.restoreId, "ambiguous_restore");
});

test("S45 live cross-instance callbacks cannot supersede a still-running physical restore", t => {
  const f = fixture(t), initial = f.verify(), other = f.open(), physicalRestore = f.destination.restore.bind(f.destination);
  const observed: string[] = []; let physicalCalls = 0;
  f.destination.restore = (...args) => {
    physicalCalls++;
    if (physicalCalls === 1) {
      for (const action of [
        () => other.get(OWNER, initial.id),
        () => other.reconcile(OWNER, initial.id, { ...f.command(initial), expectedRevision: initial.revision + 1 }),
        () => other.restore(OWNER, initial.id, { ...f.command(initial), restoreId: "untracked_second_restore" }),
      ]) {
        try { action(); observed.push("unexpected_success"); }
        catch (error) { observed.push(error instanceof BackupRecoveryError ? error.code : "unexpected_error"); }
      }
    }
    physicalRestore(...args);
  };
  const journal = new BackupRecoveryJournal(f.file, { sourceVerifier: f.sourceVerifier, destination: f.destination });
  let result: RecoveryRecord | undefined, error: unknown;
  try { result = journal.restore(OWNER, initial.id, { ...f.command(initial), restoreId: "tracked_first_restore" }).record; }
  catch (failure) { error = failure; }
  journal.close();
  assert.equal(error, undefined); assert.equal(result!.state, "restore_verified");
  assert.deepEqual(observed, ["backup_operation_live", "backup_operation_live", "backup_operation_live"]);
  assert.equal(physicalCalls, 1);
  assert.equal(f.destination.verifyRestore(OWNER, "tracked_first_restore", initial.manifest!), "complete");
  assert.equal(f.destination.verifyRestore(OWNER, "untracked_second_restore", initial.manifest!), "absent");
});

test("S45 portable verification binds full generated manifest identity and rejects production-shaped metadata", t => {
  const f = fixture(t), initial = f.verify(), portable = f.journal.exportManifest(OWNER, initial.id);
  f.journal.restore(OWNER, initial.id, { ...f.command(initial), restoreId: "bound_restore" });
  for (const field of ["sourceId", "versionId", "sourceRevision", "componentSetRevision"] as const) {
    const changed = structuredClone(portable); changed.manifest.source[field] = "transplanted_identity";
    fails(() => f.destination.verify(OWNER, changed), "backup_manifest_changed");
    fails(() => f.destination.verifyRestore(OWNER, "bound_restore", changed.manifest), "backup_restore_identity_changed");
  }
  const renamed = structuredClone(portable); renamed.manifest.manifestId = "different_manifest";
  fails(() => f.destination.verify(OWNER, renamed), "backup_manifest_changed");
  for (const [field, value] of Object.entries({ representation: "original_bytes", fidelity: "immutable_original", testOnly: false, scope: "production_backup_v1" })) {
    const changed = structuredClone(portable); Reflect.set(changed.manifest, field, value);
    fails(() => f.destination.verify(OWNER, changed), "backup_invalid_contract");
    fails(() => f.destination.verifyRestore(OWNER, "bound_restore", changed.manifest), "backup_invalid_contract");
  }
  const extra = { ...portable, credentials: "untrusted" };
  fails(() => f.destination.restore(OWNER, "forged_archive", extra), "backup_invalid_contract");
});

test("S45 capacity at 9996 preserves recovery while 9997/9998/9999 deny before capture", t => {
  for (const count of [9996, 9997, 9998, 9999]) {
    const f = fixture(t), initial = f.prepare(); fillEvents(f, count);
    let captures = 0, writes = 0;
    const capture = f.sourceVerifier.capture.bind(f.sourceVerifier), append = f.destination.append.bind(f.destination);
    f.sourceVerifier.capture = (...args) => { captures++; return capture(...args); };
    f.destination.append = (...args) => { writes++; return append(...args); };
    const journal = new BackupRecoveryJournal(f.file, { sourceVerifier: f.sourceVerifier, destination: f.destination });
    try {
      if (count === 9996) {
        f.destination.injectFailureForTest("after_object_commit");
        let record = journal.transferChunk(OWNER, initial.id, { ...f.command(initial), componentId: initial.progress[0].componentId, maxBytes: 10 }).record;
        assert.equal(record.state, "uncertain");
        f.destination.injectFailureForTest("read_close");
        record = journal.reconcile(OWNER, record.id, f.command(record)).record;
        assert.equal(record.state, "uncertain"); assert.equal(record.failure, "backup_close_failed");
        // Other commands cannot consume reserved recovery capacity.
        fails(() => f.request(journal), "backup_history_full_retain_journal");
        fails(() => journal.reconcile(OWNER, record.id, f.command(record)), "backup_history_full_retain_journal");
        assert.deepEqual(journal.get(OWNER, record.id), record);
        f.destination.settleForTest();
        record = journal.reconcile(OWNER, record.id, f.command(record)).record;
        assert.equal(record.progress[0].uploadedBytes, 10); assert.equal(record.state, "uploading");
        assert.equal(captures, 1); assert.equal(writes, 1);
      } else {
        let error: unknown;
        try { journal.transferChunk(OWNER, initial.id, { ...f.command(initial), componentId: initial.progress[0].componentId, maxBytes: 10 }); } catch (failure) { error = failure; }
        assert.equal(captures, 0, `${count} events must reject before source capture`);
        assert.equal(writes, 0, `${count} events must reject before destination effects`);
        assert.equal((error as BackupRecoveryError).code, "backup_history_full_retain_journal");
        assert.deepEqual(journal.get(OWNER, initial.id), initial);
      }
    } finally { journal.close(); }
  }
});

test("S45 committed effect plus failed journal completion reopens as uncertain and reconciles", t => {
  const f = fixture(t), initial = f.prepare(), restore = failResultCommitOnce(f.journal);
  try { assert.throws(() => f.journal.transferChunk(OWNER, initial.id, { ...f.command(initial, "interrupted"), componentId: initial.progress[0].componentId, maxBytes: 10 }), /synthetic SQL failure/); }
  finally { restore(); }
  f.journal.close(); const reopened = f.open();
  let record = reopened.get(OWNER, initial.id);
  assert.equal(record.state, "uncertain"); assert.equal(record.failure, "backup_interrupted_reconcile_required");
  assert.equal(record.progress[0].uploadedBytes, 0);
  record = reopened.reconcile(OWNER, record.id, f.command(record)).record;
  assert.equal(record.progress[0].uploadedBytes, 10); assert.equal(record.progress[0].objectRevision, 1);
});

test("S45 intent transaction rollback publishes no object or partial operational history", t => {
  const f = fixture(t), initial = f.prepare(), db = Reflect.get(f.journal, "db") as DatabaseSync, original = db.prepare.bind(db);
  Reflect.set(db, "prepare", (sql: string) => sql.startsWith("INSERT INTO backup_proof_events") ? { run() { throw new Error("intent insert failure"); } } : original(sql));
  try { assert.throws(() => f.journal.transferChunk(OWNER, initial.id, { ...f.command(initial, "rollback"), componentId: initial.progress[0].componentId, maxBytes: 10 }), /intent insert failure/); }
  finally { Reflect.set(db, "prepare", original); }
  assert.deepEqual(f.journal.get(OWNER, initial.id), initial);
  assert.equal(f.destination.inspect(OWNER, DESTINATION, initial.progress[0].objectId), null);
  inspectSyntheticJournalForTest(f.file, db => assert.equal(db.prepare("SELECT count(*) AS n FROM backup_proof_operations WHERE operation_key='rollback'").get()!.n, 0));
});

test("S44 competing instances cannot both consume the same expected revision", t => {
  const f = fixture(t), record = f.request(), other = f.open();
  const winner = f.journal.prepare(OWNER, record.id, f.command(record)).record;
  fails(() => other.prepare(OWNER, record.id, f.command(record)), "backup_revision_conflict");
  assert.deepEqual(other.get(OWNER, record.id), winner);
  const first = f.journal.transferChunk(OWNER, winner.id, { ...f.command(winner), componentId: winner.progress[0].componentId, maxBytes: 1 }).record;
  fails(() => other.transferChunk(OWNER, winner.id, { ...f.command(winner), componentId: winner.progress[0].componentId, maxBytes: 1 }), "backup_revision_conflict");
  assert.deepEqual(other.get(OWNER, record.id), first);
});

test("S44 returned records, commands and exported manifests cannot mutate durable state", t => {
  const f = fixture(t), record = f.verify(), expected = structuredClone(record), exported = f.journal.exportManifest(OWNER, record.id);
  record.source.versionId = "changed"; record.progress[0].uploadedBytes = 0; record.manifest!.components.length = 0;
  exported.manifest.components[0].sha256 = "0".repeat(64); exported.objects[0].objectId = "changed";
  assert.deepEqual(f.journal.get(OWNER, expected.id), expected);
  assert.notEqual(f.journal.exportManifest(OWNER, expected.id).manifest.components[0].sha256, "0".repeat(64));
});

test("S45 semantic current-row tampering rejects reopen before any writable pragma", t => {
  const f = fixture(t), record = f.verify(); f.journal.close();
  inspectSyntheticJournalForTest(f.file, db => {
    const row = db.prepare("SELECT record_json FROM backup_proof_records WHERE recovery_id=?").get(record.id)!;
    const changed = JSON.parse(String(row.record_json)) as RecoveryRecord; changed.state = "restore_verified"; changed.restoreId = "invented_restore";
    db.prepare("UPDATE backup_proof_records SET record_json=? WHERE recovery_id=?").run(canonical(changed), record.id);
  });
  const before = syntheticJournalBytesForTest(f.file);
  fails(() => f.open(), "backup_journal_invalid_retain_for_investigation");
  assert.deepEqual(syntheticJournalBytesForTest(f.file), before);
});

test("S45 missing operation, missing revision event and forged result revision reject reopen", t => {
  for (const mutation of ["DELETE FROM backup_proof_operations WHERE action='request'", "DELETE FROM backup_proof_events WHERE revision=1", "UPDATE backup_proof_operations SET result_revision=999 WHERE action='request'", "DELETE FROM backup_proof_records; DELETE FROM backup_proof_events; DELETE FROM backup_proof_operations"]) {
    const f = fixture(t); f.prepare(); f.journal.close();
    inspectSyntheticJournalForTest(f.file, db => db.exec(mutation));
    const before = syntheticJournalBytesForTest(f.file); fails(() => f.open(), "backup_journal_invalid_retain_for_investigation");
    assert.deepEqual(syntheticJournalBytesForTest(f.file), before);
  }
});

test("S45 foreign schema, dropped initialized database and extra trigger are never initialized or repaired", t => {
  for (const sql of ["CREATE TABLE foreign_data(value); PRAGMA application_id=42", "CREATE TABLE prior(value); DROP TABLE prior; VACUUM", "CREATE VIEW foreign_view AS SELECT 1"]) {
    const file = createSyntheticJournalFile(); t.after(() => disposeSyntheticJournalFile(file));
    inspectSyntheticJournalForTest(file, db => db.exec(sql));
    const before = syntheticJournalBytesForTest(file);
    fails(() => new BackupRecoveryJournal(file), "backup_journal_invalid_retain_for_investigation");
    assert.deepEqual(syntheticJournalBytesForTest(file), before);
  }
  const f = fixture(t); f.request(); f.journal.close();
  inspectSyntheticJournalForTest(f.file, db => db.exec("CREATE TRIGGER foreign_trigger AFTER INSERT ON backup_proof_records BEGIN SELECT 1; END"));
  const before = syntheticJournalBytesForTest(f.file); fails(() => f.open(), "backup_journal_invalid_retain_for_investigation");
  assert.deepEqual(syntheticJournalBytesForTest(f.file), before);
});
