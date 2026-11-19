/** Versioned product contracts. IDs are opaque references, never paths or URLs. */
export const SCHEMA_VERSION = 1 as const;
export const JOB_KINDS = ["image_try_on", "document_conversion", "video_edit", "backup"] as const;
export type JobKind = typeof JOB_KINDS[number];
export type JobState = "queued" | "running" | "cancelling" | "cancelled" | "succeeded" | "failed";

export interface ProductError {
  code: string;
  message: string;
  retryable: boolean;
  scope: "request" | "job" | "consent" | "policy" | "provider" | "authentication";
  recoveryAction: string;
}

export class ControlPlaneError extends Error {
  readonly status: number;
  readonly detail: ProductError;
  constructor(status: number, detail: ProductError) {
    super(detail.message);
    this.name = "ControlPlaneError";
    this.status = status;
    this.detail = detail;
  }
}

export function fault(
  code: string,
  message: string,
  status = 400,
  scope: ProductError["scope"] = "request",
  recoveryAction = "Review the request and try again.",
  retryable = false,
): never {
  throw new ControlPlaneError(status, { code, message, scope, recoveryAction, retryable });
}

export interface WriteCommand {
  schemaVersion: 1;
  idempotencyKey: string;
}
export interface InputVersion { assetId: string; versionId: string }
export interface MoneyLimit { currency: "USD"; maxMinor: number }
export type JobParameters =
  | { borrow: "hairstyle" | "outfit"; referenceVersionId: string }
  | { format: "pdf" | "docx" | "txt" }
  | { prompt: string; aspectRatio: "16:9" | "9:16" | "1:1" }
  | { origin: "manual" | "automatic"; destinationId: string };
export interface CreateMediaJob extends WriteCommand {
  kind: JobKind;
  inputs: InputVersion[];
  consentRevision: number;
  policyRevision: number;
  budget: MoneyLimit;
  parameters: JobParameters;
}
export interface SetConsent extends WriteCommand {
  expectedRevision: number;
  granted: boolean;
  assetIds: string[];
  purposes: JobKind[];
  providerIds: string[];
}
export interface ConsentRecord {
  revision: number;
  granted: boolean;
  assetIds: string[];
  purposes: JobKind[];
  providerIds: string[];
  updatedAt: string | null;
}
export interface BackupPolicyRecord {
  revision: number;
  enabled: boolean;
  includedCollectionIds: string[];
  includeExisting: boolean;
  includeVideos: boolean;
  network: "wifi" | "any";
  destinationId: string | null;
}
export interface SetBackupPolicy extends WriteCommand, Omit<BackupPolicyRecord, "revision"> {
  expectedRevision: number;
}
export interface CancelJob extends WriteCommand { expectedRevision: number }
export interface ReconcileExpiredAttempt extends WriteCommand {
  expectedRevision: number;
  expectedAttempt: number;
  incurredMinor: number;
  receiptId: string;
}
export interface JobRecord {
  id: string;
  revision: number;
  kind: JobKind;
  state: JobState;
  inputs: InputVersion[];
  consentRevision: number;
  policyRevision: number;
  providerId: string;
  parameters: JobParameters;
  budget: MoneyLimit & { reservedMinor: number; incurredMinor: number };
  attempt: number;
  cancellationRequested: boolean;
  createdAt: string;
  updatedAt: string;
  error: ProductError | null;
}
export interface ProviderCapability {
  kind: JobKind;
  available: boolean;
  reason: "provider_unconfigured" | null;
}
export interface Capabilities {
  schemaVersion: 1;
  localGallery: { requiresAccount: false };
  cloudCatalogueEnabled: false;
  automaticBackupEnabledByDefault: false;
  providers: ProviderCapability[];
}
export interface DurableEvent {
  eventId: string;
  schemaVersion: 1;
  ownerId: string;
  aggregateId: string;
  aggregateRevision: number;
  operationId: string;
  occurredAt: string;
  payload: { type: "ConsentChanged" | "BackupPolicyChanged" | "JobStateChanged"; state?: JobState };
}

function object(value: unknown, fields: string[]): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) fault("invalid_request", "Expected a JSON object.");
  const data = value as Record<string, unknown>;
  if (Object.keys(data).some(key => !fields.includes(key))) fault("invalid_request", "The request contains unsupported fields.");
  return data;
}
function integer(value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < min || value > max) {
    fault("invalid_request", "An integer is outside the allowed range.");
  }
  return value;
}
function bool(value: unknown): boolean {
  if (typeof value !== "boolean") fault("invalid_request", "Expected a boolean value.");
  return value;
}
function choice<T extends string>(value: unknown, choices: readonly T[]): T {
  if (typeof value !== "string" || !choices.includes(value as T)) fault("invalid_request", "The selected option is unsupported.");
  return value as T;
}
function opaque(value: unknown): string {
  if (typeof value !== "string" || !/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$/.test(value)) {
    fault("invalid_request", "Use an opaque identifier with letters, numbers, dashes or underscores.");
  }
  return value;
}
function ids(value: unknown, max = 100): string[] {
  if (!Array.isArray(value) || value.length > max) fault("invalid_request", "The selection exceeds the allowed size.");
  const result = value.map(opaque);
  if (new Set(result).size !== result.length) fault("invalid_request", "The selection contains repeated identifiers.");
  return result;
}
function write(data: Record<string, unknown>): WriteCommand {
  if (data.schemaVersion !== 1) fault("unsupported_schema", "This schema version is unsupported.");
  return { schemaVersion: 1, idempotencyKey: opaque(data.idempotencyKey) };
}

export function parseCreateJob(value: unknown): CreateMediaJob {
  const data = object(value, ["schemaVersion", "idempotencyKey", "kind", "inputs", "consentRevision", "policyRevision", "budget", "parameters"]);
  const base = write(data);
  const kind = choice(data.kind, JOB_KINDS);
  if (!Array.isArray(data.inputs) || data.inputs.length < 1 || data.inputs.length > 50) {
    fault("invalid_request", "Choose between 1 and 50 input versions.");
  }
  const inputs = data.inputs.map(input => {
    const ref = object(input, ["assetId", "versionId"]);
    return { assetId: opaque(ref.assetId), versionId: opaque(ref.versionId) };
  });
  if (new Set(inputs.map(input => input.versionId)).size !== inputs.length) fault("invalid_request", "Input versions must be unique.");
  const money = object(data.budget, ["currency", "maxMinor"]);
  const budget: MoneyLimit = { currency: choice(money.currency, ["USD"]), maxMinor: integer(money.maxMinor, 1, 100_000) };
  let parameters: JobParameters;
  if (kind === "image_try_on") {
    const p = object(data.parameters, ["borrow", "referenceVersionId"]);
    parameters = { borrow: choice(p.borrow, ["hairstyle", "outfit"]), referenceVersionId: opaque(p.referenceVersionId) };
    if (inputs.length < 2 || !inputs.some(input => input.versionId === (parameters as { referenceVersionId: string }).referenceVersionId)) {
      fault("invalid_request", "Include the base and reference versions in the input selection.");
    }
  } else if (kind === "document_conversion") {
    const p = object(data.parameters, ["format"]);
    parameters = { format: choice(p.format, ["pdf", "docx", "txt"]) };
  } else if (kind === "video_edit") {
    const p = object(data.parameters, ["prompt", "aspectRatio"]);
    if (typeof p.prompt !== "string" || p.prompt.trim().length < 1 || p.prompt.length > 2000) fault("invalid_request", "Describe the edit in 1 to 2,000 characters.");
    parameters = { prompt: p.prompt.trim(), aspectRatio: choice(p.aspectRatio, ["16:9", "9:16", "1:1"]) };
  } else {
    const p = object(data.parameters, ["origin", "destinationId"]);
    parameters = { origin: choice(p.origin, ["manual", "automatic"]), destinationId: opaque(p.destinationId) };
  }
  return { ...base, kind, inputs, consentRevision: integer(data.consentRevision, 1), policyRevision: integer(data.policyRevision), budget, parameters };
}

export function parseSetConsent(value: unknown): SetConsent {
  const data = object(value, ["schemaVersion", "idempotencyKey", "expectedRevision", "granted", "assetIds", "purposes", "providerIds"]);
  const assetIds = ids(data.assetIds);
  if (!Array.isArray(data.purposes) || data.purposes.length > JOB_KINDS.length) fault("invalid_request", "Choose supported processing purposes.");
  const purposes = data.purposes.map(p => choice(p, JOB_KINDS));
  if (new Set(purposes).size !== purposes.length) fault("invalid_request", "Purposes must be unique.");
  const providerIds = ids(data.providerIds, 10);
  const granted = bool(data.granted);
  if (granted && (!assetIds.length || !purposes.length || !providerIds.length)) fault("invalid_request", "Consent needs a selection, purpose and provider.");
  return { ...write(data), expectedRevision: integer(data.expectedRevision), granted, assetIds, purposes, providerIds };
}

export function parseBackupPolicy(value: unknown): SetBackupPolicy {
  const data = object(value, ["schemaVersion", "idempotencyKey", "expectedRevision", "enabled", "includedCollectionIds", "includeExisting", "includeVideos", "network", "destinationId"]);
  const includedCollectionIds = ids(data.includedCollectionIds);
  const enabled = bool(data.enabled);
  const destinationId = data.destinationId === null ? null : opaque(data.destinationId);
  if (enabled && (!includedCollectionIds.length || !destinationId)) fault("invalid_request", "Choose backup collections and a destination before enabling automatic backup.");
  return { ...write(data), expectedRevision: integer(data.expectedRevision), enabled, includedCollectionIds, includeExisting: bool(data.includeExisting), includeVideos: bool(data.includeVideos), network: choice(data.network, ["wifi", "any"]), destinationId };
}

export function parseCancellation(value: unknown): CancelJob {
  const data = object(value, ["schemaVersion", "idempotencyKey", "expectedRevision"]);
  return { ...write(data), expectedRevision: integer(data.expectedRevision, 1) };
}

/** Trusted operational contract; deliberately has no public HTTP route. */
export function parseExpiredReconciliation(value: unknown): ReconcileExpiredAttempt {
  const data = object(value, ["schemaVersion", "idempotencyKey", "expectedRevision", "expectedAttempt", "incurredMinor", "receiptId"]);
  return { ...write(data), expectedRevision: integer(data.expectedRevision, 1), expectedAttempt: integer(data.expectedAttempt, 1), incurredMinor: integer(data.incurredMinor), receiptId: opaque(data.receiptId) };
}
