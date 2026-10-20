import { randomUUID, createHash } from "node:crypto";
import { TRANSFER_PROOF_SCOPE, transferAssert, transferBinding, transferId, transferInteger } from "../../packages/contracts/src/backup-transfer-recovery.ts";
import type { SyntheticTransferStatus, TransferAttempt, TransferBinding, TransferObject, TransferPending } from "../../packages/contracts/src/backup-transfer-recovery.ts";

export function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value !== null && typeof value === "object") return `{${Object.entries(value).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([key, item]) => `${JSON.stringify(key)}:${canonical(item)}`).join(",")}}`;
  return JSON.stringify(value);
}
export function fingerprint(value: unknown): string { return createHash("sha256").update(canonical(value)).digest("hex"); }
export function same(a: unknown, b: unknown): boolean { return canonical(a) === canonical(b); }
export interface SyntheticDispatch { readonly scope: typeof TRANSFER_PROOF_SCOPE; readonly testOnly: true }
interface Packet { ownerId: string; attemptId: string; binding: TransferBinding; pending: TransferPending }
const dispatches = new WeakMap<object, { world: SyntheticTransferWorld; packet: Packet; checkAdmitted: () => boolean }>();
const attestations = new WeakMap<object, { world: SyntheticTransferWorld; fingerprint: string }>();
function freezeOwned<T>(value: T): T {
  if (value !== null && typeof value === "object") { for (const item of Object.values(value)) freezeOwned(item); Object.freeze(value); }
  return value;
}
interface RemoteObject { identity: TransferObject; offset: number; finalized: boolean; binding: TransferBinding }
interface Job {
  packet: Packet; outcome: "in_flight" | "applied" | "not_applied";
  object: TransferObject | null; offset: number; finalized: boolean; visible: boolean;
  promise: Promise<SyntheticTransferStatus>; resolve: (status: SyntheticTransferStatus) => void;
}

/** Identity/counter simulation only. Never opens a source, account, file or network connection. */
export class SyntheticTransferWorld {
  private readonly epochs = new Map<string, string | null>();
  private readonly bindings = new Map<string, { ownerId: string; binding: TransferBinding }>();
  private readonly jobs = new Map<string, Job>();
  private readonly objects = new Map<string, RemoteObject>();
  private readonly creations = new Map<string, string>();
  private issued = 0;

  generate(ownerId: string, totalBytes: number): TransferBinding {
    transferId(ownerId); transferInteger(totalBytes, 1, 1_048_576);
    if (!this.epochs.get(ownerId)) this.epochs.set(ownerId, randomUUID());
    const binding: TransferBinding = { connectionEpoch: this.epochs.get(ownerId)!, destinationId: randomUUID(), totalBytes, source: { sourceId: randomUUID(), versionId: randomUUID(), sourceRevision: randomUUID(), componentSetRevision: randomUUID(), componentId: randomUUID(), componentRevision: randomUUID() } };
    this.bindings.set(binding.source.sourceId, { ownerId, binding: structuredClone(binding) });
    return binding;
  }
  reauthorize(ownerId: string): string { transferId(ownerId); const epoch = randomUUID(); this.epochs.set(ownerId, epoch); return epoch; }
  /** Generated fixture rebinding only; a new connection/destination is not a new source. */
  rebindForTest(ownerId: string, original: TransferBinding): TransferBinding {
    const value = this.bindings.get(original.source.sourceId);
    transferAssert(value?.ownerId === ownerId && same(value.binding.source, original.source) && value.binding.totalBytes === original.totalBytes, "transfer_fixture_binding_denied");
    const epoch = this.epochs.get(ownerId); transferAssert(epoch, "transfer_fixture_binding_denied");
    const binding = { ...structuredClone(original), connectionEpoch: epoch, destinationId: randomUUID() };
    value.binding = structuredClone(binding); return binding;
  }
  disconnect(ownerId: string): void { transferId(ownerId); this.epochs.set(ownerId, null); }
  changeSource(ownerId: string, binding: TransferBinding, field: keyof TransferBinding["source"]): void {
    const value = this.bindings.get(binding.source.sourceId); transferAssert(value?.ownerId === ownerId);
    value.binding.source[field] = randomUUID();
  }
  reason(ownerId: string, bindingValue: TransferBinding): TransferAttempt["fenceReason"] {
    const binding = transferBinding(bindingValue), value = this.bindings.get(binding.source.sourceId);
    if (!this.epochs.get(ownerId)) return "disconnected";
    if (this.epochs.get(ownerId) !== binding.connectionEpoch) return "connection_changed";
    if (!value || value.ownerId !== ownerId || !same(value.binding, binding)) return "source_changed";
    return null;
  }
  issue(attempt: TransferAttempt, checkAdmitted: () => boolean): SyntheticDispatch {
    transferAssert(attempt.pending && this.reason(attempt.ownerId, attempt.binding) === null, "transfer_authority_denied");
    const dispatch: SyntheticDispatch = Object.freeze({ scope: TRANSFER_PROOF_SCOPE, testOnly: true });
    dispatches.set(dispatch, { world: this, packet: structuredClone({ ownerId: attempt.ownerId, attemptId: attempt.id, binding: attempt.binding, pending: attempt.pending }), checkAdmitted });
    return dispatch;
  }
  launch(dispatch: SyntheticDispatch): Promise<SyntheticTransferStatus> {
    const value = dispatches.get(dispatch); transferAssert(value?.world === this, "transfer_dispatch_denied");
    const packet = value.packet, previous = this.jobs.get(packet.pending.requestId);
    if (previous) { transferAssert(same(previous.packet, packet), "transfer_request_conflict"); return previous.promise; }
    let admitted = false;
    try { admitted = value.checkAdmitted(); } catch { /* A closed/unavailable origin cannot authorize new work. */ }
    transferAssert(admitted, "transfer_dispatch_not_current");
    let resolve!: (status: SyntheticTransferStatus) => void;
    const promise = new Promise<SyntheticTransferStatus>(done => { resolve = done; });
    this.jobs.set(packet.pending.requestId, { packet, outcome: "in_flight", object: null, offset: packet.pending.fromOffset, finalized: false, visible: true, promise, resolve });
    this.issued++;
    return promise;
  }
  settle(requestId: string, outcome: "applied" | "not_applied", response: "known" | "unknown" = "known"): void {
    const job = this.jobs.get(requestId); transferAssert(job?.outcome === "in_flight", "transfer_request_not_live");
    const { pending, binding } = job.packet;
    if (outcome === "applied") {
      let object: RemoteObject;
      if (pending.kind === "create") {
        const existing = this.creations.get(pending.operationId);
        if (existing) { object = this.objects.get(existing)!; transferAssert(same(object.binding, binding), "transfer_creation_conflict"); }
        else {
          object = { identity: { objectId: randomUUID(), sessionId: randomUUID(), objectRevision: randomUUID() }, offset: 0, finalized: false, binding: structuredClone(binding) };
          this.objects.set(object.identity.objectId, object); this.creations.set(pending.operationId, object.identity.objectId);
        }
      } else {
        transferAssert(pending.before);
        object = this.objects.get(pending.before.objectId)!;
        transferAssert(object && same(object.identity, pending.before) && object.offset === pending.fromOffset && !object.finalized && same(object.binding, binding), "transfer_remote_conflict");
        object.offset = pending.toOffset; object.identity = { ...object.identity, objectRevision: randomUUID() };
        if (pending.kind === "finalize") object.finalized = true;
      }
      job.object = structuredClone(object.identity); job.offset = object.offset; job.finalized = object.finalized;
    } else { job.object = structuredClone(pending.before); job.offset = pending.fromOffset; }
    job.outcome = outcome; job.visible = response === "known";
    job.resolve(this.status(job.packet.ownerId, job.packet.attemptId, job.packet.binding, pending));
  }
  reveal(requestId: string): void { const job = this.jobs.get(requestId); transferAssert(job && job.outcome !== "in_flight"); job.visible = true; }
  status(ownerId: string, attemptId: string, binding: TransferBinding, pending: TransferPending): SyntheticTransferStatus {
    const job = this.jobs.get(pending.requestId);
    let known = job && same(job.packet, { ownerId, attemptId, binding, pending }) && job.visible;
    if (known && job!.outcome === "applied") {
      const actual = this.objects.get(job!.object!.objectId);
      known = !!actual && same(actual.identity, job!.object) && actual.offset === job!.offset && actual.finalized === job!.finalized;
    }
    const outcome = known ? job!.outcome : "unknown";
    return this.attest({ schemaVersion: 1, scope: TRANSFER_PROOF_SCOPE, testOnly: true, productionCapability: false, ownerId, attemptId, binding: structuredClone(binding), requestId: pending.requestId, operationId: pending.operationId, intentRevision: pending.intentRevision, kind: pending.kind, fromOffset: pending.fromOffset, toOffset: pending.toOffset, before: structuredClone(pending.before), outcome, settled: outcome === "applied" || outcome === "not_applied", offset: outcome === "applied" || outcome === "not_applied" ? job!.offset : null, object: outcome === "applied" || outcome === "not_applied" ? structuredClone(job!.object) : null, finalized: outcome === "applied" && job!.finalized });
  }
  statusForTest(dispatch: SyntheticDispatch, overrides: Partial<SyntheticTransferStatus>): SyntheticTransferStatus {
    const value = dispatches.get(dispatch); transferAssert(value?.world === this);
    const packet = value.packet;
    return this.attest({ ...this.status(packet.ownerId, packet.attemptId, packet.binding, packet.pending), ...structuredClone(overrides) });
  }
  accepts(status: unknown): status is SyntheticTransferStatus {
    if (status === null || typeof status !== "object") return false;
    const proof = attestations.get(status); return proof?.world === this && proof.fingerprint === fingerprint(status);
  }
  private attest(value: SyntheticTransferStatus): SyntheticTransferStatus {
    // Own every nested field before attestation; callers cannot swap accessors
    // between the authority check and the ledger's strict data parsing.
    const snapshot = freezeOwned(structuredClone(value));
    attestations.set(snapshot, { world: this, fingerprint: fingerprint(snapshot) }); return snapshot;
  }
  countsForTest(): { requests: number; objects: number } { return { requests: this.issued, objects: this.objects.size }; }
}
