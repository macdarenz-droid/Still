import { randomUUID } from "node:crypto";
import { backupId, backupInteger } from "../../packages/contracts/src/backup-recovery.ts";
import {
  SOURCE_SNAPSHOT_SCOPE as SCOPE, SOURCE_SNAPSHOT_LIMITS as LIMITS,
  parseSourceSnapshotRequest, snapshotAssert, SourceSnapshotError,
} from "../../packages/contracts/src/backup-source-snapshot.ts";
import type { SourceSnapshotDescription, SourceSnapshotHandle, SourceSnapshotRequest, SourceSnapshotSession, SnapshotSettlement } from "../../packages/contracts/src/backup-source-snapshot.ts";
import { acceptsGeneratedInventory, canonical, freezeOwned, GeneratedOwnedStream, inspectGeneratedInventory, MutableSyntheticSource, openGeneratedStream, sha256 } from "./synthetic-source.ts";
import type { CompleteGeneratedInventory } from "./synthetic-source.ts";

interface Holder {
  controller: SourceSnapshotAcquisition; ownerId: string; source: MutableSyntheticSource;
  request: SourceSnapshotRequest; session: SourceSnapshotSession; checkCurrent: () => boolean;
  deadline: number; active: boolean; working: boolean; started: boolean; settling: boolean;
  inventory: CompleteGeneratedInventory | null; streams: Set<GeneratedOwnedStream>;
  buffers: Map<string, Uint8Array>; retainedBytes: number;
  handle: SourceSnapshotHandle | null; description: SourceSnapshotDescription | null;
}
// Intentionally process-wide: recreating a controller cannot discard an old
// blocked read/close or acquire a second retained snapshot allocation.
let retained: Holder | null = null;
export interface SourceSnapshotOptions { source?: MutableSyntheticSource; now?: () => number }

export class SourceSnapshotAcquisition {
  private readonly source: MutableSyntheticSource | undefined;
  private readonly now: () => number; private highWater = 0; private disposed = false;
  constructor(options: SourceSnapshotOptions = {}) { this.source = options.source; this.now = options.now ?? Date.now; }
  begin(ownerId: string, value: unknown, checkCurrent: () => boolean): SourceSnapshotSession {
    backupId(ownerId); const request = parseSourceSnapshotRequest(value);
    snapshotAssert(!this.disposed, "snapshot_session_denied");
    snapshotAssert(this.source instanceof MutableSyntheticSource, "snapshot_source_authority_denied");
    snapshotAssert(typeof checkCurrent === "function", "snapshot_authority_check_required");
    snapshotAssert(retained === null, "snapshot_busy");
    const session = Object.freeze({ scope: SCOPE, testOnly: true as const });
    const holder: Holder = { controller: this, ownerId, source: this.source, request, session, checkCurrent, deadline: backupInteger(this.time() + request.timeoutMs), active: true, working: false, started: false, settling: false, inventory: null, streams: new Set(), buffers: new Map(), retainedBytes: 0, handle: null, description: null };
    retained = holder;
    try { this.check(holder); } catch (error) { this.retire(holder); throw error; }
    return session;
  }
  async acquire(session: SourceSnapshotSession): Promise<SourceSnapshotHandle> {
    const holder = this.session(session);
    snapshotAssert(!holder.started && !holder.working, "snapshot_busy");
    holder.started = true; holder.working = true;
    try {
      this.check(holder);
      const inventory = inspectGeneratedInventory(holder.source, holder.ownerId, holder.request.source, "initial");
      snapshotAssert(acceptsGeneratedInventory(holder.source, inventory), "snapshot_source_authority_denied");
      holder.inventory = inventory;
      snapshotAssert(inventory.components.reduce((sum, item) => sum + item.byteLength, 0) <= LIMITS.retainedBytes, "snapshot_resource_limit");
      for (const component of inventory.components) {
        this.fence(holder, "before_component");
        snapshotAssert(component.byteLength >= 1 && component.byteLength <= LIMITS.componentBytes && holder.retainedBytes + component.byteLength <= LIMITS.retainedBytes, "snapshot_resource_limit");
        const buffer = new Uint8Array(component.byteLength);
        holder.buffers.set(component.componentId, buffer); holder.retainedBytes += buffer.length;
        const stream = openGeneratedStream(holder.source, holder.ownerId, holder.request.source, component);
        holder.streams.add(stream); let offset = 0;
        try {
          while (true) {
            this.check(holder);
            const chunk = await stream.read(LIMITS.readBytes);
            this.check(holder);
            if (chunk === null) break;
            snapshotAssert(chunk.length > 0 && chunk.length <= LIMITS.readBytes, "snapshot_read_invalid");
            snapshotAssert(offset + chunk.length <= component.byteLength, "snapshot_length_mismatch");
            buffer.set(chunk, offset); offset += chunk.length;
          }
          snapshotAssert(offset === component.byteLength, "snapshot_length_mismatch");
          snapshotAssert(sha256(buffer) === component.sha256, "snapshot_hash_mismatch");
        } finally {
          // Close uncertainty dominates even a suppressed read/cancellation error.
          // Leave the stream retained until an explicit successful close retry.
          try { await stream.close(); holder.streams.delete(stream); }
          catch { throw new SourceSnapshotError("snapshot_close_unsettled"); }
        }
        this.fence(holder, "after_component");
      }
      this.fence(holder, "before_publication");
      const description: SourceSnapshotDescription = freezeOwned(structuredClone({ schemaVersion: 1 as const, scope: SCOPE, testOnly: true as const, productionCapability: false as const, snapshotId: randomUUID(), source: inventory.source, representation: "generated_synthetic_bytes" as const, fidelity: "synthetic_fixture_only" as const, components: inventory.components }));
      const handle = Object.freeze({ scope: SCOPE, testOnly: true as const });
      holder.description = description; holder.handle = handle;
      return handle;
    } catch (error) {
      holder.active = false;
      if (holder.streams.size) throw new SourceSnapshotError("snapshot_close_unsettled");
      throw error instanceof SourceSnapshotError ? error : new SourceSnapshotError("snapshot_read_failed");
    } finally {
      holder.working = false;
      if (!holder.active) this.retire(holder);
    }
  }
  /** Current synthetic acquisition only; not original fidelity or transfer authority. */
  describe(ownerId: string, handle: SourceSnapshotHandle): SourceSnapshotDescription {
    const holder = this.handle(ownerId, handle); this.fence(holder, "describe");
    return freezeOwned(structuredClone(holder.description!));
  }
  /** Independent private-buffer readback. No bytes escape the fixture boundary. */
  verifyContentsForTest(ownerId: string, handle: SourceSnapshotHandle): SourceSnapshotDescription {
    const description = this.describe(ownerId, handle), holder = this.handle(ownerId, handle);
    for (const component of description.components) {
      const bytes = holder.buffers.get(component.componentId);
      snapshotAssert(bytes && bytes.length === component.byteLength && sha256(bytes) === component.sha256, "snapshot_hash_mismatch");
    }
    return description;
  }
  cancel(session: SourceSnapshotSession): void { const holder = this.session(session); holder.active = false; this.retire(holder); }
  release(ownerId: string, handle: SourceSnapshotHandle): void { const holder = this.handle(ownerId, handle); holder.active = false; this.retire(holder); }
  dispose(): void { this.disposed = true; if (retained?.controller === this) { retained.active = false; this.retire(retained); } }
  /** Can clean the retained original owner after controller recreation, never live work. */
  async settle(ownerId: string): Promise<SnapshotSettlement> {
    backupId(ownerId); const holder = retained; if (!holder) return "idle";
    snapshotAssert(holder.ownerId === ownerId && !holder.active && !holder.working && !holder.settling, "snapshot_busy");
    holder.settling = true;
    try {
      for (const stream of [...holder.streams]) {
        try { await stream.close(); holder.streams.delete(stream); } catch { throw new SourceSnapshotError("snapshot_close_unsettled"); }
      }
      return "released";
    } finally { holder.settling = false; this.retire(holder); }
  }
  retainedBytesForTest(): number { return retained?.retainedBytes ?? 0; }
  private time(): number { this.highWater = Math.max(this.highWater, backupInteger(this.now())); return this.highWater; }
  private check(holder: Holder): void {
    snapshotAssert(retained === holder && holder.active && !holder.controller.disposed, "snapshot_cancelled");
    if (holder.controller.time() >= holder.deadline) { holder.active = false; throw new SourceSnapshotError("snapshot_timeout"); }
    let current = false; try { current = holder.checkCurrent() === true; } catch { /* deny an unavailable authority */ }
    if (!current) holder.active = false;
    snapshotAssert(holder.active && retained === holder && !holder.controller.disposed, "snapshot_cancelled");
  }
  private fence(holder: Holder, stage: string): void {
    this.check(holder);
    const current = inspectGeneratedInventory(holder.source, holder.ownerId, holder.request.source, stage);
    snapshotAssert(holder.active && retained === holder, "snapshot_cancelled");
    snapshotAssert(acceptsGeneratedInventory(holder.source, current) && canonical(current) === canonical(holder.inventory), "snapshot_inventory_changed");
  }
  private session(value: SourceSnapshotSession): Holder { snapshotAssert(retained?.controller === this && retained.session === value, "snapshot_session_denied"); return retained; }
  private handle(ownerId: string, value: SourceSnapshotHandle): Holder { backupId(ownerId); snapshotAssert(retained?.controller === this && retained.ownerId === ownerId && retained.handle === value && retained.description !== null, "snapshot_handle_denied"); return retained; }
  private retire(holder: Holder): void {
    if (holder.working || holder.settling || holder.streams.size) return;
    for (const bytes of holder.buffers.values()) bytes.fill(0);
    holder.buffers.clear(); holder.retainedBytes = 0;
    if (retained === holder) retained = null;
  }
}
