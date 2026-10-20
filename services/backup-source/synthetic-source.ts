/** Generated mutable streams only. No path, file, provider, media or caller-byte input. */
import { createHash, randomUUID } from "node:crypto";
import { backupId, backupInteger } from "../../packages/contracts/src/backup-recovery.ts";
import type { BackupComponent, BackupSourceRef, ComponentRole } from "../../packages/contracts/src/backup-recovery.ts";
import { SOURCE_SNAPSHOT_LIMITS as LIMITS, snapshotAssert, snapshotData, snapshotSource } from "../../packages/contracts/src/backup-source-snapshot.ts";

export function sha256(bytes: Uint8Array): string { return createHash("sha256").update(bytes).digest("hex"); }
export function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value !== null && typeof value === "object") return `{${Object.entries(value).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([key, item]) => `${JSON.stringify(key)}:${canonical(item)}`).join(",")}}`;
  return JSON.stringify(value);
}
export function freezeOwned<T>(value: T): T {
  if (value !== null && typeof value === "object") { for (const field of Object.values(value)) freezeOwned(field); Object.freeze(value); }
  return value;
}
function generated(seed: number, length: number): Uint8Array {
  let state = seed >>> 0; const bytes = new Uint8Array(length);
  for (let i = 0; i < length; i++) { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; bytes[i] = state & 255; }
  return bytes;
}
export interface GeneratedComponentDefinition { role: ComponentRole; seed: number; byteLength: number }
export interface CompleteGeneratedInventory {
  readonly ownerId: string; readonly source: BackupSourceRef;
  readonly completeness: "complete"; readonly components: BackupComponent[];
}
export interface StreamEvent { readonly componentId: string; readonly componentIndex: number; readonly readIndex: number }
export interface GeneratedStreamHooks {
  inspect?: (stage: string) => void;
  read?: (event: StreamEvent) => void | Promise<void>;
  close?: (event: StreamEvent) => void | Promise<void>;
  afterClose?: (event: StreamEvent) => void | Promise<void>;
}
interface Fixture {
  ownerId: string; source: BackupSourceRef; components: BackupComponent[]; bytes: Map<string, Uint8Array>;
  completeness: "complete" | "unknown" | "partial" | "unsupported";
}
interface State {
  fixtures: Map<string, Fixture>; hooks: GeneratedStreamHooks;
  faults: Map<number, "short" | "extra" | "corrupt" | "io" | "zero">;
  open: Set<GeneratedOwnedStream>; opens: number; closes: number;
}
const sources = new WeakMap<object, State>();
const inventories = new WeakMap<object, { source: MutableSyntheticSource; fingerprint: string }>();
function state(source: MutableSyntheticSource): State { const value = sources.get(source); snapshotAssert(value, "snapshot_source_authority_denied"); return value; }
function fixture(source: MutableSyntheticSource, ownerId: string, ref: BackupSourceRef): Fixture {
  backupId(ownerId); const requested = snapshotSource(ref), value = state(source).fixtures.get(requested.sourceId);
  snapshotAssert(value && value.ownerId === ownerId && canonical(value.source) === canonical(requested), "snapshot_source_changed"); return value;
}

export class MutableSyntheticSource {
  constructor() { sources.set(this, { fixtures: new Map(), hooks: {}, faults: new Map(), open: new Set(), opens: 0, closes: 0 }); }
  generate(ownerId: string, definitions: readonly GeneratedComponentDefinition[]): BackupSourceRef {
    backupId(ownerId); snapshotAssert(Array.isArray(definitions) && definitions.length >= 1 && definitions.length <= LIMITS.components);
    const bytes = new Map<string, Uint8Array>();
    const components = Array.from(definitions).map(value => {
      const data = snapshotData(value, ["role", "seed", "byteLength"]);
      snapshotAssert(data.role === "primary" || data.role === "motion" || data.role === "sidecar");
      const payload = generated(backupInteger(data.seed, 1, 0xffff_ffff), backupInteger(data.byteLength, 1, LIMITS.componentBytes)), componentId = randomUUID();
      bytes.set(componentId, payload);
      return { componentId, componentRevision: randomUUID(), role: data.role, byteLength: payload.length, sha256: sha256(payload) } as BackupComponent;
    });
    snapshotAssert(components.filter(item => item.role === "primary").length === 1);
    const ref = { sourceId: randomUUID(), versionId: randomUUID(), sourceRevision: randomUUID(), componentSetRevision: randomUUID() };
    state(this).fixtures.set(ref.sourceId, { ownerId, source: ref, components, bytes, completeness: "complete" });
    return structuredClone(ref);
  }
  inventoryForTest(ownerId: string, ref: BackupSourceRef): CompleteGeneratedInventory { return inspectGeneratedInventory(this, ownerId, ref, "test"); }
  hooksForTest(hooks: GeneratedStreamHooks): void { state(this).hooks = { ...hooks }; }
  faultForTest(componentIndex: number, fault: "short" | "extra" | "corrupt" | "io" | "zero"): void { state(this).faults.set(backupInteger(componentIndex, 0, LIMITS.components - 1), fault); }
  completenessForTest(ownerId: string, ref: BackupSourceRef, completeness: Fixture["completeness"]): void { fixture(this, ownerId, ref).completeness = completeness; }
  changeForTest(ownerId: string, ref: BackupSourceRef, change: "source" | "version" | "set" | "component" | "remove" | "add" | "reorder" | "bytes"): void {
    const value = fixture(this, ownerId, ref);
    if (change === "source") value.source.sourceRevision = randomUUID();
    else if (change === "version") value.source.versionId = randomUUID();
    else if (change === "set") value.source.componentSetRevision = randomUUID();
    else if (change === "component") value.components[0].componentRevision = randomUUID();
    else if (change === "remove") { const removed = value.components.pop()!; value.bytes.delete(removed.componentId); }
    else if (change === "reorder") value.components.reverse();
    else if (change === "bytes") {
      const component = value.components[0], bytes = value.bytes.get(component.componentId)!;
      bytes[0] ^= 255; component.sha256 = sha256(bytes); component.componentRevision = randomUUID();
    }
    else {
      const data = generated(42, 33), componentId = randomUUID(); value.bytes.set(componentId, data);
      value.components.push({ componentId, componentRevision: randomUUID(), role: "sidecar", byteLength: data.length, sha256: sha256(data) });
    }
  }
  countsForTest(): { opens: number; closes: number; unsettled: number } { const value = state(this); return { opens: value.opens, closes: value.closes, unsettled: value.open.size }; }
}

/** Authority is bound to the registered generator and one frozen, owned inventory. */
export function inspectGeneratedInventory(source: MutableSyntheticSource, ownerId: string, ref: BackupSourceRef, stage: string): CompleteGeneratedInventory {
  const current = state(source); current.hooks.inspect?.(stage);
  const value = fixture(source, ownerId, ref);
  snapshotAssert(value.completeness === "complete", "snapshot_inventory_incomplete");
  snapshotAssert(value.components.length >= 1 && value.components.length <= LIMITS.components && value.components.filter(item => item.role === "primary").length === 1 && new Set(value.components.map(item => item.componentId)).size === value.components.length, "snapshot_inventory_incomplete");
  const inventory: CompleteGeneratedInventory = freezeOwned(structuredClone({ ownerId, source: value.source, completeness: "complete" as const, components: value.components }));
  inventories.set(inventory, { source, fingerprint: canonical(inventory) }); return inventory;
}
export function acceptsGeneratedInventory(source: MutableSyntheticSource, inventory: unknown): inventory is CompleteGeneratedInventory {
  if (inventory === null || typeof inventory !== "object") return false;
  const proof = inventories.get(inventory); return proof?.source === source && proof.fingerprint === canonical(inventory);
}

/** Owned generated read lifecycle; close failures may leave it physically open. */
export class GeneratedOwnedStream {
  private position = 0; private reads = 0; private closed = false;
  private readonly current: State; private readonly bytes: Uint8Array; private readonly event: Omit<StreamEvent, "readIndex">;
  private readonly fault: "short" | "extra" | "corrupt" | "io" | "zero" | undefined;
  constructor(current: State, bytes: Uint8Array, event: Omit<StreamEvent, "readIndex">, fault: GeneratedOwnedStream["fault"]) { this.current = current; this.bytes = bytes; this.event = event; this.fault = fault; }
  async read(maxBytes: number): Promise<Uint8Array | null> {
    snapshotAssert(!this.closed, "snapshot_stream_closed"); backupInteger(maxBytes, 1, LIMITS.readBytes);
    await this.current.hooks.read?.({ ...this.event, readIndex: this.reads++ });
    snapshotAssert(!this.closed, "snapshot_stream_closed");
    if (this.fault === "io") throw new Error("generated read failure");
    if (this.fault === "zero") return new Uint8Array();
    const length = this.bytes.length + (this.fault === "extra" ? 1 : this.fault === "short" ? -1 : 0);
    if (this.position >= length) return null;
    const count = Math.min(maxBytes, length - this.position), payload = new Uint8Array(count);
    for (let i = 0; i < count; i++) payload[i] = this.bytes[this.position + i] ?? 0;
    if (this.fault === "corrupt" && this.position === 0) payload[0] ^= 255;
    this.position += count; return payload;
  }
  async close(): Promise<void> {
    if (this.closed) return;
    await this.current.hooks.close?.({ ...this.event, readIndex: this.reads });
    this.closed = true; this.current.open.delete(this); this.current.closes++;
    await this.current.hooks.afterClose?.({ ...this.event, readIndex: this.reads });
  }
}
export function openGeneratedStream(source: MutableSyntheticSource, ownerId: string, ref: BackupSourceRef, component: BackupComponent): GeneratedOwnedStream {
  const current = state(source), value = fixture(source, ownerId, ref), index = value.components.findIndex(item => item.componentId === component.componentId);
  snapshotAssert(index >= 0 && canonical(value.components[index]) === canonical(component), "snapshot_inventory_changed");
  const bytes = value.bytes.get(component.componentId); snapshotAssert(bytes, "snapshot_component_missing");
  const stream = new GeneratedOwnedStream(current, bytes, { componentId: component.componentId, componentIndex: index }, current.faults.get(index));
  current.open.add(stream); current.opens++; return stream;
}
