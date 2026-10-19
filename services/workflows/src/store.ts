import { createHash, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import {
  JOB_KINDS, fault, parseBackupPolicy, parseCancellation, parseCreateJob, parseExpiredReconciliation, parseSetConsent,
} from "../../../packages/contracts/src/index.ts";
import type {
  BackupPolicyRecord, Capabilities, ConsentRecord, CreateMediaJob, DurableEvent,
  InputVersion, JobKind, JobRecord, JobState, ProductError,
} from "../../../packages/contracts/src/index.ts";

/** Internal integration seam. Production bootstrap installs no providers. */
export interface AdmissionProvider {
  kind: JobKind;
  providerId: string;
  estimateMinor: number;
}
export interface StoreOptions {
  providers?: AdmissionProvider[];
  /** Must verify owner, immutable version, tombstones and current source access. */
  verifyInputs?: (ownerId: string, inputs: InputVersion[]) => boolean;
  /** Platform adapter must prove collection membership, media choice and current network. */
  automaticBackupEligibility?: (ownerId: string, inputs: InputVersion[], policy: BackupPolicyRecord) => {
    includedCollections: boolean; mediaTypes: boolean; network: boolean;
  };
  now?: () => number;
}
interface JobRow { record_json: string; lease_token: string | null; lease_until: number | null }
export interface Lease { ownerId: string; job: JobRecord; token: string; expiresAt: number }

function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") {
    const data = value as Record<string, unknown>;
    return `{${Object.keys(data).sort().map(key => `${JSON.stringify(key)}:${canonical(data[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}
function isActive(state: JobState): boolean {
  return state === "queued" || state === "running" || state === "cancelling";
}
function conflict(): never {
  fault("revision_conflict", "This record changed. Refresh before continuing.", 409, "request", "Refresh the record and review the change.");
}

export class ControlPlane {
  private readonly db: DatabaseSync;
  private readonly providers: AdmissionProvider[];
  private readonly verifyInputs: NonNullable<StoreOptions["verifyInputs"]>;
  private readonly automaticBackupEligibility: NonNullable<StoreOptions["automaticBackupEligibility"]>;
  private readonly now: () => number;

  constructor(databasePath: string, options: StoreOptions = {}) {
    this.db = new DatabaseSync(databasePath);
    this.db.exec("PRAGMA busy_timeout = 5000; PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL; PRAGMA synchronous = FULL;");
    const version = this.db.prepare("PRAGMA user_version").get() as { user_version: number };
    if (version.user_version > 1) {
      this.db.close();
      throw new Error("Database schema is newer than this application supports.");
    }
    if (version.user_version === 0) {
      const migration = readFileSync(new URL("../../../db/migrations/001_control_plane.sql", import.meta.url), "utf8");
      this.db.exec("BEGIN IMMEDIATE");
      try { this.db.exec(migration); this.db.exec("COMMIT"); }
      catch (error) { this.db.exec("ROLLBACK"); this.db.close(); throw error; }
    }
    this.providers = (options.providers ?? []).map(provider => {
      if (!JOB_KINDS.includes(provider.kind) || !/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$/.test(provider.providerId)
        || !Number.isSafeInteger(provider.estimateMinor) || provider.estimateMinor < 1 || provider.estimateMinor > 100_000) {
        throw new Error("Provider admission configuration is invalid.");
      }
      return { ...provider };
    });
    if (new Set(this.providers.map(p => p.kind)).size !== this.providers.length) throw new Error("A job kind has multiple admission providers.");
    this.verifyInputs = options.verifyInputs ?? (() => false);
    this.automaticBackupEligibility = options.automaticBackupEligibility ?? (() => ({ includedCollections: false, mediaTypes: false, network: false }));
    this.now = options.now ?? Date.now;
  }

  close(): void { this.db.close(); }
  capabilities(): Capabilities {
    return {
      schemaVersion: 1, localGallery: { requiresAccount: false }, cloudCatalogueEnabled: false,
      automaticBackupEnabledByDefault: false,
      providers: JOB_KINDS.map(kind => ({ kind, available: this.providers.some(p => p.kind === kind), reason: this.providers.some(p => p.kind === kind) ? null : "provider_unconfigured" })),
    };
  }
  getConsent(ownerId: string): ConsentRecord {
    const row = this.db.prepare("SELECT record_json FROM consent WHERE owner_id = ?").get(ownerId) as { record_json: string } | undefined;
    return row ? JSON.parse(row.record_json) as ConsentRecord : { revision: 0, granted: false, assetIds: [], purposes: [], providerIds: [], updatedAt: null };
  }
  getBackupPolicy(ownerId: string): BackupPolicyRecord {
    const row = this.db.prepare("SELECT record_json FROM backup_policy WHERE owner_id = ?").get(ownerId) as { record_json: string } | undefined;
    return row ? JSON.parse(row.record_json) as BackupPolicyRecord : { revision: 0, enabled: false, includedCollectionIds: [], includeExisting: false, includeVideos: false, network: "wifi", destinationId: null };
  }
  getJob(ownerId: string, jobId: string): JobRecord {
    const row = this.db.prepare("SELECT record_json FROM jobs WHERE owner_id = ? AND job_id = ?").get(ownerId, jobId) as { record_json: string } | undefined;
    if (!row) fault("job_not_found", "This task is unavailable.", 404, "job", "Refresh the task list.");
    return JSON.parse(row.record_json) as JobRecord;
  }
  listJobs(ownerId: string): JobRecord[] {
    const rows = this.db.prepare("SELECT record_json FROM jobs WHERE owner_id = ? ORDER BY rowid DESC LIMIT 100").all(ownerId) as { record_json: string }[];
    return rows.map(row => JSON.parse(row.record_json) as JobRecord);
  }

  setConsent(ownerId: string, value: unknown): ConsentRecord {
    const command = parseSetConsent(value);
    return this.operation(ownerId, command.idempotencyKey, { action: "SetConsent", command }, () => {
      const prior = this.getConsent(ownerId);
      if (prior.revision !== command.expectedRevision) conflict();
      const next: ConsentRecord = {
        revision: prior.revision + 1, granted: command.granted, assetIds: command.assetIds,
        purposes: command.purposes, providerIds: command.providerIds, updatedAt: this.timestamp(),
      };
      this.db.prepare("INSERT INTO consent(owner_id, revision, record_json) VALUES (?, ?, ?) ON CONFLICT(owner_id) DO UPDATE SET revision = excluded.revision, record_json = excluded.record_json")
        .run(ownerId, next.revision, JSON.stringify(next));
      this.event(ownerId, "consent", next.revision, command.idempotencyKey, { type: "ConsentChanged" });
      // Every snapshot is stale after a consent edit; do not assume broader grants
      // preserve permission for an already queued transmission.
      for (const job of this.activeJobs(ownerId)) {
        this.stopJob(ownerId, job, command.idempotencyKey, this.policyError("consent_changed", "Processing permission changed.", "consent"));
      }
      return next;
    });
  }

  setBackupPolicy(ownerId: string, value: unknown): BackupPolicyRecord {
    const command = parseBackupPolicy(value);
    return this.operation(ownerId, command.idempotencyKey, { action: "SetBackupPolicy", command }, () => {
      const prior = this.getBackupPolicy(ownerId);
      if (prior.revision !== command.expectedRevision) conflict();
      if (command.enabled && !this.providers.some(p => p.kind === "backup")) this.unconfigured();
      const next: BackupPolicyRecord = {
        revision: prior.revision + 1, enabled: command.enabled, includedCollectionIds: command.includedCollectionIds,
        includeExisting: command.includeExisting, includeVideos: command.includeVideos, network: command.network, destinationId: command.destinationId,
      };
      this.db.prepare("INSERT INTO backup_policy(owner_id, revision, record_json) VALUES (?, ?, ?) ON CONFLICT(owner_id) DO UPDATE SET revision = excluded.revision, record_json = excluded.record_json")
        .run(ownerId, next.revision, JSON.stringify(next));
      this.event(ownerId, "backup-policy", next.revision, command.idempotencyKey, { type: "BackupPolicyChanged" });
      for (const job of this.activeJobs(ownerId)) {
        if (job.kind === "backup" && "origin" in job.parameters && job.parameters.origin === "automatic") {
          this.stopJob(ownerId, job, command.idempotencyKey, this.policyError("backup_policy_changed", "Automatic backup settings changed.", "policy"));
        }
      }
      return next;
    });
  }

  createJob(ownerId: string, value: unknown): JobRecord {
    const command = parseCreateJob(value);
    return this.operation(ownerId, command.idempotencyKey, { action: "CreateMediaJob", command }, () => {
      const provider = this.providers.find(p => p.kind === command.kind);
      if (!provider) this.unconfigured();
      this.assertConsent(ownerId, command, provider.providerId);
      this.assertBackupPolicy(ownerId, command);
      if (this.verifyInputs(ownerId, command.inputs) !== true) fault("input_unavailable", "An input version is unavailable or has changed.", 409, "job", "Select accessible input versions again.");
      if (provider.estimateMinor > command.budget.maxMinor) fault("budget_exceeded", "The processing estimate exceeds this task's spending limit.", 422, "job", "Review the estimate and choose a suitable spending limit.");
      const at = this.timestamp();
      const job: JobRecord = {
        id: randomUUID(), revision: 1, kind: command.kind, state: "queued", inputs: command.inputs,
        consentRevision: command.consentRevision, policyRevision: command.policyRevision,
        providerId: provider.providerId, parameters: command.parameters,
        budget: { ...command.budget, reservedMinor: provider.estimateMinor, incurredMinor: 0 },
        attempt: 0, cancellationRequested: false, createdAt: at, updatedAt: at, error: null,
      };
      this.db.prepare("INSERT INTO jobs(job_id, owner_id, revision, state, record_json) VALUES (?, ?, ?, ?, ?)")
        .run(job.id, ownerId, job.revision, job.state, JSON.stringify(job));
      this.event(ownerId, job.id, job.revision, command.idempotencyKey, { type: "JobStateChanged", state: job.state });
      return job;
    });
  }

  cancelJob(ownerId: string, jobId: string, value: unknown): JobRecord {
    const command = parseCancellation(value);
    return this.operation(ownerId, command.idempotencyKey, { action: "CancelJob", jobId, command }, () => {
      const job = this.getJob(ownerId, jobId);
      if (job.revision !== command.expectedRevision) conflict();
      if (!isActive(job.state) || job.state === "cancelling") return job;
      return this.stopJob(ownerId, job, command.idempotencyKey, null);
    });
  }

  /** Internal worker boundary, deliberately absent from HTTP routes. */
  claimNext(workerId: string, leaseMs = 30_000): Lease | null {
    if (!/^[a-zA-Z0-9_-]{1,128}$/.test(workerId) || !Number.isSafeInteger(leaseMs) || leaseMs < 1 || leaseMs > 300_000) throw new Error("Invalid lease parameters.");
    return this.transaction(() => {
      const expired = this.db.prepare("SELECT owner_id, record_json FROM jobs WHERE state IN ('running', 'cancelling') AND lease_until <= ?").all(this.now()) as { owner_id: string; record_json: string }[];
      for (const row of expired) {
        const job = JSON.parse(row.record_json) as JobRecord;
        // A provider might have received the old request. Blindly retrying would
        // duplicate side effects or charges; reserve stays until reconciliation.
        this.saveJob(row.owner_id, { ...job, state: "failed", error: this.policyError("reconciliation_required", "A worker stopped during processing. The provider outcome needs checking.", "job") }, "lease-expired");
        this.db.prepare("UPDATE jobs SET lease_token = NULL, lease_until = NULL WHERE job_id = ?").run(job.id);
      }
      const rows = this.db.prepare("SELECT owner_id, record_json FROM jobs WHERE state = 'queued' ORDER BY rowid").all() as { owner_id: string; record_json: string }[];
      for (const row of rows) {
        const job = JSON.parse(row.record_json) as JobRecord;
        if (!this.stillEligible(row.owner_id, job)) {
          this.stopJob(row.owner_id, job, "dispatch-recheck", this.policyError("input_or_consent_changed", "An input or processing permission changed.", "job"));
          continue;
        }
        const next = this.saveJob(row.owner_id, { ...job, state: "running", attempt: job.attempt + 1 }, `dispatch-${workerId}`);
        const token = randomUUID();
        const expiresAt = this.now() + leaseMs;
        this.db.prepare("UPDATE jobs SET lease_token = ?, lease_until = ? WHERE job_id = ?").run(token, expiresAt, job.id);
        return { ownerId: row.owner_id, job: next, token, expiresAt };
      }
      return null;
    });
  }

  /** Call before every external stage/retry. No expired, cancelled or revoked lease can transmit. */
  assertCanTransmit(ownerId: string, jobId: string, token: string): JobRecord {
    const job = this.requireLease(ownerId, jobId, token);
    if (job.state !== "running" || !this.stillEligible(ownerId, job)) {
      fault("transmission_forbidden", "This task is no longer authorised to send data.", 409, "job", "Review inputs and permissions before starting a new task.");
    }
    return job;
  }

  /** Reconcile failure/cancellation with actual usage. Success needs a future validated-output contract. */
  finishStopped(ownerId: string, jobId: string, token: string, incurredMinor: number): JobRecord {
    return this.transaction(() => {
      const job = this.requireLease(ownerId, jobId, token);
      if (!Number.isSafeInteger(incurredMinor) || incurredMinor < job.budget.incurredMinor) {
        fault("invalid_usage", "Usage reconciliation must be a non-negative integer that preserves incurred cost.", 422, "job", "Check the provider receipt before reconciling usage.");
      }
      const cancelled = job.state === "cancelling" || !this.stillEligible(ownerId, job);
      const next = this.saveJob(ownerId, {
        ...job, state: cancelled ? "cancelled" : "failed",
        budget: { ...job.budget, reservedMinor: 0, incurredMinor },
        error: incurredMinor > job.budget.maxMinor ? this.policyError("budget_overrun", "The provider receipt exceeds the approved limit. Further processing is blocked.", "job")
          : cancelled ? job.error : this.policyError("processing_failed", "Processing did not produce a validated output.", "job"),
      }, "attempt-reconciled");
      this.db.prepare("UPDATE jobs SET lease_token = NULL, lease_until = NULL WHERE job_id = ?").run(jobId);
      return next;
    });
  }

  /** Operator/adapter capability only: reconcile an uncertain expired attempt using a checked receipt. */
  reconcileExpiredAttempt(ownerId: string, jobId: string, value: unknown): JobRecord {
    const command = parseExpiredReconciliation(value);
    return this.operation(ownerId, command.idempotencyKey, { action: "ReconcileExpiredAttempt", jobId, command }, () => {
      const job = this.getJob(ownerId, jobId);
      if (job.revision !== command.expectedRevision || job.attempt !== command.expectedAttempt) conflict();
      if (job.state !== "failed" || job.error?.code !== "reconciliation_required") {
        fault("attempt_not_reconcilable", "This attempt is not awaiting reconciliation.", 409, "job", "Review the latest task and provider receipt.");
      }
      if (command.incurredMinor < job.budget.incurredMinor) fault("invalid_usage", "Previously incurred cost cannot be removed.", 422, "job");
      this.db.prepare("INSERT INTO attempt_receipts(job_id, attempt, owner_id, receipt_id, incurred_minor) VALUES (?, ?, ?, ?, ?)")
        .run(jobId, job.attempt, ownerId, command.receiptId, command.incurredMinor);
      return this.saveJob(ownerId, {
        ...job, state: job.cancellationRequested ? "cancelled" : "failed",
        budget: { ...job.budget, reservedMinor: 0, incurredMinor: command.incurredMinor },
        error: command.incurredMinor > job.budget.maxMinor ? this.policyError("budget_overrun", "The provider receipt exceeds the approved limit. Further processing is blocked.", "job")
          : job.cancellationRequested ? null : this.policyError("processing_failed", "The expired attempt was reconciled without a validated output.", "job"),
      }, command.idempotencyKey);
    });
  }

  readEvents(ownerId: string, after = 0): { offset: number; event: DurableEvent }[] {
    if (!Number.isSafeInteger(after) || after < 0) throw new Error("Invalid event cursor.");
    const rows = this.db.prepare("SELECT offset, event_json FROM outbox WHERE owner_id = ? AND offset > ? ORDER BY offset LIMIT 100").all(ownerId, after) as { offset: number; event_json: string }[];
    return rows.map(row => ({ offset: row.offset, event: JSON.parse(row.event_json) as DurableEvent }));
  }

  private operation<T>(ownerId: string, key: string, request: unknown, effect: () => T): T {
    const fingerprint = createHash("sha256").update(canonical(request)).digest("hex");
    return this.transaction(() => {
      const existing = this.db.prepare("SELECT fingerprint, result_json FROM operations WHERE owner_id = ? AND idempotency_key = ?").get(ownerId, key) as { fingerprint: string; result_json: string } | undefined;
      if (existing) {
        if (existing.fingerprint !== fingerprint) fault("idempotency_conflict", "This operation ID was already used for a different request.", 409, "request", "Use a new operation ID for a new request.");
        return JSON.parse(existing.result_json) as T;
      }
      const result = effect();
      this.db.prepare("INSERT INTO operations(owner_id, idempotency_key, fingerprint, result_json) VALUES (?, ?, ?, ?)")
        .run(ownerId, key, fingerprint, JSON.stringify(result));
      return result;
    });
  }
  private transaction<T>(effect: () => T): T {
    this.db.exec("BEGIN IMMEDIATE");
    try { const result = effect(); this.db.exec("COMMIT"); return result; }
    catch (error) { this.db.exec("ROLLBACK"); throw error; }
  }
  private timestamp(): string { return new Date(this.now()).toISOString(); }
  private unconfigured(): never {
    fault("provider_unconfigured", "This cloud service is not connected yet. No media was uploaded or processing started.", 503, "provider", "Continue using the local gallery. Provider integrations are planned.");
  }
  private assertConsent(ownerId: string, command: Pick<CreateMediaJob, "kind" | "inputs" | "consentRevision">, providerId: string): void {
    const consent = this.getConsent(ownerId);
    if (!consent.granted || consent.revision !== command.consentRevision || !consent.purposes.includes(command.kind)
      || !consent.providerIds.includes(providerId) || command.inputs.some(input => !consent.assetIds.includes(input.assetId))) {
      fault("consent_required", "Current permission does not cover these inputs, purpose and provider.", 409, "consent", "Review the selected media and processing permission.");
    }
  }
  private assertBackupPolicy(ownerId: string, command: Pick<CreateMediaJob, "kind" | "parameters" | "policyRevision" | "inputs">): void {
    if (command.kind !== "backup" || !("origin" in command.parameters) || command.parameters.origin !== "automatic") return;
    const policy = this.getBackupPolicy(ownerId);
    if (!policy.enabled || policy.revision !== command.policyRevision || command.parameters.destinationId !== policy.destinationId) {
      fault("backup_policy_changed", "Automatic backup settings no longer match this task.", 409, "policy", "Review automatic backup settings.");
    }
    const eligibility = this.automaticBackupEligibility(ownerId, command.inputs, policy);
    if (eligibility.includedCollections !== true || eligibility.mediaTypes !== true || eligibility.network !== true) {
      fault("backup_scope_unverified", "Automatic backup eligibility could not be verified for this selection and connection.", 409, "policy", "Verify included collections, video settings and the current network before retrying.");
    }
  }
  private stillEligible(ownerId: string, job: JobRecord): boolean {
    try {
      this.assertConsent(ownerId, job, job.providerId);
      this.assertBackupPolicy(ownerId, job);
      return this.providers.some(p => p.kind === job.kind && p.providerId === job.providerId) && this.verifyInputs(ownerId, job.inputs) === true;
    } catch { return false; }
  }
  private activeJobs(ownerId: string): JobRecord[] {
    const rows = this.db.prepare("SELECT record_json FROM jobs WHERE owner_id = ? AND state IN ('queued', 'running', 'cancelling')").all(ownerId) as { record_json: string }[];
    return rows.map(row => JSON.parse(row.record_json) as JobRecord);
  }
  private stopJob(ownerId: string, job: JobRecord, operationId: string, error: ProductError | null): JobRecord {
    const state = job.state === "queued" ? "cancelled" : "cancelling";
    return this.saveJob(ownerId, { ...job, state, cancellationRequested: true, error, budget: { ...job.budget, reservedMinor: state === "cancelled" ? 0 : job.budget.reservedMinor } }, operationId);
  }
  private saveJob(ownerId: string, job: JobRecord, operationId: string): JobRecord {
    const next = { ...job, revision: job.revision + 1, updatedAt: this.timestamp() };
    const update = this.db.prepare("UPDATE jobs SET revision = ?, state = ?, record_json = ? WHERE owner_id = ? AND job_id = ? AND revision = ?")
      .run(next.revision, next.state, JSON.stringify(next), ownerId, job.id, job.revision);
    if (update.changes !== 1) conflict();
    this.event(ownerId, job.id, next.revision, operationId, { type: "JobStateChanged", state: next.state });
    return next;
  }
  private event(ownerId: string, aggregateId: string, revision: number, operationId: string, payload: DurableEvent["payload"]): void {
    const event: DurableEvent = { eventId: randomUUID(), schemaVersion: 1, ownerId, aggregateId, aggregateRevision: revision, operationId, occurredAt: this.timestamp(), payload };
    this.db.prepare("INSERT INTO outbox(event_id, owner_id, event_json) VALUES (?, ?, ?)").run(event.eventId, ownerId, JSON.stringify(event));
  }
  private requireLease(ownerId: string, jobId: string, token: string): JobRecord {
    const row = this.db.prepare("SELECT record_json, lease_token, lease_until FROM jobs WHERE owner_id = ? AND job_id = ?").get(ownerId, jobId) as JobRow | undefined;
    if (!row || row.lease_token !== token || !row.lease_until || row.lease_until <= this.now()) {
      fault("lease_invalid", "The worker lease has expired or is unavailable.", 409, "job", "Reconcile the previous attempt before retrying.");
    }
    const job = JSON.parse(row.record_json) as JobRecord;
    if (job.state !== "running" && job.state !== "cancelling") fault("lease_invalid", "The task is no longer running.", 409, "job");
    return job;
  }
  private policyError(code: string, message: string, scope: ProductError["scope"]): ProductError {
    return { code, message, retryable: false, scope, recoveryAction: "Review task inputs, settings and provider outcome before starting again." };
  }
}
