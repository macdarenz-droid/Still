/** CP15 synthetic asynchronous protocol proof. Never a production transfer receipt. */
export const TRANSFER_PROOF_SCOPE = "synthetic_async_transfer_v1" as const;
export type TransferKind = "create" | "append" | "finalize";
export type TransferState = "ready" | "in_flight" | "uncertain" | "uploaded_unverified" | "cancelled" | "fenced";
export interface TransferSource {
  sourceId: string; versionId: string; sourceRevision: string; componentSetRevision: string;
  componentId: string; componentRevision: string;
}
export interface TransferBinding { connectionEpoch: string; destinationId: string; source: TransferSource; totalBytes: number }
export interface TransferObject { objectId: string; sessionId: string; objectRevision: string }
export interface TransferPending {
  requestId: string; operationId: string; kind: TransferKind; intentRevision: number;
  fromOffset: number; toOffset: number; before: TransferObject | null; issuedAt: number; leaseExpiresAt: number;
}
export interface TransferObservation {
  requestId: string; outcome: "applied" | "not_applied"; offset: number;
  object: TransferObject | null; finalized: boolean;
}
export interface TransferAttempt {
  schemaVersion: 1; scope: typeof TRANSFER_PROOF_SCOPE; testOnly: true; productionCapability: false;
  ownerId: string; id: string; revision: number; binding: TransferBinding; creationOperationId: string;
  state: TransferState; acknowledgedOffset: number; object: TransferObject | null;
  pending: TransferPending | null; stopRequested: boolean;
  fenceReason: "connection_changed" | "disconnected" | "source_changed" | null;
  leaseExpired: boolean; lastObservation: TransferObservation | null;
}
export interface TransferResult {
  attempt: TransferAttempt;
  /** Original historical result; never a new dispatch or live eligibility. */
  replayed: boolean; testOnly: true; productionCapability: false;
}
export interface CreateTransfer { idempotencyKey: string; binding: TransferBinding }
export interface TransferRevision { idempotencyKey: string; expectedRevision: number }
export interface DispatchTransfer extends TransferRevision { kind: TransferKind; byteLength: number; leaseMs: number }
/** A status is authoritative only when minted by the installed synthetic world. JSON is insufficient. */
export interface SyntheticTransferStatus {
  schemaVersion: 1; scope: typeof TRANSFER_PROOF_SCOPE; testOnly: true; productionCapability: false;
  ownerId: string; attemptId: string; binding: TransferBinding; requestId: string; operationId: string;
  intentRevision: number; kind: TransferKind; fromOffset: number; toOffset: number;
  before: TransferObject | null;
  outcome: "unknown" | "in_flight" | "applied" | "not_applied";
  /** True only after the modeled remote operation has physically settled. */
  settled: boolean; offset: number | null; object: TransferObject | null; finalized: boolean;
}
export class TransferProofError extends Error {
  readonly code: string;
  constructor(code: string) { super(code); this.name = "TransferProofError"; this.code = code; }
}
export function transferAssert(condition: unknown, code = "transfer_invalid_contract"): asserts condition { if (!condition) throw new TransferProofError(code); }
export function transferObject(value: unknown, keys: readonly string[]): Record<string, unknown> {
  transferAssert(value !== null && typeof value === "object" && !Array.isArray(value));
  const object = value as Record<string, unknown>;
  transferAssert(Object.getPrototypeOf(object) === Object.prototype || Object.getPrototypeOf(object) === null);
  transferAssert(Object.keys(object).length === keys.length && keys.every(key => Object.hasOwn(object, key)));
  return object;
}
export function transferId(value: unknown): string { transferAssert(typeof value === "string" && /^[A-Za-z0-9_-]{1,96}$/.test(value)); return value; }
export function transferInteger(value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): number { transferAssert(typeof value === "number" && Number.isSafeInteger(value) && value >= min && value <= max); return value; }
export function transferSource(value: unknown): TransferSource {
  const data = transferObject(value, ["sourceId", "versionId", "sourceRevision", "componentSetRevision", "componentId", "componentRevision"]);
  return { sourceId: transferId(data.sourceId), versionId: transferId(data.versionId), sourceRevision: transferId(data.sourceRevision), componentSetRevision: transferId(data.componentSetRevision), componentId: transferId(data.componentId), componentRevision: transferId(data.componentRevision) };
}
export function transferBinding(value: unknown): TransferBinding {
  const data = transferObject(value, ["connectionEpoch", "destinationId", "source", "totalBytes"]);
  return { connectionEpoch: transferId(data.connectionEpoch), destinationId: transferId(data.destinationId), source: transferSource(data.source), totalBytes: transferInteger(data.totalBytes, 1, 1_048_576) };
}
export function transferProviderObject(value: unknown): TransferObject {
  const data = transferObject(value, ["objectId", "sessionId", "objectRevision"]);
  return { objectId: transferId(data.objectId), sessionId: transferId(data.sessionId), objectRevision: transferId(data.objectRevision) };
}
export function parseCreateTransfer(value: unknown): CreateTransfer {
  const data = transferObject(value, ["idempotencyKey", "binding"]);
  return { idempotencyKey: transferId(data.idempotencyKey), binding: transferBinding(data.binding) };
}
export function parseTransferRevision(value: unknown): TransferRevision {
  const data = transferObject(value, ["idempotencyKey", "expectedRevision"]);
  return { idempotencyKey: transferId(data.idempotencyKey), expectedRevision: transferInteger(data.expectedRevision, 1) };
}
export function parseDispatchTransfer(value: unknown): DispatchTransfer {
  const data = transferObject(value, ["idempotencyKey", "expectedRevision", "kind", "byteLength", "leaseMs"]);
  transferAssert(data.kind === "create" || data.kind === "append" || data.kind === "finalize");
  const byteLength = transferInteger(data.byteLength, 0, 65_536);
  transferAssert(data.kind === "append" ? byteLength > 0 : byteLength === 0);
  return { idempotencyKey: transferId(data.idempotencyKey), expectedRevision: transferInteger(data.expectedRevision, 1), kind: data.kind, byteLength, leaseMs: transferInteger(data.leaseMs, 1, 300_000) };
}
