/** CP16 generated-stream acquisition proof. Never production source authority. */
import { backupId, backupInteger } from "./backup-recovery.ts";
import type { BackupComponent, BackupSourceRef } from "./backup-recovery.ts";

export const SOURCE_SNAPSHOT_SCOPE = "synthetic_source_snapshot_v1" as const;
export const SOURCE_SNAPSHOT_LIMITS = Object.freeze({ components: 8, componentBytes: 1_048_576, retainedBytes: 8_388_608, readBytes: 65_536, timeoutMs: 30_000 });
export class SourceSnapshotError extends Error {
  readonly code: string;
  constructor(code: string) { super(code); this.name = "SourceSnapshotError"; this.code = code; }
}
export function snapshotAssert(value: unknown, code = "snapshot_invalid_contract"): asserts value { if (!value) throw new SourceSnapshotError(code); }
/** Accessors are rejected without invoking them, including non-enumerable extras. */
export function snapshotData(value: unknown, fields: readonly string[]): Record<string, unknown> {
  snapshotAssert(value !== null && typeof value === "object" && !Array.isArray(value));
  snapshotAssert(Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null);
  const keys = Reflect.ownKeys(value);
  snapshotAssert(keys.length === fields.length && fields.every(key => keys.includes(key)));
  const result: Record<string, unknown> = {};
  for (const key of fields) { const property = Object.getOwnPropertyDescriptor(value, key)!; snapshotAssert(property.enumerable && "value" in property); result[key] = property.value; }
  return result;
}
export function snapshotSource(value: unknown): BackupSourceRef {
  const data = snapshotData(value, ["sourceId", "versionId", "sourceRevision", "componentSetRevision"]);
  return { sourceId: backupId(data.sourceId), versionId: backupId(data.versionId), sourceRevision: backupId(data.sourceRevision), componentSetRevision: backupId(data.componentSetRevision) };
}
export interface SourceSnapshotRequest { source: BackupSourceRef; timeoutMs: number }
export function parseSourceSnapshotRequest(value: unknown): SourceSnapshotRequest {
  const data = snapshotData(value, ["source", "timeoutMs"]);
  return { source: snapshotSource(data.source), timeoutMs: backupInteger(data.timeoutMs, 1, SOURCE_SNAPSHOT_LIMITS.timeoutMs) };
}
/** Opaque runtime identities. Structural/JSON copies have no authority. */
export interface SourceSnapshotSession { readonly scope: typeof SOURCE_SNAPSHOT_SCOPE; readonly testOnly: true }
export interface SourceSnapshotHandle { readonly scope: typeof SOURCE_SNAPSHOT_SCOPE; readonly testOnly: true }
export interface SourceSnapshotDescription {
  readonly schemaVersion: 1; readonly scope: typeof SOURCE_SNAPSHOT_SCOPE;
  readonly testOnly: true; readonly productionCapability: false;
  readonly snapshotId: string; readonly source: Readonly<BackupSourceRef>;
  readonly representation: "generated_synthetic_bytes"; readonly fidelity: "synthetic_fixture_only";
  readonly components: readonly Readonly<BackupComponent>[];
}
export type SnapshotSettlement = "idle" | "released";
