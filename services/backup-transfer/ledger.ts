import { randomUUID } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import {
  TRANSFER_PROOF_SCOPE, TransferProofError, parseCreateTransfer, parseDispatchTransfer,
  parseTransferRevision, transferAssert, transferBinding, transferId, transferInteger,
  transferObject, transferProviderObject,
} from "../../packages/contracts/src/backup-transfer-recovery.ts";
import type { SyntheticTransferStatus, TransferAttempt, TransferObservation, TransferPending, TransferResult } from "../../packages/contracts/src/backup-transfer-recovery.ts";
import { canonical, same, SyntheticTransferWorld } from "./synthetic-world.ts";
import type { SyntheticDispatch } from "./synthetic-world.ts";

export interface TransferJournalFile { readonly scope: "generated_async_transfer_journal" }
interface Location { directory: string; file: string; open: number }
const locations = new WeakMap<object, Location>();
export function createTransferJournalFile(): TransferJournalFile {
  const directory = mkdtempSync(join(tmpdir(), "still-synthetic-transfer-"));
  const value: TransferJournalFile = Object.freeze({ scope: "generated_async_transfer_journal" });
  locations.set(value, { directory, file: join(directory, "transfer.sqlite"), open: 0 }); return value;
}
function location(value: unknown): Location { transferAssert(value !== null && typeof value === "object" && locations.has(value), "transfer_fixture_location_denied"); return locations.get(value)!; }
export function inspectTransferJournalForTest<T>(value: TransferJournalFile, inspect: (db: DatabaseSync) => T): T { const db = new DatabaseSync(location(value).file); try { return inspect(db); } finally { db.close(); } }
export function transferJournalBytesForTest(value: TransferJournalFile): Uint8Array { return readFileSync(location(value).file); }
export function disposeTransferJournalFile(value: TransferJournalFile): void { const file = location(value); transferAssert(file.open === 0, "transfer_journal_busy"); rmSync(file.directory, { recursive: true }); locations.delete(value); }
export interface TransferLedgerOptions { world?: SyntheticTransferWorld; now?: () => number }
export interface DispatchResult extends TransferResult { dispatch: SyntheticDispatch | null }
type RecordRow = { owner_id: string; attempt_id: string; revision: number; record_json: string };
type EventRow = RecordRow & { kind: string; operation_key: string | null };
type OperationRow = { owner_id: string; operation_key: string; descriptor_json: string; attempt_id: string; result_revision: number };
type Descriptor = { action: "create" | "dispatch" | "acknowledge" | "cancel" | "reconcile"; attemptId: string | null; command: unknown; status: SyntheticTransferStatus | null };
const DDL = readFileSync(new URL("../../db/backup/002_transfer_attempts.sql", import.meta.url), "utf8");
transferAssert(DDL.trim().length > 0);
const invalid = "transfer_journal_invalid_retain_for_investigation";
function ensure(value: unknown): asserts value { transferAssert(value, invalid); }
function clone<T>(value: T): T { return structuredClone(value); }
function next(value: number): number { transferAssert(value < Number.MAX_SAFE_INTEGER, "transfer_revision_exhausted"); return value + 1; }
function schema(db: DatabaseSync): string { return canonical(db.prepare("SELECT type,name,tbl_name,sql FROM sqlite_schema WHERE substr(name,1,7)!='sqlite_' ORDER BY type,name").all()); }
const reference = new DatabaseSync(":memory:"); reference.exec(DDL); const expectedSchema = schema(reference); reference.close();

function pending(value: unknown): TransferPending {
  const data = transferObject(value, ["requestId", "operationId", "kind", "intentRevision", "fromOffset", "toOffset", "before", "issuedAt", "leaseExpiresAt"]);
  ensure(data.kind === "create" || data.kind === "append" || data.kind === "finalize");
  const result = { requestId: transferId(data.requestId), operationId: transferId(data.operationId), kind: data.kind, intentRevision: transferInteger(data.intentRevision, 1), fromOffset: transferInteger(data.fromOffset), toOffset: transferInteger(data.toOffset), before: data.before === null ? null : transferProviderObject(data.before), issuedAt: transferInteger(data.issuedAt), leaseExpiresAt: transferInteger(data.leaseExpiresAt, 1) } as TransferPending;
  ensure(result.leaseExpiresAt > result.issuedAt && result.leaseExpiresAt - result.issuedAt <= 300_000); return result;
}
function observation(value: unknown): TransferObservation {
  const data = transferObject(value, ["requestId", "outcome", "offset", "object", "finalized"]);
  ensure((data.outcome === "applied" || data.outcome === "not_applied") && typeof data.finalized === "boolean");
  return { requestId: transferId(data.requestId), outcome: data.outcome, offset: transferInteger(data.offset), object: data.object === null ? null : transferProviderObject(data.object), finalized: data.finalized };
}
function decode(value: unknown): TransferAttempt {
  const data = transferObject(value, ["schemaVersion", "scope", "testOnly", "productionCapability", "ownerId", "id", "revision", "binding", "creationOperationId", "state", "acknowledgedOffset", "object", "pending", "stopRequested", "fenceReason", "leaseExpired", "lastObservation"]);
  ensure(data.schemaVersion === 1 && data.scope === TRANSFER_PROOF_SCOPE && data.testOnly === true && data.productionCapability === false);
  ensure(["ready", "in_flight", "uncertain", "uploaded_unverified", "cancelled", "fenced"].includes(String(data.state)));
  ensure(typeof data.stopRequested === "boolean" && typeof data.leaseExpired === "boolean" && (data.fenceReason === null || ["connection_changed", "disconnected", "source_changed"].includes(String(data.fenceReason))));
  const result: TransferAttempt = { schemaVersion: 1, scope: TRANSFER_PROOF_SCOPE, testOnly: true, productionCapability: false, ownerId: transferId(data.ownerId), id: transferId(data.id), revision: transferInteger(data.revision, 1), binding: transferBinding(data.binding), creationOperationId: transferId(data.creationOperationId), state: data.state as TransferAttempt["state"], acknowledgedOffset: transferInteger(data.acknowledgedOffset), object: data.object === null ? null : transferProviderObject(data.object), pending: data.pending === null ? null : pending(data.pending), stopRequested: data.stopRequested, fenceReason: data.fenceReason as TransferAttempt["fenceReason"], leaseExpired: data.leaseExpired, lastObservation: data.lastObservation === null ? null : observation(data.lastObservation) };
  ensure(result.acknowledgedOffset <= result.binding.totalBytes && (result.object !== null || result.acknowledgedOffset === 0));
  if (result.pending) {
    const p = result.pending;
    ensure(p.intentRevision <= result.revision && p.fromOffset === result.acknowledgedOffset && p.toOffset <= result.binding.totalBytes && p.toOffset >= p.fromOffset && same(p.before, result.object));
    ensure(p.kind === "create" ? !p.before && p.fromOffset === 0 && p.toOffset === 0 && p.operationId === result.creationOperationId : p.before && (p.kind === "append" ? p.toOffset > p.fromOffset && p.toOffset - p.fromOffset <= 65_536 : p.toOffset === p.fromOffset && p.toOffset === result.binding.totalBytes));
    ensure(result.state === "in_flight" || result.state === "uncertain");
  } else ensure(!result.leaseExpired && result.state !== "in_flight" && result.state !== "uncertain");
  if (result.state === "in_flight") ensure(!result.stopRequested && !result.fenceReason && !result.leaseExpired && result.pending!.intentRevision === result.revision);
  if (result.state === "cancelled") ensure(result.stopRequested && !result.pending);
  if (result.state === "fenced") ensure(result.fenceReason && !result.pending);
  if (result.state === "ready" || result.state === "uploaded_unverified") ensure(!result.stopRequested && !result.fenceReason && !result.pending);
  if (result.state === "uploaded_unverified") ensure(result.object && result.acknowledgedOffset === result.binding.totalBytes && result.lastObservation?.finalized);
  if (result.lastObservation) ensure(result.lastObservation.offset <= result.binding.totalBytes && (!result.lastObservation.finalized || result.lastObservation.outcome === "applied" && result.lastObservation.offset === result.binding.totalBytes && result.lastObservation.object));
  return result;
}
function decodeText(text: string): TransferAttempt { ensure(text.length < 65_536); const result = decode(JSON.parse(text)); ensure(canonical(result) === text); return result; }
function parseStatus(value: unknown): SyntheticTransferStatus {
  const data = transferObject(value, ["schemaVersion", "scope", "testOnly", "productionCapability", "ownerId", "attemptId", "binding", "requestId", "operationId", "intentRevision", "kind", "fromOffset", "toOffset", "before", "outcome", "settled", "offset", "object", "finalized"]);
  ensure(data.schemaVersion === 1 && data.scope === TRANSFER_PROOF_SCOPE && data.testOnly === true && data.productionCapability === false && typeof data.settled === "boolean" && typeof data.finalized === "boolean");
  ensure(["create", "append", "finalize"].includes(String(data.kind)) && ["unknown", "in_flight", "applied", "not_applied"].includes(String(data.outcome)));
  return { schemaVersion: 1, scope: TRANSFER_PROOF_SCOPE, testOnly: true, productionCapability: false, ownerId: transferId(data.ownerId), attemptId: transferId(data.attemptId), binding: transferBinding(data.binding), requestId: transferId(data.requestId), operationId: transferId(data.operationId), intentRevision: transferInteger(data.intentRevision, 1), kind: data.kind as SyntheticTransferStatus["kind"], fromOffset: transferInteger(data.fromOffset), toOffset: transferInteger(data.toOffset), before: data.before === null ? null : transferProviderObject(data.before), outcome: data.outcome as SyntheticTransferStatus["outcome"], settled: data.settled, offset: data.offset === null ? null : transferInteger(data.offset), object: data.object === null ? null : transferProviderObject(data.object), finalized: data.finalized };
}

/** Metadata-only fixture ledger. Dispatch is a one-time runtime capability after durable intent. */
export class TransferRecoveryLedger {
  private readonly db: DatabaseSync;
  private readonly file: Location | undefined;
  private readonly world: SyntheticTransferWorld | undefined;
  private readonly now: () => number;
  private observedTime = 0;
  private readonly local = new Set<string>();
  private closed = false;
  constructor(file: ":memory:" | TransferJournalFile, options: TransferLedgerOptions = {}) {
    this.file = file === ":memory:" ? undefined : location(file); const target = this.file?.file ?? ":memory:";
    this.world = options.world; this.now = options.now ?? Date.now;
    if (target !== ":memory:" && existsSync(target)) { const db = new DatabaseSync(target, { readOnly: true }); try { this.readValidate(db, true); } finally { db.close(); } }
    this.db = new DatabaseSync(target);
    try {
      if (this.readValidate(this.db, true)) { this.db.exec("BEGIN"); try { ensure(this.validate(this.db, true)); this.db.exec(DDL); this.db.exec("COMMIT"); } catch (error) { this.db.exec("ROLLBACK"); throw error; } }
      this.readValidate(this.db); this.db.exec("PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;"); if (this.file) this.file.open++;
    } catch (error) { this.db.close(); throw error; }
  }
  close(): void { if (!this.closed) { this.db.close(); this.closed = true; if (this.file) this.file.open--; } }
  create(ownerId: string, value: unknown): TransferResult {
    const command = parseCreateTransfer(value), descriptor: Descriptor = { action: "create", attemptId: null, command, status: null };
    return this.transaction(at => {
      const replay = this.replay(ownerId, command.idempotencyKey, descriptor); if (replay) return replay;
      transferAssert(this.authority().reason(ownerId, command.binding) === null, "transfer_authority_denied");
      ensure(Number(this.db.prepare("SELECT attempt_count FROM transfer_proof_meta").get()!.attempt_count) < 1000);
      const record: TransferAttempt = { schemaVersion: 1, scope: TRANSFER_PROOF_SCOPE, testOnly: true, productionCapability: false, ownerId: transferId(ownerId), id: randomUUID(), revision: 1, binding: command.binding, creationOperationId: randomUUID(), state: "ready", acknowledgedOffset: 0, object: null, pending: null, stopRequested: false, fenceReason: null, leaseExpired: false, lastObservation: null };
      this.append(record, "create", command.idempotencyKey, null); this.operation(ownerId, command.idempotencyKey, descriptor, record); return this.result(record, false);
    });
  }
  dispatch(ownerId: string, id: string, value: unknown): DispatchResult {
    const command = parseDispatchTransfer(value), descriptor: Descriptor = { action: "dispatch", attemptId: id, command, status: null };
    const result = this.transaction(at => {
      const replay = this.replay(ownerId, command.idempotencyKey, descriptor); if (replay) return replay;
      const record = this.expected(ownerId, id, command.expectedRevision);
      transferAssert(!record.pending && !record.stopRequested && !record.fenceReason && record.state === "ready", "transfer_reconciliation_required");
      transferAssert(this.authority().reason(ownerId, record.binding) === null, "transfer_context_changed");
      transferAssert(command.kind === "create" ? !record.object : record.object, "transfer_phase_invalid");
      transferAssert(command.kind !== "finalize" || record.acknowledgedOffset === record.binding.totalBytes, "transfer_incomplete");
      transferAssert(command.kind !== "append" || record.acknowledgedOffset + command.byteLength <= record.binding.totalBytes, "transfer_offset_invalid");
      record.pending = { requestId: randomUUID(), operationId: command.kind === "create" ? record.creationOperationId : randomUUID(), kind: command.kind, intentRevision: next(record.revision), fromOffset: record.acknowledgedOffset, toOffset: record.acknowledgedOffset + command.byteLength, before: clone(record.object), issuedAt: at, leaseExpiresAt: transferInteger(at + command.leaseMs, 1) };
      record.state = "in_flight"; record.leaseExpired = false; this.advance(record, "dispatch", command.idempotencyKey);
      this.operation(ownerId, command.idempotencyKey, descriptor, record); return this.result(record, false);
    });
    if (result.replayed) return { ...result, dispatch: null };
    this.local.add(result.attempt.pending!.requestId);
    return { ...result, dispatch: this.authority().issue(result.attempt, () => this.transaction(at => {
      const current = this.load(ownerId, id);
      return current.revision === result.attempt.revision && current.state === "in_flight" && current.pending?.requestId === result.attempt.pending!.requestId && !current.stopRequested && !current.fenceReason && at < current.pending.leaseExpiresAt && this.authority().reason(ownerId, current.binding) === null;
    })) };
  }
  acknowledge(ownerId: string, id: string, value: unknown, receipt: unknown): TransferResult {
    const status = this.trusted(receipt), command = parseTransferRevision(value), descriptor: Descriptor = { action: "acknowledge", attemptId: id, command, status };
    return this.transaction(at => {
      const replay = this.replay(ownerId, command.idempotencyKey, descriptor); if (replay) return replay;
      const record = this.expected(ownerId, id, command.expectedRevision);
      transferAssert(record.pending && this.local.has(record.pending.requestId) && record.state === "in_flight" && record.revision === record.pending.intentRevision && !record.stopRequested && !record.fenceReason && this.sample() < record.pending.leaseExpiresAt && this.authority().reason(ownerId, record.binding) === null, "transfer_late_response_reconcile_required");
      this.apply(record, status, false); this.advance(record, "acknowledge", command.idempotencyKey); this.operation(ownerId, command.idempotencyKey, descriptor, record); return this.result(record, false);
    });
  }
  cancel(ownerId: string, id: string, value: unknown): TransferResult {
    const command = parseTransferRevision(value), descriptor: Descriptor = { action: "cancel", attemptId: id, command, status: null };
    return this.transaction(() => {
      const replay = this.replay(ownerId, command.idempotencyKey, descriptor); if (replay) return replay;
      const record = this.expected(ownerId, id, command.expectedRevision); record.stopRequested = true; record.state = record.pending ? "uncertain" : "cancelled";
      this.advance(record, "cancel", command.idempotencyKey); this.operation(ownerId, command.idempotencyKey, descriptor, record); return this.result(record, false);
    });
  }
  reconcile(ownerId: string, id: string, value: unknown): TransferResult {
    const command = parseTransferRevision(value);
    return this.transaction(at => {
      const initial: Descriptor = { action: "reconcile", attemptId: id, command, status: null };
      const replay = this.replay(ownerId, command.idempotencyKey, initial, true); if (replay) return replay;
      const record = this.expected(ownerId, id, command.expectedRevision); transferAssert(record.pending, "transfer_nothing_to_reconcile");
      this.fence(record, at);
      const status = this.trusted(this.authority().status(ownerId, id, record.binding, record.pending));
      this.apply(record, status, true); this.advance(record, "reconcile", command.idempotencyKey);
      this.operation(ownerId, command.idempotencyKey, { ...initial, status }, record); return this.result(record, false);
    });
  }
  /** Historical journal observation; a restart/lease never proves the remote request stopped. */
  get(ownerId: string, id: string): TransferAttempt {
    return this.transaction(at => {
      const record = this.load(ownerId, id), before = canonical(record); this.fence(record, at);
      if (record.pending && !this.local.has(record.pending.requestId)) record.state = "uncertain";
      if (canonical(record) !== before) this.advance(record, "fence", null);
      return clone(record);
    });
  }
  private fence(record: TransferAttempt, at: number): void {
    if (!record.fenceReason && this.world) record.fenceReason = this.world.reason(record.ownerId, record.binding);
    if (record.pending && at >= record.pending.leaseExpiresAt) record.leaseExpired = true;
    if (record.pending && (record.fenceReason || record.stopRequested || record.leaseExpired)) record.state = "uncertain";
    else if (!record.pending && record.stopRequested) record.state = "cancelled";
    else if (!record.pending && record.fenceReason) record.state = "fenced";
  }
  private trusted(value: unknown): SyntheticTransferStatus { transferAssert(this.authority().accepts(value), "transfer_status_authority_denied"); return parseStatus(value); }
  private authority(): SyntheticTransferWorld { transferAssert(this.world instanceof SyntheticTransferWorld, "transfer_authority_unavailable"); return this.world; }
  private apply(record: TransferAttempt, status: SyntheticTransferStatus, reconciled: boolean): void {
    const p = record.pending!;
    transferAssert(status.ownerId === record.ownerId && status.attemptId === record.id && same(status.binding, record.binding) && status.requestId === p.requestId && status.operationId === p.operationId && status.intentRevision === p.intentRevision && status.kind === p.kind && status.fromOffset === p.fromOffset && status.toOffset === p.toOffset && same(status.before, p.before), "transfer_status_identity_mismatch");
    if (status.outcome === "unknown" || status.outcome === "in_flight") {
      transferAssert(!status.settled && status.offset === null && status.object === null && !status.finalized, "transfer_status_invalid"); record.state = "uncertain"; return;
    }
    transferAssert(status.settled && status.offset !== null, "transfer_remote_not_settled");
    if (status.outcome === "not_applied") transferAssert(status.offset === p.fromOffset && same(status.object, p.before) && !status.finalized, "transfer_status_offset_mismatch");
    else {
      transferAssert(status.offset === p.toOffset && status.object && status.finalized === (p.kind === "finalize"), "transfer_status_offset_mismatch");
      if (p.before) transferAssert(status.object.objectId === p.before.objectId && status.object.sessionId === p.before.sessionId && status.object.objectRevision !== p.before.objectRevision, "transfer_status_session_mismatch");
    }
    record.lastObservation = { requestId: p.requestId, outcome: status.outcome, offset: status.offset, object: clone(status.object), finalized: status.finalized };
    // Cancelled/fenced intent can record its historical remote outcome, never resume/retarget it.
    const active = !record.stopRequested && !record.fenceReason;
    if (active && status.outcome === "applied") { record.acknowledgedOffset = status.offset; record.object = clone(status.object); }
    record.pending = null; record.leaseExpired = false;
    record.state = record.stopRequested ? "cancelled" : record.fenceReason ? "fenced" : status.finalized ? "uploaded_unverified" : "ready";
    if (!reconciled) transferAssert(active, "transfer_late_response_reconcile_required");
  }
  private result(attempt: TransferAttempt, replayed: boolean): TransferResult { return { attempt: clone(attempt), replayed, testOnly: true, productionCapability: false }; }
  private load(owner: string, id: string): TransferAttempt { transferId(owner); transferId(id); const row = this.db.prepare("SELECT * FROM transfer_proof_attempts WHERE owner_id=? AND attempt_id=?").get(owner, id) as RecordRow | undefined; transferAssert(row, "transfer_not_found"); return decodeText(row.record_json); }
  private expected(owner: string, id: string, revision: number): TransferAttempt { const record = this.load(owner, id); transferAssert(record.revision === revision, "transfer_revision_conflict"); return record; }
  private replay(owner: string, key: string, descriptor: Descriptor, statusRead = false): TransferResult | null {
    transferId(owner); const row = this.db.prepare("SELECT * FROM transfer_proof_operations WHERE owner_id=? AND operation_key=?").get(owner, key) as OperationRow | undefined; if (!row) return null;
    const original = JSON.parse(row.descriptor_json) as Descriptor;
    transferAssert(statusRead ? same({ ...original, status: null }, descriptor) : same(original, descriptor), "transfer_idempotency_conflict");
    const event = this.db.prepare("SELECT record_json FROM transfer_proof_events WHERE owner_id=? AND attempt_id=? AND revision=?").get(owner, row.attempt_id, row.result_revision)!;
    return this.result(decodeText(String(event.record_json)), true);
  }
  private operation(owner: string, key: string, descriptor: Descriptor, record: TransferAttempt): void {
    this.db.prepare("INSERT INTO transfer_proof_operations VALUES(?,?,?,?,?)").run(owner, key, canonical(descriptor), record.id, record.revision);
    this.db.exec("UPDATE transfer_proof_meta SET operation_count=operation_count+1 WHERE singleton=1");
  }
  private append(record: TransferAttempt, kind: string, key: string | null, previous: number | null): void {
    decode(record); const text = canonical(record);
    if (previous === null) this.db.prepare("INSERT INTO transfer_proof_attempts VALUES(?,?,?,?)").run(record.ownerId, record.id, record.revision, text);
    else ensure(this.db.prepare("UPDATE transfer_proof_attempts SET revision=?,record_json=? WHERE owner_id=? AND attempt_id=? AND revision=?").run(record.revision, text, record.ownerId, record.id, previous).changes === 1);
    this.db.prepare("INSERT INTO transfer_proof_events VALUES(?,?,?,?,?,?)").run(record.ownerId, record.id, record.revision, kind, key, text);
    this.db.prepare("UPDATE transfer_proof_meta SET event_count=event_count+1,attempt_count=attempt_count+? WHERE singleton=1").run(previous === null ? 1 : 0);
  }
  private advance(record: TransferAttempt, kind: string, key: string | null): void { const previous = record.revision; record.revision = next(previous); this.append(record, kind, key, previous); }
  private sample(): number { const current = transferInteger(this.now()), persisted = Number(this.db.prepare("SELECT high_water FROM transfer_proof_meta").get()!.high_water); this.observedTime = Math.max(current, persisted, this.observedTime); this.db.prepare("UPDATE transfer_proof_meta SET high_water=? WHERE singleton=1").run(this.observedTime); return this.observedTime; }
  private transaction<T>(body: (at: number) => T): T {
    this.db.exec("BEGIN IMMEDIATE");
    try { this.validate(this.db); const at = this.sample(), result = body(at); this.sample(); this.db.exec("COMMIT"); return result; }
    catch (error) {
      this.db.exec("ROLLBACK");
      // Observed time remains monotonic even when a rejected command/write rolls back.
      this.db.exec("BEGIN IMMEDIATE"); try { this.validate(this.db); this.sample(); this.db.exec("COMMIT"); } catch { this.db.exec("ROLLBACK"); }
      throw error;
    }
  }
  private readValidate(db: DatabaseSync, empty = false): boolean { db.exec("BEGIN"); try { const result = this.validate(db, empty); db.exec("COMMIT"); return result; } catch (error) { db.exec("ROLLBACK"); throw error; } }
  private validate(db: DatabaseSync, allowEmpty = false): boolean {
    const app = Number(db.prepare("PRAGMA application_id").get()!.application_id), version = Number(db.prepare("PRAGMA user_version").get()!.user_version), actualSchema = schema(db);
    if (allowEmpty && app === 0 && version === 0 && actualSchema === "[]" && Number(db.prepare("PRAGMA page_count").get()!.page_count) === 0) return true;
    ensure(app === 1398032947 && version === 1 && actualSchema === expectedSchema && db.prepare("PRAGMA quick_check").all().every(row => row.quick_check === "ok"));
    const meta = db.prepare("SELECT * FROM transfer_proof_meta").all(); ensure(meta.length === 1 && meta[0].singleton === 1 && meta[0].identity === "still-synthetic-async-transfer" && meta[0].schema_version === 1); transferInteger(meta[0].high_water);
    const rows = db.prepare("SELECT * FROM transfer_proof_attempts").all() as RecordRow[], events = db.prepare("SELECT * FROM transfer_proof_events ORDER BY owner_id,attempt_id,revision").all() as EventRow[], operations = db.prepare("SELECT * FROM transfer_proof_operations").all() as OperationRow[];
    ensure(meta[0].attempt_count === rows.length && meta[0].event_count === events.length && meta[0].operation_count === operations.length && rows.length <= 1000);
    const address = (owner: string, id: string) => canonical([owner, id]), current = new Map<string, TransferAttempt>(), history = new Map<string, { event: EventRow; record: TransferAttempt }[]>();
    const opMap = new Map(operations.map(op => [address(op.owner_id, op.operation_key), op]));
    for (const row of rows) { const record = decodeText(row.record_json); ensure(record.ownerId === row.owner_id && record.id === row.attempt_id && record.revision === row.revision); current.set(address(row.owner_id, row.attempt_id), record); }
    for (const event of events) {
      const record = decodeText(event.record_json), key = address(event.owner_id, event.attempt_id), entries = history.get(key) ?? [], previous = entries.at(-1)?.record;
      ensure(current.has(key) && record.ownerId === event.owner_id && record.id === event.attempt_id && record.revision === event.revision && record.revision === entries.length + 1);
      if (!previous) ensure(event.kind === "create" && record.state === "ready" && record.acknowledgedOffset === 0 && !record.object && !record.pending && !record.lastObservation);
      else {
        ensure(same(record.binding, previous.binding) && record.creationOperationId === previous.creationOperationId && (!previous.stopRequested || record.stopRequested) && (!previous.fenceReason || previous.fenceReason === record.fenceReason) && record.acknowledgedOffset >= previous.acknowledgedOffset);
        if (previous.object) ensure(record.object && record.object.objectId === previous.object.objectId && record.object.sessionId === previous.object.sessionId);
        if (event.kind === "dispatch") ensure(!previous.pending && previous.state === "ready" && record.pending?.intentRevision === record.revision && same(record.pending.before, previous.object) && record.acknowledgedOffset === previous.acknowledgedOffset && record.state === "in_flight");
        if (event.kind === "cancel" || event.kind === "fence") ensure(same(record.pending, previous.pending) && same(record.object, previous.object) && record.acknowledgedOffset === previous.acknowledgedOffset && same(record.lastObservation, previous.lastObservation));
        if (event.kind === "cancel") { const expected = clone(previous); expected.revision++; expected.stopRequested = true; expected.state = expected.pending ? "uncertain" : "cancelled"; ensure(same(expected, record)); }
        if (event.kind === "fence") ensure(record.stopRequested === previous.stopRequested && (!record.leaseExpired || record.pending && Number(meta[0].high_water) >= record.pending.leaseExpiresAt));
        if (event.kind === "acknowledge" || event.kind === "reconcile") ensure(previous.pending && (record.pending ? same(record.pending, previous.pending) && record.acknowledgedOffset === previous.acknowledgedOffset : record.lastObservation?.requestId === previous.pending.requestId));
      }
      if (event.operation_key === null) ensure(event.kind === "fence");
      else { const op = opMap.get(address(event.owner_id, event.operation_key)); ensure(op && op.attempt_id === record.id && op.result_revision === record.revision); }
      entries.push({ event, record }); history.set(key, entries);
    }
    for (const [key, record] of current) ensure(same(history.get(key)?.at(-1)?.record, record));
    for (const op of operations) {
      transferId(op.owner_id); transferId(op.operation_key); ensure(op.descriptor_json.length < 16_384);
      const descriptor = transferObject(JSON.parse(op.descriptor_json), ["action", "attemptId", "command", "status"]);
      ensure(["create", "dispatch", "acknowledge", "cancel", "reconcile"].includes(String(descriptor.action)));
      const command = descriptor.action === "create" ? parseCreateTransfer(descriptor.command) : descriptor.action === "dispatch" ? parseDispatchTransfer(descriptor.command) : parseTransferRevision(descriptor.command);
      ensure(command.idempotencyKey === op.operation_key && same(command, descriptor.command) && canonical(descriptor) === op.descriptor_json && (descriptor.action === "create" ? descriptor.attemptId === null : descriptor.attemptId === op.attempt_id));
      const entries = history.get(address(op.owner_id, op.attempt_id)), entry = entries?.[op.result_revision - 1]; ensure(entry && entry.event.operation_key === op.operation_key && entry.event.kind === descriptor.action);
      if (descriptor.action === "create") ensure(same(entry.record.binding, parseCreateTransfer(descriptor.command).binding) && entry.record.revision === 1 && descriptor.status === null);
      else ensure(parseTransferRevision(descriptor.action === "dispatch" ? { idempotencyKey: command.idempotencyKey, expectedRevision: (command as { expectedRevision: number }).expectedRevision } : descriptor.command).expectedRevision + 1 === entry.record.revision);
      if (descriptor.action === "dispatch") {
        const issued = parseDispatchTransfer(descriptor.command), pending = entry.record.pending!;
        ensure(pending.kind === issued.kind && pending.toOffset - pending.fromOffset === issued.byteLength && pending.leaseExpiresAt - pending.issuedAt === issued.leaseMs && pending.issuedAt <= Number(meta[0].high_water));
      }
      if (descriptor.action === "acknowledge" || descriptor.action === "reconcile") {
        const status = parseStatus(descriptor.status), previous = entries![op.result_revision - 2].record;
        ensure(status.ownerId === op.owner_id && status.attemptId === op.attempt_id && same(status.binding, previous.binding) && previous.pending?.requestId === status.requestId);
        if (descriptor.action === "acknowledge") ensure(previous.state === "in_flight" && !previous.stopRequested && !previous.fenceReason && !previous.leaseExpired && previous.revision === previous.pending!.intentRevision);
        else ensure(previous.stopRequested === entry.record.stopRequested);
        const expected = clone(previous); if (descriptor.action === "reconcile") { expected.fenceReason = entry.record.fenceReason; expected.stopRequested = entry.record.stopRequested; expected.leaseExpired = entry.record.leaseExpired; }
        this.apply(expected, status, descriptor.action === "reconcile"); expected.revision++;
        ensure(same(expected, entry.record));
      } else ensure(descriptor.status === null);
    }
    return false;
  }
}
