import { fault } from "./index.ts";

/** Development-only metadata. No value in this module grants native execution. */
export interface CleanupSourceRef {
  assetId: string;
  versionId: string;
  sourceId: string;
  expectedSourceRevision: number;
}
export interface CleanupWrite { schemaVersion: 1; idempotencyKey: string }
export interface CreateCleanupPlan extends CleanupWrite {
  intent: "trash_specified_sources";
  targets: CleanupSourceRef[];
  keepers: CleanupSourceRef[];
  expiresInMs: number;
}
export interface CleanupRevisionCommand extends CleanupWrite { expectedRevision: number }
export interface ConfirmCleanupPlan extends CleanupRevisionCommand { expectedReviewFingerprint: string }
export interface CleanupItemCommand extends CleanupRevisionCommand { expectedItemRevision: number }
export interface MarkCleanupUncertain extends CleanupItemCommand { uncertaintyId: string }

export const CLEANUP_BLOCKERS = [
  "evidence_unavailable", "evidence_invalid", "evidence_expired", "source_unpinned",
  "representation_unverified", "access_unavailable", "protected", "favorite",
  "protection_unknown", "favorite_unknown", "dependency_unknown", "required_dependency",
  "copy_unverified", "keeper_unavailable", "source_alias", "review_changed",
  "uncertainty", "cancelled", "expired",
] as const;
export type CleanupBlocker = typeof CLEANUP_BLOCKERS[number];
export type CleanupPlanState = "blocked" | "reviewable" | "confirmed_nonexecuting" | "uncertain" | "cancelled" | "expired";
export type CleanupItemState = CleanupPlanState | "reconciled_no_change";

/** Constructor-only adapter facts. Requests cannot supply this evidence. */
export interface CleanupSourceEvidence {
  ref: CleanupSourceRef;
  physicalSourceId: string;
  pinProofId: string | null;
  representation: "original_bytes" | "other" | "unknown";
  access: "granted" | "denied" | "unknown";
  protected: boolean | null;
  favorite: boolean | null;
  requiredDependency: boolean | null;
}
export interface CleanupCopyEvidence {
  targetSourceId: string;
  keeperSourceId: string;
  versionId: string;
  identityProofId: string;
  independenceProofId: string;
  restoreProofId: string;
}
export interface CleanupEvidence {
  schemaVersion: 1;
  ownerId: string;
  evidenceRevision: number;
  verifiedAt: number;
  /** Evidence deadline, not a native trash/restore window. */
  validUntil: number;
  protectionRevision: number;
  favoriteRevision: number;
  dependencyRevision: number;
  copyRevision: number;
  sources: CleanupSourceEvidence[];
  copies: CleanupCopyEvidence[];
}
export interface CleanupNoChangeEvidence {
  schemaVersion: 1;
  ownerId: string;
  ref: CleanupSourceRef;
  uncertaintyId: string;
  result: "verified_no_change";
  proofId: string;
  verifiedAt: number;
  validUntil: number;
}
export interface CleanupUncertainty {
  id: string;
  openedAt: number;
  resolvedAt: number | null;
  noChangeProof: CleanupNoChangeEvidence | null;
}
export interface CleanupItemRecord {
  id: string;
  revision: number;
  ref: CleanupSourceRef;
  state: CleanupItemState;
  blockers: CleanupBlocker[];
  physicalSourceId: string | null;
  uncertainties: CleanupUncertainty[];
  executionAvailable: false;
}
export interface CleanupPlanRecord {
  schemaVersion: 1;
  id: string;
  ownerId: string;
  revision: number;
  intent: "trash_specified_sources";
  state: CleanupPlanState;
  createdAt: number;
  updatedAt: number;
  /** Review deadline in epoch ms, not a restore window; uses a durable nondecreasing clock. */
  expiresAt: number;
  expired: boolean;
  cancelled: boolean;
  items: CleanupItemRecord[];
  keepers: CleanupSourceRef[];
  blockers: CleanupBlocker[];
  reviewFingerprint: string | null;
  evidence: CleanupEvidence | null;
  executionAvailable: false;
}
/** replayed means the original historical result, never current eligibility. */
export interface CleanupCommandResult { plan: CleanupPlanRecord; replayed: boolean }

export function cleanupObject(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) fault("invalid_cleanup_request", "Expected a cleanup metadata object.");
  const data = value as Record<string, unknown>;
  if (Object.keys(data).length !== keys.length || Object.keys(data).some(key => !keys.includes(key))) {
    fault("invalid_cleanup_request", "Cleanup metadata contains missing or unsupported fields.");
  }
  return data;
}
export function cleanupId(value: unknown): string {
  if (typeof value !== "string" || !/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$/.test(value)) {
    fault("invalid_cleanup_request", "Use an opaque cleanup identifier, without paths or URLs.");
  }
  return value;
}
export function cleanupInteger(value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < min || value > max) {
    fault("invalid_cleanup_request", `Expected an integer from ${min} to ${max}.`);
  }
  return value;
}
export function cleanupChoice<T extends string>(value: unknown, choices: readonly T[]): T {
  if (typeof value !== "string" || !choices.includes(value as T)) fault("invalid_cleanup_request", "An unsupported cleanup value was supplied.");
  return value as T;
}
export function cleanupRef(value: unknown): CleanupSourceRef {
  const data = cleanupObject(value, ["assetId", "versionId", "sourceId", "expectedSourceRevision"]);
  return { assetId: cleanupId(data.assetId), versionId: cleanupId(data.versionId), sourceId: cleanupId(data.sourceId), expectedSourceRevision: cleanupInteger(data.expectedSourceRevision, 1) };
}
function refs(value: unknown, min: number): CleanupSourceRef[] {
  if (!Array.isArray(value) || value.length < min || value.length > 50) fault("invalid_cleanup_request", `Expected ${min}–50 source references.`);
  return value.map(cleanupRef);
}
function write(data: Record<string, unknown>): CleanupWrite {
  if (data.schemaVersion !== 1) fault("unsupported_cleanup_schema", "This cleanup schema is unsupported.");
  return { schemaVersion: 1, idempotencyKey: cleanupId(data.idempotencyKey) };
}
export function parseCreateCleanupPlan(value: unknown): CreateCleanupPlan {
  const data = cleanupObject(value, ["schemaVersion", "idempotencyKey", "intent", "targets", "keepers", "expiresInMs"]);
  const targets = refs(data.targets, 1), keepers = refs(data.keepers, 0);
  assertCleanupSelections(targets, keepers);
  return { ...write(data), intent: cleanupChoice(data.intent, ["trash_specified_sources"]), targets, keepers, expiresInMs: cleanupInteger(data.expiresInMs, 1_000, 900_000) };
}
export function assertCleanupSelections(targets: CleanupSourceRef[], keepers: CleanupSourceRef[]): void {
  if (new Set([...targets, ...keepers].map(ref => ref.sourceId)).size !== targets.length + keepers.length) {
    fault("invalid_cleanup_request", "A source cannot occur twice or be both a target and a keeper.");
  }
  const versions = new Map<string, string>();
  for (const ref of [...targets, ...keepers]) {
    const prior = versions.get(ref.versionId);
    if (prior != null && prior !== ref.assetId) fault("invalid_cleanup_request", "A version cannot refer to multiple assets.");
    versions.set(ref.versionId, ref.assetId);
  }
}
export function parseCleanupRevision(value: unknown): CleanupRevisionCommand {
  const data = cleanupObject(value, ["schemaVersion", "idempotencyKey", "expectedRevision"]);
  return { ...write(data), expectedRevision: cleanupInteger(data.expectedRevision, 1) };
}
export function parseConfirmCleanup(value: unknown): ConfirmCleanupPlan {
  const data = cleanupObject(value, ["schemaVersion", "idempotencyKey", "expectedRevision", "expectedReviewFingerprint"]);
  if (typeof data.expectedReviewFingerprint !== "string" || !/^[0-9a-f]{64}$/.test(data.expectedReviewFingerprint)) fault("invalid_cleanup_request", "Review the exact cleanup intent before confirming it.");
  return { ...write(data), expectedRevision: cleanupInteger(data.expectedRevision, 1), expectedReviewFingerprint: data.expectedReviewFingerprint };
}
export function parseCleanupItemCommand(value: unknown, uncertain = false): CleanupItemCommand | MarkCleanupUncertain {
  const data = cleanupObject(value, ["schemaVersion", "idempotencyKey", "expectedRevision", "expectedItemRevision", ...(uncertain ? ["uncertaintyId"] : [])]);
  const result: CleanupItemCommand = { ...write(data), expectedRevision: cleanupInteger(data.expectedRevision, 1), expectedItemRevision: cleanupInteger(data.expectedItemRevision, 1) };
  return uncertain ? { ...result, uncertaintyId: cleanupId(data.uncertaintyId) } : result;
}
function nullableFlag(value: unknown): boolean | null {
  if (value !== true && value !== false && value !== null) fault("invalid_cleanup_request", "Expected known or unknown cleanup evidence.");
  return value;
}
export function parseCleanupEvidence(value: unknown): CleanupEvidence {
  const data = cleanupObject(value, ["schemaVersion", "ownerId", "evidenceRevision", "verifiedAt", "validUntil", "protectionRevision", "favoriteRevision", "dependencyRevision", "copyRevision", "sources", "copies"]);
  if (data.schemaVersion !== 1 || !Array.isArray(data.sources) || data.sources.length > 100 || !Array.isArray(data.copies) || data.copies.length > 50) fault("invalid_cleanup_request", "Cleanup evidence is unsupported or exceeds its bounds.");
  const sources = data.sources.map(value => {
    const source = cleanupObject(value, ["ref", "physicalSourceId", "pinProofId", "representation", "access", "protected", "favorite", "requiredDependency"]);
    return { ref: cleanupRef(source.ref), physicalSourceId: cleanupId(source.physicalSourceId), pinProofId: source.pinProofId === null ? null : cleanupId(source.pinProofId), representation: cleanupChoice(source.representation, ["original_bytes", "other", "unknown"]), access: cleanupChoice(source.access, ["granted", "denied", "unknown"]), protected: nullableFlag(source.protected), favorite: nullableFlag(source.favorite), requiredDependency: nullableFlag(source.requiredDependency) };
  });
  const copies = data.copies.map(value => {
    const copy = cleanupObject(value, ["targetSourceId", "keeperSourceId", "versionId", "identityProofId", "independenceProofId", "restoreProofId"]);
    return { targetSourceId: cleanupId(copy.targetSourceId), keeperSourceId: cleanupId(copy.keeperSourceId), versionId: cleanupId(copy.versionId), identityProofId: cleanupId(copy.identityProofId), independenceProofId: cleanupId(copy.independenceProofId), restoreProofId: cleanupId(copy.restoreProofId) };
  });
  return { schemaVersion: 1, ownerId: cleanupId(data.ownerId), evidenceRevision: cleanupInteger(data.evidenceRevision, 1), verifiedAt: cleanupInteger(data.verifiedAt), validUntil: cleanupInteger(data.validUntil, 1), protectionRevision: cleanupInteger(data.protectionRevision), favoriteRevision: cleanupInteger(data.favoriteRevision), dependencyRevision: cleanupInteger(data.dependencyRevision), copyRevision: cleanupInteger(data.copyRevision), sources, copies };
}
export function parseCleanupNoChange(value: unknown): CleanupNoChangeEvidence {
  const data = cleanupObject(value, ["schemaVersion", "ownerId", "ref", "uncertaintyId", "result", "proofId", "verifiedAt", "validUntil"]);
  if (data.schemaVersion !== 1) fault("invalid_cleanup_request", "Unsupported cleanup reconciliation evidence.");
  return { schemaVersion: 1, ownerId: cleanupId(data.ownerId), ref: cleanupRef(data.ref), uncertaintyId: cleanupId(data.uncertaintyId), result: cleanupChoice(data.result, ["verified_no_change"]), proofId: cleanupId(data.proofId), verifiedAt: cleanupInteger(data.verifiedAt), validUntil: cleanupInteger(data.validUntil, 1) };
}
