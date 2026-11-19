import { randomUUID } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { DatabaseSync } from "node:sqlite";
import {
  BACKUP_PROOF_SCOPE, BACKUP_STATES, BackupRecoveryError, backupAssert, backupDigest,
  backupId, backupInteger, backupObject, backupSource, parseBackupRevision,
  parseRequestBackup, parseRestoreBackup, parseSyntheticEvidence, parseTransferBackup,
} from "../../packages/contracts/src/backup-recovery.ts";
import type {
  BackupAction, BackupCommandResult, BackupManifest, BackupObject, BackupSourceRef,
  ComponentProgress, PendingBackupAction, PortableBackupArchive, RecoveryRecord,
  SyntheticInputEvidence,
} from "../../packages/contracts/src/backup-recovery.ts";
import {
  canonical, digest, EMPTY_SHA256, isGeneratedChunk, isGeneratedEvidence, SyntheticObjectStore,
} from "./synthetic-store.ts";
import type { SyntheticChunk, TrustedSyntheticSourceVerifier } from "./synthetic-store.ts";

export interface BackupRecoveryOptions {
  /** Internal fixture installation only. Absent by default; JSON cannot install trust. */
  sourceVerifier?: TrustedSyntheticSourceVerifier;
  destination?: SyntheticObjectStore;
}
export interface SyntheticJournalFile { readonly scope: "generated_backup_journal" }
const journalFiles = new WeakMap<object, { directory: string; path: string; open: number }>();
const liveOperations = new WeakMap<object, Set<string>>();
export function createSyntheticJournalFile(): SyntheticJournalFile {
  const directory = mkdtempSync(join(tmpdir(), "still-synthetic-journal-"));
  const token: SyntheticJournalFile = Object.freeze({ scope: "generated_backup_journal" });
  journalFiles.set(token, { directory, path: join(directory, "recovery.sqlite"), open: 0 });
  return token;
}
function fixtureLocation(value: unknown): { directory: string; path: string; open: number } {
  backupAssert(value !== null && typeof value === "object" && journalFiles.has(value), "backup_fixture_location_denied");
  return journalFiles.get(value)!;
}
export function inspectSyntheticJournalForTest<T>(file: SyntheticJournalFile, inspect: (db: DatabaseSync) => T): T {
  const db = new DatabaseSync(fixtureLocation(file).path);
  try { return inspect(db); } finally { db.close(); }
}
export function syntheticJournalBytesForTest(file: SyntheticJournalFile): Uint8Array { return readFileSync(fixtureLocation(file).path); }
export function disposeSyntheticJournalFile(file: SyntheticJournalFile): void {
  const location = fixtureLocation(file); backupAssert(location.open === 0 && !liveOperations.get(location)?.size, "backup_fixture_journal_busy");
  rmSync(location.directory, { recursive: true }); journalFiles.delete(file);
}
type Descriptor = { recoveryId: string | null; action: BackupAction; command: Record<string, unknown> };
type OperationRow = { owner_id: string; operation_key: string; recovery_id: string; action: BackupAction; command_json: string; request_hash: string; result_revision: number | null };
type EventRow = { owner_id: string; recovery_id: string; revision: number; kind: string; operation_key: string; record_json: string };
type RecordRow = { owner_id: string; recovery_id: string; revision: number; record_json: string };
const APPLICATION_ID = 1398032946;
const migration = readFileSync(new URL("../../db/backup/001_recovery.sql", import.meta.url), "utf8");
backupAssert(migration.trim().length > 0);
const identityError = "backup_journal_invalid_retain_for_investigation";
function ensure(value: unknown): asserts value { backupAssert(value, identityError); }
function clone<T>(value: T): T { return structuredClone(value); }
function same(a: unknown, b: unknown): boolean { return canonical(a) === canonical(b); }
function next(revision: number): number { backupAssert(revision < Number.MAX_SAFE_INTEGER, "backup_revision_exhausted"); return revision + 1; }
function reservations(record: RecoveryRecord | null): { events: number; operations: number } { return { events: record?.pending ? record.state === "uncertain" ? 1 : 2 : 0, operations: record?.pending ? 1 : 0 }; }
function code(error: unknown): string { return error instanceof BackupRecoveryError ? error.code : "backup_io_failure"; }
function objects(db: DatabaseSync): unknown[] { return db.prepare("SELECT type, name, tbl_name, sql FROM sqlite_schema WHERE substr(name, 1, 7) != 'sqlite_' ORDER BY type, name").all(); }
const reference = new DatabaseSync(":memory:"); reference.exec(migration);
const expectedSchema = canonical(objects(reference)); reference.close();

function progress(value: unknown): ComponentProgress {
  const data = backupObject(value, ["componentId", "objectId", "objectRevision", "uploadedBytes", "prefixSha256"]);
  const result = { componentId: backupId(data.componentId), objectId: backupId(data.objectId), objectRevision: data.objectRevision === null ? null : backupInteger(data.objectRevision, 1), uploadedBytes: backupInteger(data.uploadedBytes, 0, 1_048_576), prefixSha256: backupDigest(data.prefixSha256) };
  ensure(result.objectRevision === null ? result.uploadedBytes === 0 && result.prefixSha256 === EMPTY_SHA256 : result.uploadedBytes > 0);
  return result;
}
function parseManifest(value: unknown, ownerId: string): BackupManifest {
  const data = backupObject(value, ["schemaVersion", "scope", "testOnly", "manifestId", "source", "representation", "fidelity", "components"]);
  ensure(data.scope === BACKUP_PROOF_SCOPE);
  const evidence = parseSyntheticEvidence({ schemaVersion: data.schemaVersion, testOnly: data.testOnly, ownerId, source: data.source, representation: data.representation, fidelity: data.fidelity, components: data.components });
  const { ownerId: omitted, ...portable } = evidence;
  return { ...portable, scope: BACKUP_PROOF_SCOPE, manifestId: backupId(data.manifestId) };
}
function parsePending(value: unknown): PendingBackupAction {
  const data = backupObject(value, ["action", "operationKey", "componentId", "before", "after", "restoreId"]);
  ensure(data.action === "transfer" || data.action === "verify" || data.action === "restore");
  const result: PendingBackupAction = { action: data.action, operationKey: backupId(data.operationKey), componentId: data.componentId === null ? null : backupId(data.componentId), before: data.before === null ? null : progress(data.before), after: data.after === null ? null : progress(data.after), restoreId: data.restoreId === null ? null : backupId(data.restoreId) };
  if (result.action === "transfer") {
    ensure(result.componentId && result.before && result.after && !result.restoreId);
    ensure(result.before.componentId === result.componentId && result.after.componentId === result.componentId && result.before.objectId === result.after.objectId && result.after.objectRevision === (result.before.objectRevision ?? 0) + 1 && result.after.uploadedBytes > result.before.uploadedBytes && result.after.uploadedBytes - result.before.uploadedBytes <= 65_536);
  } else ensure(result.componentId === null && result.before === null && result.after === null && (result.action === "restore" ? result.restoreId !== null : result.restoreId === null));
  return result;
}
function parseRecord(value: unknown): RecoveryRecord {
  const data = backupObject(value, ["schemaVersion", "scope", "testOnly", "productionCapability", "ownerId", "id", "revision", "source", "destinationId", "state", "manifest", "progress", "pending", "restoreId", "failure"]);
  ensure(data.schemaVersion === 1 && data.scope === BACKUP_PROOF_SCOPE && data.testOnly === true && data.productionCapability === false && BACKUP_STATES.includes(data.state as never));
  const ownerId = backupId(data.ownerId);
  ensure(Array.isArray(data.progress) && data.progress.length <= 8);
  const record: RecoveryRecord = {
    schemaVersion: 1, scope: BACKUP_PROOF_SCOPE, testOnly: true, productionCapability: false,
    ownerId, id: backupId(data.id), revision: backupInteger(data.revision, 1), source: backupSource(data.source), destinationId: backupId(data.destinationId), state: data.state as RecoveryRecord["state"],
    manifest: data.manifest === null ? null : parseManifest(data.manifest, ownerId), progress: Array.from(data.progress).map(progress), pending: data.pending === null ? null : parsePending(data.pending), restoreId: data.restoreId === null ? null : backupId(data.restoreId), failure: data.failure === null ? null : String(data.failure),
  };
  ensure(record.failure === null || /^backup_[a-z_]{1,96}$/.test(record.failure));
  if (!record.manifest) ensure(record.progress.length === 0 && record.pending === null && record.restoreId === null && (record.state === "requested" || record.state === "failed"));
  else {
    ensure(same(record.manifest.source, record.source) && record.progress.length === record.manifest.components.length);
    ensure(new Set(record.progress.map(item => item.objectId)).size === record.progress.length);
    record.manifest.components.forEach((component, i) => {
      const item = record.progress[i]; ensure(item.componentId === component.componentId && item.uploadedBytes <= component.byteLength);
      if (item.uploadedBytes === component.byteLength) ensure(item.prefixSha256 === component.sha256);
    });
    if (record.pending?.action === "transfer") {
      ensure(record.progress.some(item => same(item, record.pending!.before)));
      const component = record.manifest.components.find(item => item.componentId === record.pending!.componentId);
      ensure(component && record.pending.after!.uploadedBytes <= component.byteLength);
      if (record.pending.after!.uploadedBytes === component.byteLength) ensure(record.pending.after!.prefixSha256 === component.sha256);
    }
  }
  const complete = !!record.manifest && record.progress.every((item, i) => item.uploadedBytes === record.manifest!.components[i].byteLength);
  if (record.state === "requested") ensure(record.manifest === null && record.failure === null);
  if (record.state === "preparing") ensure(record.manifest && record.progress.every(item => item.uploadedBytes === 0) && !record.pending && !record.failure);
  if (record.state === "uploading") ensure(record.manifest && !complete && (!record.pending || record.pending.action === "transfer") && !record.failure);
  if (record.state === "uploaded_unverified") ensure(complete && (!record.pending || record.pending.action === "verify") && !record.failure);
  if (record.state === "verified") ensure(complete && !record.pending && !record.failure && !record.restoreId);
  if (record.state === "restoring") ensure(complete && record.pending?.action === "restore" && !record.failure);
  if (record.state === "restore_verified") ensure(complete && !record.pending && !record.failure && record.restoreId);
  if (record.state === "uncertain") ensure(record.manifest && record.pending && record.failure);
  if (record.state === "failed") ensure(!record.pending && record.failure);
  ensure(record.state === "restore_verified" || record.restoreId === null);
  return record;
}
function decoded(text: string): RecoveryRecord { ensure(text.length <= 262_144); return parseRecord(JSON.parse(text)); }
function archive(record: RecoveryRecord): PortableBackupArchive {
  backupAssert(record.manifest && record.progress.every(item => item.objectRevision !== null), "backup_not_uploaded");
  return { schemaVersion: 1, scope: BACKUP_PROOF_SCOPE, testOnly: true, productionCapability: false, destinationId: record.destinationId, manifest: clone(record.manifest), objects: record.progress.map(item => ({ componentId: item.componentId, objectId: item.objectId, objectRevision: item.objectRevision!, byteLength: item.uploadedBytes, sha256: item.prefixSha256 })) };
}
function matchObject(object: BackupObject | null, expected: ComponentProgress): boolean {
  return expected.objectRevision === null ? object === null : !!object && object.componentId === expected.componentId && object.objectId === expected.objectId && object.objectRevision === expected.objectRevision && object.byteLength === expected.uploadedBytes && object.sha256 === expected.prefixSha256;
}
function transferState(record: RecoveryRecord): RecoveryRecord["state"] {
  return record.progress.every((item, i) => item.uploadedBytes === record.manifest!.components[i].byteLength) ? "uploaded_unverified" : record.progress.some(item => item.uploadedBytes > 0) ? "uploading" : "preparing";
}

/** Internal synchronous generated-byte proof. Never imported by API/native bootstrap. */
export class BackupRecoveryJournal {
  private readonly db: DatabaseSync;
  private readonly verifier: TrustedSyntheticSourceVerifier | undefined;
  private readonly store: SyntheticObjectStore | undefined;
  private readonly location: { directory: string; path: string; open: number } | undefined;
  private readonly liveScope: object;
  private inFlight = 0;
  private closed = false;
  constructor(file: ":memory:" | SyntheticJournalFile, options: BackupRecoveryOptions = {}) {
    this.location = file === ":memory:" ? undefined : fixtureLocation(file);
    this.liveScope = this.location ?? {};
    const path = this.location?.path ?? ":memory:";
    this.verifier = options.sourceVerifier; this.store = options.destination;
    if (path !== ":memory:" && existsSync(path)) {
      const preflight = new DatabaseSync(path, { readOnly: true });
      try { this.readValidation(preflight, true); } finally { preflight.close(); }
    }
    this.db = new DatabaseSync(path);
    try {
      if (this.readValidation(this.db, true)) {
        this.db.exec("BEGIN");
        try { ensure(this.validate(this.db, true)); this.db.exec(migration); this.db.exec("COMMIT"); }
        catch (error) { this.db.exec("ROLLBACK"); throw error; }
      }
      this.readValidation(this.db);
      this.db.exec("PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;");
      if (this.location) this.location.open++;
    } catch (error) { this.db.close(); throw error; }
  }
  close(): void { backupAssert(this.inFlight === 0, "backup_operation_live"); if (!this.closed) { this.db.close(); this.closed = true; if (this.location) this.location.open--; } }

  request(ownerId: string, value: unknown): BackupCommandResult {
    const command = parseRequestBackup(value), descriptor: Descriptor = { recoveryId: null, action: "request", command: { ...command } };
    return this.transaction(() => {
      const replay = this.replay(ownerId, descriptor); if (replay) return replay;
      ensure(Number(this.db.prepare("SELECT count(*) AS n FROM backup_proof_records").get()!.n) < 1000);
      const record: RecoveryRecord = { schemaVersion: 1, scope: BACKUP_PROOF_SCOPE, testOnly: true, productionCapability: false, ownerId, id: randomUUID(), revision: 1, source: command.source, destinationId: command.destinationId, state: "requested", manifest: null, progress: [], pending: null, restoreId: null, failure: null };
      this.operation(ownerId, descriptor, record.id);
      this.append(record, "requested", command.idempotencyKey, null);
      this.completeOperation(ownerId, command.idempotencyKey, record.revision);
      return this.result(record, false);
    });
  }
  prepare(ownerId: string, recoveryId: string, value: unknown): BackupCommandResult {
    this.notLive(ownerId, recoveryId);
    const command = parseBackupRevision(value), descriptor: Descriptor = { recoveryId, action: "prepare", command: { ...command } };
    return this.transaction(() => {
      const replay = this.replay(ownerId, descriptor); if (replay) return replay;
      const record = this.expected(ownerId, recoveryId, command.expectedRevision);
      backupAssert(!record.pending && !record.manifest && (record.state === "requested" || record.state === "failed"), "backup_transition_denied");
      this.operation(ownerId, descriptor, recoveryId);
      try {
        const evidence = this.source(record);
        const { ownerId: omitted, ...portable } = evidence;
        record.manifest = { ...portable, manifestId: randomUUID(), scope: BACKUP_PROOF_SCOPE };
        record.progress = evidence.components.map(item => ({ componentId: item.componentId, objectId: randomUUID(), objectRevision: null, uploadedBytes: 0, prefixSha256: EMPTY_SHA256 }));
        record.state = "preparing"; record.failure = null;
      } catch (error) { record.state = "failed"; record.failure = code(error); }
      this.advance(record, "prepared", command.idempotencyKey);
      this.completeOperation(ownerId, command.idempotencyKey, record.revision);
      return this.result(record, false);
    });
  }
  transferChunk(ownerId: string, recoveryId: string, value: unknown): BackupCommandResult {
    const command = parseTransferBackup(value), descriptor: Descriptor = { recoveryId, action: "transfer", command: { ...command } };
    let captured: SyntheticChunk | undefined;
    return this.perform(ownerId, descriptor, record => {
      backupAssert(record.manifest && !record.pending && ["preparing", "uploading", "failed"].includes(record.state), "backup_transition_denied");
      const before = record.progress.find(item => item.componentId === command.componentId);
      const component = record.manifest.components.find(item => item.componentId === command.componentId);
      backupAssert(before && component && before.uploadedBytes < component.byteLength, "backup_component_unavailable");
      const evidence = this.source(record);
      captured = this.verifier!.capture(ownerId, record.source, command.componentId, before.uploadedBytes, command.maxBytes);
      backupAssert(isGeneratedChunk(captured) && captured.componentId === command.componentId && captured.offset === before.uploadedBytes && captured.byteLength === Math.min(command.maxBytes, component.byteLength - before.uploadedBytes) && captured.evidenceFingerprint === digest(canonical(evidence)), "backup_fixture_bytes_denied");
      this.source(record);
      return { action: "transfer", operationKey: command.idempotencyKey, componentId: command.componentId, before: clone(before), after: { ...before, objectRevision: (before.objectRevision ?? 0) + 1, uploadedBytes: before.uploadedBytes + captured.byteLength, prefixSha256: captured.prefixSha256 }, restoreId: null };
    }, record => {
      const object = this.destination().append(ownerId, record.destinationId, record.pending!.before!, captured!, record.manifest!);
      backupAssert(matchObject(object, record.pending!.after!), "backup_object_revision_conflict"); this.source(record);
      record.progress = record.progress.map(item => item.componentId === command.componentId ? clone(record.pending!.after!) : item);
      record.pending = null; record.state = transferState(record);
    });
  }
  verify(ownerId: string, recoveryId: string, value: unknown): BackupCommandResult {
    const command = parseBackupRevision(value), descriptor: Descriptor = { recoveryId, action: "verify", command: { ...command } };
    return this.perform(ownerId, descriptor, record => {
      backupAssert(record.state === "uploaded_unverified" && !record.pending, "backup_transition_denied"); this.source(record);
      return { action: "verify", operationKey: command.idempotencyKey, componentId: null, before: null, after: null, restoreId: null };
    }, record => {
      this.destination().verify(ownerId, archive(record)); this.source(record);
      record.pending = null; record.state = "verified";
    });
  }
  restore(ownerId: string, recoveryId: string, value: unknown): BackupCommandResult {
    const command = parseRestoreBackup(value), descriptor: Descriptor = { recoveryId, action: "restore", command: { ...command } };
    return this.perform(ownerId, descriptor, record => {
      backupAssert(record.state === "verified" && !record.pending, "backup_transition_denied");
      return { action: "restore", operationKey: command.idempotencyKey, componentId: null, before: null, after: null, restoreId: command.restoreId };
    }, record => {
      const store = this.destination(), portable = archive(record);
      store.restore(ownerId, command.restoreId, portable);
      backupAssert(store.verifyRestore(ownerId, command.restoreId, record.manifest!) === "complete", "backup_restore_unverified");
      store.verify(ownerId, portable);
      record.pending = null; record.state = "restore_verified"; record.restoreId = command.restoreId;
    });
  }
  reconcile(ownerId: string, recoveryId: string, value: unknown): BackupCommandResult {
    this.notLive(ownerId, recoveryId);
    const command = parseBackupRevision(value), descriptor: Descriptor = { recoveryId, action: "reconcile", command: { ...command } };
    return this.transaction(() => {
      const replay = this.replay(ownerId, descriptor); if (replay) return replay;
      const record = this.expected(ownerId, recoveryId, command.expectedRevision);
      backupAssert(record.state === "uncertain" && record.pending, "backup_reconciliation_required");
      // Settlement can consume this intent's reserved slots. An unsuccessful readback
      // needs extra capacity to record another unresolved result; otherwise roll it back.
      this.capacity(0, 0);
      const canJournalUnresolved = this.capacityAvailable(1, 1);
      this.operation(ownerId, descriptor, recoveryId);
      try {
        const pending = record.pending, store = this.destination();
        if (pending.action === "transfer") {
          this.source(record);
          const actual = store.inspect(ownerId, record.destinationId, pending.before!.objectId);
          if (matchObject(actual, pending.after!)) record.progress = record.progress.map(item => item.componentId === pending.componentId ? clone(pending.after!) : item);
          else backupAssert(matchObject(actual, pending.before!), "backup_reconciliation_unknown");
          record.state = transferState(record);
        } else if (pending.action === "verify") {
          this.source(record); store.verify(ownerId, archive(record)); this.source(record); record.state = "verified";
        } else {
          store.verify(ownerId, archive(record));
          const restored = store.verifyRestore(ownerId, pending.restoreId!, record.manifest!);
          record.state = restored === "complete" ? "restore_verified" : "verified";
          if (restored === "complete") record.restoreId = pending.restoreId;
        }
        record.pending = null; record.failure = null;
      } catch (error) {
        backupAssert(canJournalUnresolved, "backup_history_full_retain_journal");
        record.failure = code(error);
      }
      this.advance(record, "reconciled", command.idempotencyKey);
      this.completeOperation(ownerId, command.idempotencyKey, record.revision);
      return this.result(record, false);
    });
  }
  /** Journal history, not live source/object eligibility. Mutating commands revalidate. */
  get(ownerId: string, recoveryId: string): RecoveryRecord {
    this.notLive(ownerId, recoveryId);
    return this.transaction(() => {
      const record = this.load(ownerId, recoveryId);
      if (record.pending && record.state !== "uncertain") {
        record.state = "uncertain"; record.failure = "backup_interrupted_reconcile_required";
        this.advance(record, "uncertain", record.pending.operationKey);
        this.completeOperation(ownerId, record.pending.operationKey, record.revision);
      }
      return clone(record);
    });
  }
  /** Independently rechecks current destination revisions/bytes before exporting metadata. */
  exportManifest(ownerId: string, recoveryId: string): PortableBackupArchive {
    this.notLive(ownerId, recoveryId);
    return this.transaction(() => {
      const record = this.load(ownerId, recoveryId);
      backupAssert((record.state === "verified" || record.state === "restore_verified") && !record.pending, "backup_not_verified");
      const portable = archive(record); this.destination().verify(ownerId, portable); return clone(portable);
    });
  }

  private source(record: RecoveryRecord): SyntheticInputEvidence {
    backupAssert(this.verifier, "backup_source_authority_unavailable");
    const value = this.verifier.inspect(record.ownerId, clone(record.source));
    backupAssert(isGeneratedEvidence(value), "backup_source_authority_denied");
    const evidence = parseSyntheticEvidence(value);
    backupAssert(evidence.ownerId === record.ownerId && same(evidence.source, record.source), "backup_source_changed");
    if (record.manifest) {
      const { ownerId: omitted, ...portable } = evidence;
      const { manifestId: omittedId, scope: omittedScope, ...expected } = record.manifest;
      backupAssert(same(portable, expected), "backup_component_changed");
    }
    return evidence;
  }
  private destination(): SyntheticObjectStore {
    backupAssert(this.store instanceof SyntheticObjectStore, "backup_destination_unavailable"); return this.store;
  }
  private perform(ownerId: string, descriptor: Descriptor, admission: (record: RecoveryRecord) => PendingBackupAction, work: (record: RecoveryRecord) => void): BackupCommandResult {
    const key = backupId(descriptor.command.idempotencyKey), recoveryId = backupId(descriptor.recoveryId);
    const release = this.holdLive(ownerId, recoveryId);
    try {
    const first = this.transaction(() => {
      const replay = this.replay(ownerId, descriptor); if (replay) return { replay };
      const record = this.expected(ownerId, recoveryId, backupInteger(descriptor.command.expectedRevision, 1));
      // Intent, settlement, a failed reconciliation, and retained recovery headroom.
      this.capacity(4, 3);
      // Admission failures have no destination effects and must not create a pending intent.
      const pending = admission(record);
      this.operation(ownerId, descriptor, recoveryId);
      record.pending = pending; record.failure = null;
      record.state = pending.action === "transfer" ? "uploading" : pending.action === "restore" ? "restoring" : "uploaded_unverified";
      this.advance(record, "intent", key);
      return { record };
    });
    if (first.replay) return first.replay;
    const intent = first.record!, outcome = clone(intent);
    let kind = "completed";
    try { work(outcome); outcome.failure = null; }
    catch (error) { outcome.state = "uncertain"; outcome.pending = clone(intent.pending); outcome.progress = clone(intent.progress); outcome.restoreId = null; outcome.failure = code(error); kind = "uncertain"; }
    return this.transaction(() => {
      const current = this.expected(ownerId, recoveryId, intent.revision);
      backupAssert(same(current, intent), "backup_revision_conflict");
      this.advance(outcome, kind, key);
      this.completeOperation(ownerId, key, outcome.revision);
      return this.result(outcome, false);
    });
    } finally { release(); }
  }
  private notLive(ownerId: string, recoveryId: string): void {
    backupId(ownerId); backupId(recoveryId);
    backupAssert(!liveOperations.get(this.liveScope)?.has(canonical([ownerId, recoveryId])), "backup_operation_live");
  }
  private holdLive(ownerId: string, recoveryId: string): () => void {
    this.notLive(ownerId, recoveryId);
    const key = canonical([ownerId, recoveryId]), held = liveOperations.get(this.liveScope) ?? new Set<string>();
    held.add(key); liveOperations.set(this.liveScope, held); this.inFlight++;
    return () => { held.delete(key); this.inFlight--; };
  }
  private capacity(additionalEvents: number, additionalOperations: number): void {
    backupAssert(this.capacityAvailable(additionalEvents, additionalOperations), "backup_history_full_retain_journal");
  }
  private capacityAvailable(additionalEvents: number, additionalOperations: number): boolean {
    const meta = this.db.prepare("SELECT * FROM backup_proof_meta WHERE singleton=1").get()!;
    return Number(meta.event_count) + Number(meta.reserved_events) + additionalEvents <= 10_000 && Number(meta.operation_count) + Number(meta.reserved_operations) + additionalOperations <= 10_000;
  }
  private result(record: RecoveryRecord, replayed: boolean): BackupCommandResult { return { record: clone(record), replayed, testOnly: true, productionCapability: false }; }
  private expected(ownerId: string, id: string, revision: number): RecoveryRecord {
    const record = this.load(ownerId, id); backupAssert(record.revision === revision, "backup_revision_conflict"); return record;
  }
  private load(ownerId: string, id: string): RecoveryRecord {
    backupId(ownerId); backupId(id);
    const row = this.db.prepare("SELECT * FROM backup_proof_records WHERE owner_id=? AND recovery_id=?").get(ownerId, id) as RecordRow | undefined;
    backupAssert(row, "backup_not_found"); return decoded(row.record_json);
  }
  private operation(ownerId: string, descriptor: Descriptor, recoveryId: string): void {
    this.capacity(0, descriptor.action === "reconcile" ? 0 : 1);
    this.db.prepare("INSERT INTO backup_proof_operations VALUES (?, ?, ?, ?, ?, ?, NULL)").run(ownerId, backupId(descriptor.command.idempotencyKey), recoveryId, descriptor.action, canonical(descriptor), digest(canonical(descriptor)));
    this.db.exec("UPDATE backup_proof_meta SET operation_count=operation_count+1 WHERE singleton=1");
  }
  private completeOperation(ownerId: string, key: string, revision: number): void {
    const updated = this.db.prepare("UPDATE backup_proof_operations SET result_revision=? WHERE owner_id=? AND operation_key=? AND result_revision IS NULL").run(revision, ownerId, key);
    ensure(updated.changes === 1);
  }
  private replay(ownerId: string, descriptor: Descriptor): BackupCommandResult | null {
    backupId(ownerId); const key = backupId(descriptor.command.idempotencyKey);
    const row = this.db.prepare("SELECT * FROM backup_proof_operations WHERE owner_id=? AND operation_key=?").get(ownerId, key) as OperationRow | undefined;
    if (!row) return null;
    backupAssert(row.request_hash === digest(canonical(descriptor)) && row.command_json === canonical(descriptor), "backup_idempotency_conflict");
    backupAssert(row.result_revision !== null, "backup_interrupted_reconcile_required");
    const event = this.db.prepare("SELECT record_json FROM backup_proof_events WHERE owner_id=? AND recovery_id=? AND revision=?").get(ownerId, row.recovery_id, row.result_revision) as { record_json: string };
    return this.result(decoded(event.record_json), true);
  }
  private append(record: RecoveryRecord, kind: string, key: string, previous: number | null): void {
    parseRecord(record);
    const before = reservations(previous === null ? null : this.load(record.ownerId, record.id)), after = reservations(record);
    this.capacity(1 + after.events - before.events, after.operations - before.operations);
    const json = canonical(record);
    if (previous === null) this.db.prepare("INSERT INTO backup_proof_records VALUES (?, ?, ?, ?)").run(record.ownerId, record.id, record.revision, json);
    else ensure(this.db.prepare("UPDATE backup_proof_records SET revision=?, record_json=? WHERE owner_id=? AND recovery_id=? AND revision=?").run(record.revision, json, record.ownerId, record.id, previous).changes === 1);
    this.db.prepare("INSERT INTO backup_proof_events VALUES (?, ?, ?, ?, ?, ?)").run(record.ownerId, record.id, record.revision, kind, key, json);
    this.db.prepare("UPDATE backup_proof_meta SET event_count=event_count+1, record_count=record_count+?, reserved_events=reserved_events+?, reserved_operations=reserved_operations+? WHERE singleton=1").run(previous === null ? 1 : 0, after.events - before.events, after.operations - before.operations);
  }
  private advance(record: RecoveryRecord, kind: string, key: string): void { const previous = record.revision; record.revision = next(previous); this.append(record, kind, key, previous); }
  private transaction<T>(body: () => T): T {
    this.db.exec("BEGIN IMMEDIATE");
    try { this.validate(this.db); const result = body(); this.db.exec("COMMIT"); return result; }
    catch (error) { this.db.exec("ROLLBACK"); throw error; }
  }
  private readValidation(db: DatabaseSync, allowEmpty = false): boolean {
    db.exec("BEGIN"); try { const empty = this.validate(db, allowEmpty); db.exec("COMMIT"); return empty; } catch (error) { db.exec("ROLLBACK"); throw error; }
  }
  private validate(db: DatabaseSync, allowEmpty = false): boolean {
    const application = Number(db.prepare("PRAGMA application_id").get()!.application_id), version = Number(db.prepare("PRAGMA user_version").get()!.user_version), schema = canonical(objects(db));
    if (allowEmpty && application === 0 && version === 0 && schema === "[]" && Number(db.prepare("PRAGMA page_count").get()!.page_count) === 0) return true;
    ensure(application === APPLICATION_ID && version === 1 && schema === expectedSchema);
    ensure(db.prepare("PRAGMA quick_check").all().every(row => row.quick_check === "ok"));
    const meta = db.prepare("SELECT * FROM backup_proof_meta").all();
    ensure(meta.length === 1 && meta[0].singleton === 1 && meta[0].identity === "still-synthetic-backup-recovery" && meta[0].schema_version === 1);
    const records = db.prepare("SELECT * FROM backup_proof_records ORDER BY owner_id, recovery_id").all() as RecordRow[];
    const events = db.prepare("SELECT * FROM backup_proof_events ORDER BY owner_id, recovery_id, revision").all() as EventRow[];
    const operations = db.prepare("SELECT * FROM backup_proof_operations ORDER BY owner_id, operation_key").all() as OperationRow[];
    ensure(records.length <= 1000 && events.length <= 10_000 && operations.length <= 10_000);
    ensure(meta[0].record_count === records.length && meta[0].event_count === events.length && meta[0].operation_count === operations.length);
    const current = new Map<string, RecoveryRecord>(), histories = new Map<string, { event: EventRow; record: RecoveryRecord }[]>();
    const operationEvents = new Map<string, { event: EventRow; record: RecoveryRecord }[]>();
    const address = (owner: string, id: string) => canonical([owner, id]);
    for (const row of records) {
      const record = decoded(row.record_json); ensure(record.ownerId === row.owner_id && record.id === row.recovery_id && record.revision === row.revision && canonical(record) === row.record_json);
      current.set(address(row.owner_id, row.recovery_id), record);
    }
    const operationMap = new Map(operations.map(row => [address(row.owner_id, row.operation_key), row]));
    for (const event of events) {
      const record = decoded(event.record_json), id = address(event.owner_id, event.recovery_id), op = operationMap.get(address(event.owner_id, event.operation_key));
      ensure(op && op.recovery_id === event.recovery_id && record.ownerId === event.owner_id && record.id === event.recovery_id && record.revision === event.revision && canonical(record) === event.record_json);
      const history = histories.get(id) ?? [], previous = history.at(-1)?.record;
      ensure(record.revision === history.length + 1 && current.has(id));
      if (!previous) ensure(event.kind === "requested" && op.action === "request" && record.state === "requested");
      else {
        ensure(same(record.source, previous.source) && record.destinationId === previous.destinationId && record.ownerId === previous.ownerId && record.id === previous.id);
        if (previous.manifest) {
          ensure(same(previous.manifest, record.manifest));
          previous.progress.forEach((item, i) => ensure(item.componentId === record.progress[i].componentId && item.objectId === record.progress[i].objectId && record.progress[i].uploadedBytes >= item.uploadedBytes && (record.progress[i].uploadedBytes !== item.uploadedBytes || same(item, record.progress[i]))));
        }
        if (previous.pending) ensure(event.kind === "completed" || event.kind === "uncertain" || event.kind === "reconciled");
        if (previous.state === "uncertain") ensure(event.kind === "reconciled" && op.action === "reconcile");
        if (event.kind === "intent") {
          ensure(!previous.pending && record.pending?.operationKey === event.operation_key && record.pending.action === op.action && same(previous.progress, record.progress));
          ensure(op.action === "transfer" ? ["preparing", "uploading", "failed"].includes(previous.state) && record.state === "uploading" : op.action === "verify" ? previous.state === "uploaded_unverified" && record.state === "uploaded_unverified" : op.action === "restore" && previous.state === "verified" && record.state === "restoring");
        }
        if (event.kind === "completed") {
          ensure(previous.pending?.operationKey === event.operation_key && !record.pending);
          if (op.action === "transfer") ensure(record.state === transferState(record) && same(record.progress, previous.progress.map(item => item.componentId === previous.pending!.componentId ? previous.pending!.after : item)));
          else ensure(same(record.progress, previous.progress) && (op.action === "verify" ? record.state === "verified" : op.action === "restore" && record.state === "restore_verified" && record.restoreId === previous.pending.restoreId));
        }
        if (event.kind === "uncertain") ensure(record.state === "uncertain" && same(record.pending, previous.pending) && same(record.progress, previous.progress));
        if (event.kind === "prepared") ensure(op.action === "prepare" && !previous.manifest && ["requested", "failed"].includes(previous.state) && (record.state === "preparing" || record.state === "failed" && !record.manifest));
        if (event.kind === "reconciled") {
          ensure(op.action === "reconcile" && previous.state === "uncertain" && previous.pending);
          if (record.state === "uncertain") ensure(same(record.pending, previous.pending) && same(record.progress, previous.progress));
          else {
            ensure(!record.pending);
            if (previous.pending.action === "transfer") ensure(record.state === transferState(record) && (same(record.progress, previous.progress) || same(record.progress, previous.progress.map(item => item.componentId === previous.pending!.componentId ? previous.pending!.after : item))));
            else ensure(same(record.progress, previous.progress) && (previous.pending.action === "verify" ? record.state === "verified" : record.state === "verified" || record.state === "restore_verified" && record.restoreId === previous.pending.restoreId));
          }
        }
      }
      const entry = { event, record };
      history.push(entry); histories.set(id, history);
      const operationAddress = address(event.owner_id, event.operation_key), own = operationEvents.get(operationAddress) ?? [];
      own.push(entry); operationEvents.set(operationAddress, own);
    }
    for (const [id, record] of current) ensure(same(histories.get(id)?.at(-1)?.record, record));
    ensure(meta[0].reserved_events === [...current.values()].reduce((n, record) => n + reservations(record).events, 0) && meta[0].reserved_operations === [...current.values()].reduce((n, record) => n + reservations(record).operations, 0));
    ensure(events.length + Number(meta[0].reserved_events) <= 10_000 && operations.length + Number(meta[0].reserved_operations) <= 10_000);
    for (const op of operations) {
      backupId(op.owner_id); backupId(op.operation_key); backupId(op.recovery_id);
      ensure(["request", "prepare", "transfer", "verify", "restore", "reconcile"].includes(op.action));
      ensure(op.command_json.length <= 8192 && digest(op.command_json) === op.request_hash);
      const data = backupObject(JSON.parse(op.command_json), ["recoveryId", "action", "command"]);
      ensure(data.action === op.action && (op.action === "request" ? data.recoveryId === null : data.recoveryId === op.recovery_id));
      const parsed = op.action === "request" ? parseRequestBackup(data.command) : op.action === "transfer" ? parseTransferBackup(data.command) : op.action === "restore" ? parseRestoreBackup(data.command) : parseBackupRevision(data.command);
      ensure(parsed.idempotencyKey === op.operation_key && same(parsed, data.command) && op.command_json === canonical(data));
      const history = histories.get(address(op.owner_id, op.recovery_id)); ensure(history);
      const ownEvents = operationEvents.get(address(op.owner_id, op.operation_key)) ?? []; ensure(ownEvents.length >= 1 && ownEvents.length <= 2);
      const first = ownEvents[0], last = ownEvents.at(-1)!;
      if (op.action === "request") ensure(first.event.kind === "requested" && same(first.record.source, (parsed as { source: BackupSourceRef }).source) && first.record.destinationId === (parsed as { destinationId: string }).destinationId && ownEvents.length === 1);
      else {
        const expected = (parsed as { expectedRevision: number }).expectedRevision;
        ensure(first.record.revision === expected + 1 && (op.action === "prepare" || op.action === "reconcile" ? ownEvents.length === 1 : first.event.kind === "intent"));
        if (op.action === "transfer") { const transfer = parseTransferBackup(data.command); ensure(first.record.pending?.componentId === transfer.componentId && first.record.pending.after!.uploadedBytes - first.record.pending.before!.uploadedBytes <= transfer.maxBytes); }
        if (op.action === "restore") ensure(first.record.pending?.restoreId === parseRestoreBackup(data.command).restoreId);
      }
      if (op.result_revision === null) ensure(last.event.kind === "intent" && last === history.at(-1));
      else ensure(op.result_revision === last.record.revision && (op.action === "request" || op.action === "prepare" || op.action === "reconcile" || ownEvents.length === 2));
    }
    return false;
  }
}
