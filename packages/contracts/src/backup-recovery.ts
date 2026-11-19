/** CP13 development proof only. No value in this module authorizes production backup. */
export const BACKUP_PROOF_SCOPE = "synthetic_backup_recovery_v1" as const;
export const BACKUP_STATES = ["requested", "preparing", "uploading", "uploaded_unverified", "verified", "restoring", "restore_verified", "uncertain", "failed"] as const;
export type BackupState = typeof BACKUP_STATES[number];
export type ComponentRole = "primary" | "motion" | "sidecar";
export interface BackupSourceRef {
  sourceId: string;
  versionId: string;
  sourceRevision: string;
  componentSetRevision: string;
}
export interface BackupComponent {
  componentId: string;
  componentRevision: string;
  role: ComponentRole;
  byteLength: number;
  sha256: string;
}
export interface SyntheticInputEvidence {
  schemaVersion: 1;
  testOnly: true;
  ownerId: string;
  source: BackupSourceRef;
  representation: "generated_synthetic_bytes";
  fidelity: "synthetic_fixture_only";
  components: BackupComponent[];
}
/** Portable metadata, never an original-fidelity claim or a credential container. */
export interface BackupManifest extends Omit<SyntheticInputEvidence, "ownerId"> {
  manifestId: string;
  scope: typeof BACKUP_PROOF_SCOPE;
}
export interface BackupObject {
  componentId: string;
  objectId: string;
  objectRevision: number;
  byteLength: number;
  sha256: string;
}
export interface ComponentProgress {
  componentId: string;
  objectId: string;
  objectRevision: number | null;
  uploadedBytes: number;
  prefixSha256: string;
}
export type BackupAction = "request" | "prepare" | "transfer" | "verify" | "restore" | "reconcile";
export interface PendingBackupAction {
  action: "transfer" | "verify" | "restore";
  operationKey: string;
  componentId: string | null;
  before: ComponentProgress | null;
  after: ComponentProgress | null;
  restoreId: string | null;
}
export interface RecoveryRecord {
  schemaVersion: 1;
  scope: typeof BACKUP_PROOF_SCOPE;
  testOnly: true;
  productionCapability: false;
  ownerId: string;
  id: string;
  revision: number;
  source: BackupSourceRef;
  destinationId: string;
  /** Durable observation, not live eligibility. Transfer/verify/restore/export recheck evidence. */
  state: BackupState;
  manifest: BackupManifest | null;
  progress: ComponentProgress[];
  pending: PendingBackupAction | null;
  restoreId: string | null;
  failure: string | null;
}
export interface BackupCommandResult {
  record: RecoveryRecord;
  /** A replay is the original historical command result, never fresh eligibility. */
  replayed: boolean;
  testOnly: true;
  productionCapability: false;
}
export interface PortableBackupArchive {
  schemaVersion: 1;
  scope: typeof BACKUP_PROOF_SCOPE;
  testOnly: true;
  productionCapability: false;
  destinationId: string;
  manifest: BackupManifest;
  objects: BackupObject[];
}
export interface RequestBackup { idempotencyKey: string; source: BackupSourceRef; destinationId: string }
export interface BackupRevision { idempotencyKey: string; expectedRevision: number }
export interface TransferBackup extends BackupRevision { componentId: string; maxBytes: number }
export interface RestoreBackup extends BackupRevision { restoreId: string }

export class BackupRecoveryError extends Error {
  readonly code: string;
  constructor(code: string) { super(code); this.name = "BackupRecoveryError"; this.code = code; }
}
export function backupAssert(condition: unknown, code = "backup_invalid_contract"): asserts condition {
  if (!condition) throw new BackupRecoveryError(code);
}
export function backupObject(value: unknown, keys: readonly string[]): Record<string, unknown> {
  backupAssert(value !== null && typeof value === "object" && !Array.isArray(value));
  const object = value as Record<string, unknown>;
  backupAssert(Object.getPrototypeOf(object) === Object.prototype || Object.getPrototypeOf(object) === null);
  backupAssert(Object.keys(object).length === keys.length && keys.every(key => Object.hasOwn(object, key)));
  return object;
}
export function backupId(value: unknown): string {
  backupAssert(typeof value === "string" && /^[A-Za-z0-9_-]{1,96}$/.test(value));
  return value;
}
export function backupInteger(value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): number {
  backupAssert(typeof value === "number" && Number.isSafeInteger(value) && value >= min && value <= max);
  return value;
}
export function backupDigest(value: unknown): string {
  backupAssert(typeof value === "string" && /^[a-f0-9]{64}$/.test(value));
  return value;
}
export function backupSource(value: unknown): BackupSourceRef {
  const data = backupObject(value, ["sourceId", "versionId", "sourceRevision", "componentSetRevision"]);
  return { sourceId: backupId(data.sourceId), versionId: backupId(data.versionId), sourceRevision: backupId(data.sourceRevision), componentSetRevision: backupId(data.componentSetRevision) };
}
export function parseSyntheticEvidence(value: unknown): SyntheticInputEvidence {
  const data = backupObject(value, ["schemaVersion", "testOnly", "ownerId", "source", "representation", "fidelity", "components"]);
  backupAssert(data.schemaVersion === 1 && data.testOnly === true && data.representation === "generated_synthetic_bytes" && data.fidelity === "synthetic_fixture_only");
  backupAssert(Array.isArray(data.components) && data.components.length >= 1 && data.components.length <= 8);
  const components = Array.from(data.components).map(value => {
    const item = backupObject(value, ["componentId", "componentRevision", "role", "byteLength", "sha256"]);
    backupAssert(item.role === "primary" || item.role === "motion" || item.role === "sidecar");
    return { componentId: backupId(item.componentId), componentRevision: backupId(item.componentRevision), role: item.role, byteLength: backupInteger(item.byteLength, 1, 1_048_576), sha256: backupDigest(item.sha256) } as BackupComponent;
  });
  backupAssert(new Set(components.map(item => item.componentId)).size === components.length && components.filter(item => item.role === "primary").length === 1);
  return { schemaVersion: 1, testOnly: true, ownerId: backupId(data.ownerId), source: backupSource(data.source), representation: "generated_synthetic_bytes", fidelity: "synthetic_fixture_only", components };
}
export function parseRequestBackup(value: unknown): RequestBackup {
  const data = backupObject(value, ["idempotencyKey", "source", "destinationId"]);
  return { idempotencyKey: backupId(data.idempotencyKey), source: backupSource(data.source), destinationId: backupId(data.destinationId) };
}
export function parseBackupRevision(value: unknown): BackupRevision {
  const data = backupObject(value, ["idempotencyKey", "expectedRevision"]);
  return { idempotencyKey: backupId(data.idempotencyKey), expectedRevision: backupInteger(data.expectedRevision, 1) };
}
export function parseTransferBackup(value: unknown): TransferBackup {
  const data = backupObject(value, ["idempotencyKey", "expectedRevision", "componentId", "maxBytes"]);
  return { idempotencyKey: backupId(data.idempotencyKey), expectedRevision: backupInteger(data.expectedRevision, 1), componentId: backupId(data.componentId), maxBytes: backupInteger(data.maxBytes, 1, 65_536) };
}
export function parseRestoreBackup(value: unknown): RestoreBackup {
  const data = backupObject(value, ["idempotencyKey", "expectedRevision", "restoreId"]);
  return { idempotencyKey: backupId(data.idempotencyKey), expectedRevision: backupInteger(data.expectedRevision, 1), restoreId: backupId(data.restoreId) };
}
export function parseBackupManifest(value: unknown): BackupManifest {
  const data = backupObject(value, ["schemaVersion", "scope", "testOnly", "manifestId", "source", "representation", "fidelity", "components"]);
  backupAssert(data.scope === BACKUP_PROOF_SCOPE);
  const evidence = parseSyntheticEvidence({ schemaVersion: data.schemaVersion, testOnly: data.testOnly, ownerId: "synthetic_manifest_validation", source: data.source, representation: data.representation, fidelity: data.fidelity, components: data.components });
  const { ownerId: omitted, ...portable } = evidence;
  return { ...portable, manifestId: backupId(data.manifestId), scope: BACKUP_PROOF_SCOPE };
}
export function parsePortableBackupArchive(value: unknown): PortableBackupArchive {
  const data = backupObject(value, ["schemaVersion", "scope", "testOnly", "productionCapability", "destinationId", "manifest", "objects"]);
  backupAssert(data.schemaVersion === 1 && data.scope === BACKUP_PROOF_SCOPE && data.testOnly === true && data.productionCapability === false);
  const manifest = parseBackupManifest(data.manifest);
  backupAssert(Array.isArray(data.objects) && data.objects.length === manifest.components.length);
  const objects = Array.from(data.objects).map(value => {
    const item = backupObject(value, ["componentId", "objectId", "objectRevision", "byteLength", "sha256"]);
    return { componentId: backupId(item.componentId), objectId: backupId(item.objectId), objectRevision: backupInteger(item.objectRevision, 1), byteLength: backupInteger(item.byteLength, 1, 1_048_576), sha256: backupDigest(item.sha256) };
  });
  backupAssert(new Set(objects.map(item => item.objectId)).size === objects.length && new Set(objects.map(item => item.componentId)).size === objects.length);
  backupAssert(manifest.components.every(component => objects.some(item => item.componentId === component.componentId && item.byteLength === component.byteLength && item.sha256 === component.sha256)));
  return { schemaVersion: 1, scope: BACKUP_PROOF_SCOPE, testOnly: true, productionCapability: false, destinationId: backupId(data.destinationId), manifest, objects };
}
