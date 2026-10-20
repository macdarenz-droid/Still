/** CP18 fixture coordinator. No provider, media ingestion, UI or production export. */
import { randomUUID } from "node:crypto";
import { BackupRecoveryError, backupId } from "../../packages/contracts/src/backup-recovery.ts";
import type { PortableBackupArchive } from "../../packages/contracts/src/backup-recovery.ts";
import type { SourceSnapshotHandle } from "../../packages/contracts/src/backup-source-snapshot.ts";
import type { TransferAttempt, TransferBinding, TransferKind, SyntheticTransferStatus } from "../../packages/contracts/src/backup-transfer-recovery.ts";
import { SourceSnapshotAcquisition, releaseSyntheticSnapshotBorrow } from "../backup-source/acquisition.ts";
import type { SyntheticSnapshotBorrow } from "../backup-source/acquisition.ts";
import { TransferRecoveryLedger, createTransferJournalFile, disposeTransferJournalFile } from "../backup-transfer/ledger.ts";
import type { DispatchResult, TransferJournalFile } from "../backup-transfer/ledger.ts";
import { SyntheticTransferWorld } from "../backup-transfer/synthetic-world.ts";
import type { SyntheticTransferredSet } from "../backup-transfer/synthetic-world.ts";
import { SyntheticObjectStore } from "../backup/synthetic-store.ts";

function assert(value: unknown, code: string): asserts value { if (!value) throw new BackupRecoveryError(code); }
// Successful fixture-wide claims outlive handles, journals and worlds. A new
// coordinator is not authorization to create another copy of the same source.
// There is no release/reset operation until an explicit duplicate-copy policy exists.
const reservedSources = new Map<string, symbol>();
interface Request { issued: TransferAttempt; completion: Promise<SyntheticTransferStatus> }
export interface ComposedPipelineOptions { world?: SyntheticTransferWorld; now?: () => number }

export class ComposedSyntheticPipeline {
  readonly testOnly = true; readonly productionCapability = false;
  readonly world: SyntheticTransferWorld;
  readonly readbackStore: SyntheticObjectStore;
  private readonly ownerId: string; private readonly snapshots: SourceSnapshotAcquisition;
  private readonly handle: SourceSnapshotHandle; private readonly borrow: SyntheticSnapshotBorrow;
  private readonly file: TransferJournalFile; private ledger: TransferRecoveryLedger | null;
  private readonly now: () => number; private readonly bindings: TransferBinding[];
  private readonly ids = new Map<string, string>(); private readonly requests = new Map<string, Request>();
  private readonly dispatches = new Map<string, { componentId: string; kind: TransferKind; byteLength: number; expectedRevision: number }>();
  private archive: PortableBackupArchive | null = null; private released = false; private cancelled = false; private disposed = false;
  private constructor(ownerId: string, snapshots: SourceSnapshotAcquisition, handle: SourceSnapshotHandle, options: ComposedPipelineOptions) {
    this.ownerId = backupId(ownerId); this.snapshots = snapshots; this.handle = handle;
    const accepted = snapshots.describe(this.ownerId, handle);
    const sourceKeys = accepted.components.map(item => JSON.stringify([this.ownerId, accepted.source.sourceId, accepted.source.versionId, accepted.source.sourceRevision,
      accepted.source.componentSetRevision, item.componentId, item.componentRevision])).sort();
    assert(sourceKeys.every(key => !reservedSources.has(key)), "pipeline_source_already_tracked");
    const claim = Symbol(); for (const key of sourceKeys) reservedSources.set(key, claim);
    this.ledger = null; this.bindings = [];
    let file: TransferJournalFile | undefined, store: SyntheticObjectStore | undefined, borrow: SyntheticSnapshotBorrow | undefined;
    try {
      this.borrow = borrow = snapshots.borrowForSyntheticPipeline(ownerId, handle);
      this.world = options.world ?? new SyntheticTransferWorld(); this.now = options.now ?? Date.now;
      this.readbackStore = store = new SyntheticObjectStore(); this.file = file = createTransferJournalFile();
      this.bindings = this.world.bindSnapshotForSyntheticPipeline(ownerId, this.borrow);
      this.ledger = new TransferRecoveryLedger(this.file, { world: this.world, now: this.now });
      for (const binding of this.bindings) {
        const record = this.ledger.create(ownerId, { idempotencyKey: `pipeline_${binding.source.componentId}`, binding }).attempt;
        this.ids.set(binding.source.componentId, record.id);
      }
    } catch (error) {
      // Construction never dispatches. Only a fully cleaned pre-effect failure
      // may roll back its own provisional claims; returned pipelines never do.
      let cleaned = true;
      for (const cleanup of [() => this.ledger?.close(), () => { if (file) disposeTransferJournalFile(file); },
        () => { if (borrow) releaseSyntheticSnapshotBorrow(ownerId, borrow); }, () => store?.disposeForTest()]) {
        try { cleanup(); } catch { cleaned = false; }
      }
      if (cleaned) for (const key of sourceKeys) if (reservedSources.get(key) === claim) reservedSources.delete(key);
      throw error;
    }
  }
  static fromSnapshot(ownerId: string, snapshots: SourceSnapshotAcquisition, handle: SourceSnapshotHandle, options: ComposedPipelineOptions = {}): ComposedSyntheticPipeline {
    return new ComposedSyntheticPipeline(ownerId, snapshots, handle, options);
  }
  componentBindings(): TransferBinding[] { return structuredClone(this.bindings); }
  state(componentId: string): TransferAttempt { return this.journal().get(this.ownerId, this.id(componentId)); }
  dispatch(componentId: string, kind: TransferKind, byteLength = 0, idempotencyKey: string = randomUUID()): DispatchResult {
    assert(!this.cancelled && !this.released, "pipeline_withdrawn");
    const record = this.state(componentId), previous = this.dispatches.get(idempotencyKey);
    assert(!previous || previous.componentId === componentId && previous.kind === kind && previous.byteLength === byteLength, "pipeline_idempotency_conflict");
    const expectedRevision = previous?.expectedRevision ?? record.revision;
    const issued = this.journal().dispatch(this.ownerId, record.id, { idempotencyKey, expectedRevision, kind, byteLength, leaseMs: 30_000 });
    this.dispatches.set(idempotencyKey, { componentId, kind, byteLength, expectedRevision });
    if (issued.dispatch) this.requests.set(issued.attempt.pending!.requestId, { issued: issued.attempt, completion: this.world.launch(issued.dispatch) });
    return issued;
  }
  async acknowledge(requestId: string): Promise<TransferAttempt> {
    const request = this.requests.get(requestId); assert(request, "pipeline_request_unknown");
    const receipt = await request.completion;
    return this.journal().acknowledge(this.ownerId, request.issued.id, { idempotencyKey: `ack_${requestId}`, expectedRevision: request.issued.revision }, receipt).attempt;
  }
  async complete(componentId: string, kind: TransferKind, byteLength = 0, response: "known" | "unknown" = "known", outcome: "applied" | "not_applied" = "applied"): Promise<TransferAttempt> {
    const issued = this.dispatch(componentId, kind, byteLength), requestId = issued.attempt.pending!.requestId;
    this.world.settle(requestId, outcome, response); return this.acknowledge(requestId);
  }
  reconcile(componentId: string, idempotencyKey: string = randomUUID()): TransferAttempt {
    const record = this.state(componentId);
    return this.journal().reconcile(this.ownerId, record.id, { idempotencyKey, expectedRevision: record.revision }).attempt;
  }
  cancel(): void {
    this.cancelled = true;
    try {
      for (const [componentId] of this.ids) {
        const record = this.state(componentId);
        this.journal().cancel(this.ownerId, record.id, { idempotencyKey: randomUUID(), expectedRevision: record.revision });
      }
    } finally {
      // Deactivate task reads immediately; borrows retain allocation until the
      // original physical effect and uncertainty settle, without restoring access.
      if (!this.released) this.snapshots.release(this.ownerId, this.handle);
    }
  }
  closeForRestart(): void { this.journal().close(); this.ledger = null; }
  reopen(): void { assert(!this.disposed && this.ledger === null, "pipeline_journal_open"); this.ledger = new TransferRecoveryLedger(this.file, { world: this.world, now: this.now }); }
  verify(): PortableBackupArchive {
    if (this.archive) { this.readbackStore.verify(this.ownerId, this.archive); return structuredClone(this.archive); }
    assert(!this.cancelled && !this.released, "pipeline_withdrawn");
    const readback = this.readbackForTest();
    const archive = this.readbackStore.importTransferredForSyntheticPipeline(this.ownerId, readback);
    this.readbackStore.verify(this.ownerId, archive); this.archive = structuredClone(archive); return structuredClone(archive);
  }
  readbackForTest(): SyntheticTransferredSet {
    assert(!this.cancelled && !this.released, "pipeline_withdrawn");
    for (const [componentId] of this.ids) assert(this.state(componentId).state === "uploaded_unverified", "pipeline_components_unverified");
    return this.world.readbackForSyntheticPipeline(this.ownerId, this.borrow);
  }
  releaseSnapshot(): void {
    assert(!this.released, "pipeline_snapshot_released");
    for (const [componentId] of this.ids) assert(!this.state(componentId).pending, "pipeline_reconciliation_required");
    this.snapshots.release(this.ownerId, this.handle); releaseSyntheticSnapshotBorrow(this.ownerId, this.borrow); this.released = true;
  }
  restore(restoreId: string): PortableBackupArchive {
    assert(this.archive, "pipeline_not_verified");
    assert(this.released, "pipeline_snapshot_release_required");
    this.readbackStore.verify(this.ownerId, this.archive);
    this.readbackStore.restore(this.ownerId, restoreId, this.archive);
    assert(this.readbackStore.verifyRestore(this.ownerId, restoreId, this.archive.manifest) === "complete", "pipeline_restore_unverified");
    this.readbackStore.verify(this.ownerId, this.archive); return structuredClone(this.archive);
  }
  disposeForTest(): void {
    if (this.disposed) return;
    if (!this.ledger) this.reopen();
    if (!this.released) this.releaseSnapshot();
    this.journal().close(); this.ledger = null;
    this.readbackStore.settleForTest(); this.readbackStore.disposeForTest(); disposeTransferJournalFile(this.file); this.disposed = true;
  }
  private journal(): TransferRecoveryLedger { assert(this.ledger && !this.disposed, "pipeline_journal_closed"); return this.ledger; }
  private id(componentId: string): string { const id = this.ids.get(backupId(componentId)); assert(id, "pipeline_component_unknown"); return id; }
}
