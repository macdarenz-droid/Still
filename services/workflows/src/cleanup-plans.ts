import { createHash, randomUUID } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { fault } from "../../../packages/contracts/src/index.ts";
import {
  CLEANUP_BLOCKERS, assertCleanupSelections, cleanupChoice, cleanupId, cleanupInteger,
  cleanupObject, cleanupRef, parseCleanupEvidence, parseCleanupItemCommand,
  parseCleanupNoChange, parseCleanupRevision, parseConfirmCleanup, parseCreateCleanupPlan,
} from "../../../packages/contracts/src/cleanup.ts";
import type {
  CleanupBlocker, CleanupCommandResult, CleanupEvidence, CleanupItemRecord, CleanupNoChangeEvidence,
  CleanupPlanRecord, CleanupSourceRef, CleanupUncertainty, MarkCleanupUncertain,
} from "../../../packages/contracts/src/cleanup.ts";

/** Trusted internal adapters only. Normal application/API startup installs none. */
export interface TrustedCleanupEvidence {
  inspect(ownerId: string, selection: { targets: CleanupSourceRef[]; keepers: CleanupSourceRef[] }): unknown;
  verifyNoChange(ownerId: string, ref: CleanupSourceRef, uncertaintyId: string): unknown;
}
export interface CleanupPlansOptions { trustedEvidence?: TrustedCleanupEvidence; now?: () => number }

const APPLICATION_ID = 1398032945;
const PLAN_STATES = ["blocked", "reviewable", "confirmed_nonexecuting", "uncertain", "cancelled", "expired"] as const;
const ITEM_STATES = [...PLAN_STATES, "reconciled_no_change"] as const;
const MAX_UNCERTAINTIES = 20;
const MAX_PLANS = 1_000;
const MAX_OPERATIONS = 10_000;
const MAX_RECORD_BYTES = 1_048_576;
type Row = { owner_id: string; plan_id: string; revision: number; state: string; expires_at: number; record_json: string };
type Operation = { owner_id: string; idempotency_key: string; fingerprint: string; command_json: string; result_json: string };
type Descriptor = { action: string; planId: string | null; itemId: string | null; command: unknown };
type Evaluation = { evidence: CleanupEvidence | null; blockers: Map<string, CleanupBlocker[]> };

function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") return `{${Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => `${JSON.stringify(key)}:${canonical(item)}`).join(",")}}`;
  return JSON.stringify(value);
}
function hash(value: unknown): string { return createHash("sha256").update(canonical(value)).digest("hex"); }
function copy<T>(value: T): T { return structuredClone(value); }
function ensure(condition: unknown): asserts condition {
  if (!condition) throw new Error("Cleanup journal is invalid or unsupported. Retain the database for investigation.");
}
function bool(value: unknown): boolean { ensure(typeof value === "boolean"); return value; }
function blockers(value: unknown): CleanupBlocker[] {
  ensure(Array.isArray(value) && value.length <= CLEANUP_BLOCKERS.length);
  const result = value.map(item => cleanupChoice(item, CLEANUP_BLOCKERS));
  ensure(new Set(result).size === result.length && canonical(result) === canonical([...result].sort()));
  return result;
}
function unique(values: CleanupBlocker[]): CleanupBlocker[] { return [...new Set(values)].sort(); }
function next(value: number): number { if (value >= Number.MAX_SAFE_INTEGER) fault("cleanup_revision_exhausted", "Cleanup revisions are exhausted. Retain the journal.", 409, "policy"); return value + 1; }
function unresolved(item: CleanupItemRecord): CleanupUncertainty[] { return item.uncertainties.filter(entry => entry.resolvedAt === null); }
function fingerprint(plan: CleanupPlanRecord, evidence: CleanupEvidence): string {
  return hash({ ownerId: plan.ownerId, planId: plan.id, intent: plan.intent, targets: plan.items.map(item => item.ref), keepers: plan.keepers, expiresAt: plan.expiresAt, evidence });
}
function validInterval(proof: { verifiedAt: number; validUntil: number }, at: number): boolean {
  return proof.verifiedAt <= at && proof.validUntil > at && proof.validUntil - proof.verifiedAt <= 300_000;
}
function evidenceBound(plan: CleanupPlanRecord, evidence: CleanupEvidence): boolean {
  const refs = [...plan.items.map(item => item.ref), ...plan.keepers];
  const bySource = new Map(evidence.sources.map(source => [source.ref.sourceId, source]));
  return evidence.ownerId === plan.ownerId && bySource.size === refs.length && evidence.sources.length === refs.length &&
    refs.every(ref => canonical(ref) === canonical(bySource.get(ref.sourceId)?.ref)) &&
    new Set(evidence.copies.map(entry => entry.targetSourceId)).size === evidence.copies.length &&
    evidence.copies.every(entry => plan.items.some(item => item.ref.sourceId === entry.targetSourceId));
}
function evaluate(plan: CleanupPlanRecord, evidence: CleanupEvidence | null, at: number, unavailable: CleanupBlocker = "evidence_unavailable"): Evaluation {
  const result = new Map(plan.items.map(item => [item.id, [] as CleanupBlocker[]]));
  const all = (reason: CleanupBlocker) => { for (const values of result.values()) values.push(reason); };
  if (evidence === null) { all(unavailable); return { evidence: null, blockers: result }; }
  if (!evidenceBound(plan, evidence)) { all("evidence_invalid"); return { evidence: null, blockers: result }; }
  if (!validInterval(evidence, at)) { all("evidence_expired"); return { evidence: null, blockers: result }; }
  const sources = new Map(evidence.sources.map(source => [source.ref.sourceId, source]));
  if (new Set(evidence.sources.map(source => source.physicalSourceId)).size !== evidence.sources.length) all("source_alias");
  for (const keeper of plan.keepers) {
    const source = sources.get(keeper.sourceId)!;
    if (source.pinProofId === null || source.representation !== "original_bytes" || source.access !== "granted") all("keeper_unavailable");
    if (source.protected === null) all("protection_unknown");
    if (source.favorite === null) all("favorite_unknown");
    if (source.requiredDependency === null) all("dependency_unknown");
  }
  const targetedPhysical = new Set(plan.items.map(item => sources.get(item.ref.sourceId)!.physicalSourceId));
  for (const item of plan.items) {
    const values = result.get(item.id)!;
    const source = sources.get(item.ref.sourceId)!;
    if (source.pinProofId === null) values.push("source_unpinned");
    if (source.representation !== "original_bytes") values.push("representation_unverified");
    if (source.access !== "granted") values.push("access_unavailable");
    if (source.protected === null) values.push("protection_unknown"); else if (source.protected) values.push("protected");
    if (source.favorite === null) values.push("favorite_unknown"); else if (source.favorite) values.push("favorite");
    if (source.requiredDependency === null) values.push("dependency_unknown"); else if (source.requiredDependency) values.push("required_dependency");
    const proof = evidence.copies.find(entry => entry.targetSourceId === item.ref.sourceId);
    const keeper = proof && plan.keepers.find(ref => ref.sourceId === proof.keeperSourceId);
    const keeperSource = keeper && sources.get(keeper.sourceId);
    if (!proof || !keeper || !keeperSource || keeper.assetId !== item.ref.assetId || keeper.versionId !== item.ref.versionId ||
        proof.versionId !== item.ref.versionId || targetedPhysical.has(keeperSource.physicalSourceId)) values.push("copy_unverified");
  }
  for (const [id, values] of result) result.set(id, unique(values));
  return { evidence, blockers: result };
}

function decodePlan(value: unknown): CleanupPlanRecord {
  const data = cleanupObject(value, ["schemaVersion", "id", "ownerId", "revision", "intent", "state", "createdAt", "updatedAt", "expiresAt", "expired", "cancelled", "items", "keepers", "blockers", "reviewFingerprint", "evidence", "executionAvailable"]);
  ensure(data.schemaVersion === 1 && data.executionAvailable === false && Array.isArray(data.items) && data.items.length > 0 && data.items.length <= 50 && Array.isArray(data.keepers) && data.keepers.length <= 50);
  const items: CleanupItemRecord[] = data.items.map(value => {
    const item = cleanupObject(value, ["id", "revision", "ref", "state", "blockers", "physicalSourceId", "uncertainties", "executionAvailable"]);
    ensure(item.executionAvailable === false && Array.isArray(item.uncertainties) && item.uncertainties.length <= MAX_UNCERTAINTIES);
    const uncertainties = item.uncertainties.map(value => {
      const entry = cleanupObject(value, ["id", "openedAt", "resolvedAt", "noChangeProof"]);
      return { id: cleanupId(entry.id), openedAt: cleanupInteger(entry.openedAt), resolvedAt: entry.resolvedAt === null ? null : cleanupInteger(entry.resolvedAt), noChangeProof: entry.noChangeProof === null ? null : parseCleanupNoChange(entry.noChangeProof) };
    });
    return { id: cleanupId(item.id), revision: cleanupInteger(item.revision, 1), ref: cleanupRef(item.ref), state: cleanupChoice(item.state, ITEM_STATES), blockers: blockers(item.blockers), physicalSourceId: item.physicalSourceId === null ? null : cleanupId(item.physicalSourceId), uncertainties, executionAvailable: false };
  });
  const plan: CleanupPlanRecord = {
    schemaVersion: 1, id: cleanupId(data.id), ownerId: cleanupId(data.ownerId), revision: cleanupInteger(data.revision, 1), intent: cleanupChoice(data.intent, ["trash_specified_sources"]), state: cleanupChoice(data.state, PLAN_STATES),
    createdAt: cleanupInteger(data.createdAt), updatedAt: cleanupInteger(data.updatedAt), expiresAt: cleanupInteger(data.expiresAt, 1), expired: bool(data.expired), cancelled: bool(data.cancelled), items, keepers: data.keepers.map(cleanupRef), blockers: blockers(data.blockers), reviewFingerprint: data.reviewFingerprint === null ? null : String(data.reviewFingerprint), evidence: data.evidence === null ? null : parseCleanupEvidence(data.evidence), executionAvailable: false,
  };
  ensure(plan.updatedAt >= plan.createdAt && plan.expiresAt - plan.createdAt >= 1_000 && plan.expiresAt - plan.createdAt <= 900_000);
  ensure(new Set(items.map(item => item.id)).size === items.length);
  assertCleanupSelections(items.map(item => item.ref), plan.keepers);
  const uncertaintyIds = items.flatMap(item => item.uncertainties.map(entry => entry.id));
  ensure(new Set(uncertaintyIds).size === uncertaintyIds.length);
  for (const item of items) {
    ensure(item.revision === plan.revision && unresolved(item).length <= 1);
    let previous = plan.createdAt;
    for (const entry of item.uncertainties) {
      ensure(entry.openedAt >= previous && entry.openedAt <= plan.updatedAt);
      ensure((entry.resolvedAt === null) === (entry.noChangeProof === null));
      if (entry.resolvedAt !== null) {
        const proof = entry.noChangeProof!;
        ensure(entry.resolvedAt >= entry.openedAt && entry.resolvedAt <= plan.updatedAt && proof.ownerId === plan.ownerId && canonical(proof.ref) === canonical(item.ref) && proof.uncertaintyId === entry.id && proof.verifiedAt >= entry.openedAt && validInterval(proof, entry.resolvedAt));
        previous = entry.resolvedAt;
      } else { ensure(entry === item.uncertainties.at(-1)); previous = entry.openedAt; }
    }
    const expected = unresolved(item).length ? "uncertain" : plan.cancelled ? "cancelled" : plan.expired ? "expired" : plan.evidence ? (plan.state === "confirmed_nonexecuting" ? "confirmed_nonexecuting" : "reviewable") : item.uncertainties.length ? "reconciled_no_change" : "blocked";
    ensure(item.state === expected);
    ensure(!unresolved(item).length || item.blockers.includes("uncertainty"));
  }
  ensure(canonical(plan.blockers) === canonical(unique(items.flatMap(item => item.blockers))));
  ensure((plan.reviewFingerprint === null) === (plan.evidence === null));
  if (plan.evidence !== null) {
    ensure(typeof data.reviewFingerprint === "string" && /^[0-9a-f]{64}$/.test(data.reviewFingerprint) && !plan.cancelled && !plan.expired && plan.blockers.length === 0 && fingerprint(plan, plan.evidence) === plan.reviewFingerprint);
    const checked = evaluate(plan, plan.evidence, plan.updatedAt);
    ensure(checked.evidence !== null && [...checked.blockers.values()].every(values => values.length === 0));
    ensure(items.every(item => item.physicalSourceId === plan.evidence!.sources.find(source => source.ref.sourceId === item.ref.sourceId)!.physicalSourceId));
  } else ensure(plan.blockers.length > 0);
  const state = plan.blockers.includes("uncertainty") ? "uncertain" : plan.cancelled ? "cancelled" : plan.expired ? "expired" : plan.evidence ? (plan.state === "confirmed_nonexecuting" ? "confirmed_nonexecuting" : "reviewable") : "blocked";
  ensure(plan.state === state && (!plan.expired || (plan.updatedAt >= plan.expiresAt && plan.blockers.includes("expired"))) && (!plan.cancelled || plan.blockers.includes("cancelled")));
  return plan;
}

const migration = readFileSync(new URL("../../../db/cleanup/001_journal.sql", import.meta.url), "utf8");
ensure(migration.trim().length > 0);
function objects(db: DatabaseSync): unknown[] {
  return db.prepare("SELECT type, name, tbl_name, sql FROM sqlite_schema WHERE substr(name, 1, 7) != 'sqlite_' ORDER BY type, name").all();
}
const reference = new DatabaseSync(":memory:");
reference.exec(migration);
const expectedObjects = canonical(objects(reference));
reference.close();

/** Internal local-development planner. No HTTP integration, execution or capability token. */
export class CleanupPlans {
  private readonly db: DatabaseSync;
  private readonly adapter: TrustedCleanupEvidence | undefined;
  private readonly now: () => number;
  private observedTime = 0;

  constructor(path: string, options: CleanupPlansOptions = {}) {
    this.adapter = options.trustedEvidence;
    this.now = options.now ?? Date.now;
    // A readonly preflight also preserves foreign journal modes and sidecar state.
    if (path !== ":memory:" && existsSync(path)) {
      const preflight = new DatabaseSync(path, { readOnly: true });
      try { this.readValidation(preflight, true); } finally { preflight.close(); }
    }
    this.db = new DatabaseSync(path);
    try {
      const empty = this.readValidation(this.db, true);
      if (empty) {
        // BEGIN IMMEDIATE initializes a header page itself. A deferred read
        // snapshot preserves the page-count-zero proof; concurrent replacement
        // must fail the read-to-write upgrade rather than reuse a foreign file.
        this.db.exec("BEGIN");
        try { ensure(this.validateDatabase(this.db, true)); this.db.exec(migration); this.db.exec("COMMIT"); }
        catch (error) { this.db.exec("ROLLBACK"); throw error; }
      }
      this.readValidation(this.db);
      this.db.exec("PRAGMA busy_timeout = 5000; PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL; PRAGMA synchronous = FULL;");
    } catch (error) { this.db.close(); throw error; }
  }

  close(): void { this.db.close(); }

  createPlan(ownerId: string, value: unknown): CleanupCommandResult {
    const command = parseCreateCleanupPlan(value);
    return this.operation(ownerId, { action: "create", planId: null, itemId: null, command }, at => {
      ensure(this.count("cleanup_plans") < MAX_PLANS);
      const expiresAt = cleanupInteger(at + command.expiresInMs, 1);
      const plan: CleanupPlanRecord = {
        schemaVersion: 1, id: randomUUID(), ownerId, revision: 1, intent: command.intent, state: "blocked", createdAt: at, updatedAt: at, expiresAt, expired: false, cancelled: false,
        items: command.targets.map(ref => ({ id: randomUUID(), revision: 1, ref, state: "blocked", blockers: ["evidence_unavailable"], physicalSourceId: null, uncertainties: [], executionAvailable: false })), keepers: command.keepers, blockers: ["evidence_unavailable"], reviewFingerprint: null, evidence: null, executionAvailable: false,
      };
      this.review(plan, at);
      plan.updatedAt = this.sampleTime(at);
      this.fence(plan, plan.updatedAt);
      this.save(plan, null);
      return plan;
    });
  }

  getPlan(ownerId: string, planId: string): CleanupPlanRecord {
    cleanupId(ownerId); cleanupId(planId);
    const observed = this.tick();
    try { return this.transaction(() => {
      this.validateDatabase(this.db);
      const at = this.sampleTime(observed);
      const plan = this.load(ownerId, planId), before = canonical(plan);
      this.fence(plan, at);
      if (canonical(plan) !== before) this.advanceAndSave(plan, at);
      return copy(plan);
    }); } catch (error) { this.tick(); throw error; }
  }

  listPlans(ownerId: string): CleanupPlanRecord[] {
    cleanupId(ownerId);
    this.tick();
    const ids = this.db.prepare("SELECT plan_id FROM cleanup_plans WHERE owner_id = ? ORDER BY rowid DESC LIMIT 100").all(ownerId) as { plan_id: string }[];
    return ids.map(row => this.getPlan(ownerId, row.plan_id));
  }

  revalidatePlan(ownerId: string, planId: string, value: unknown): CleanupCommandResult {
    const command = parseCleanupRevision(value);
    return this.operation(ownerId, { action: "revalidate", planId, itemId: null, command }, at => {
      const plan = this.loadRevision(ownerId, planId, command.expectedRevision);
      this.fence(plan, at);
      if (!plan.expired && !plan.cancelled && !this.hasRelatedUncertainty(plan)) this.review(plan, at);
      this.advanceAndSave(plan, at);
      return plan;
    });
  }

  confirmPlan(ownerId: string, planId: string, value: unknown): CleanupCommandResult {
    const command = parseConfirmCleanup(value);
    return this.operation(ownerId, { action: "confirm", planId, itemId: null, command }, at => {
      const plan = this.loadRevision(ownerId, planId, command.expectedRevision);
      this.fence(plan, at);
      if (plan.expired || plan.cancelled || plan.blockers.includes("uncertainty")) { this.advanceAndSave(plan, at); return plan; }
      if (plan.state !== "reviewable" || plan.reviewFingerprint !== command.expectedReviewFingerprint) fault("cleanup_review_required", "Review the current exact cleanup intent before confirming.", 409, "policy", "Read and revalidate the plan.");
      const checked = this.inspect(plan, at);
      this.fence(plan, this.currentTime(at));
      const unchanged = checked.evidence !== null && fingerprint(plan, checked.evidence) === plan.reviewFingerprint && [...checked.blockers.values()].every(values => values.length === 0);
      if (!unchanged) {
        this.clearReview(plan, "review_changed");
        for (const item of plan.items) item.blockers = unique([...item.blockers, ...checked.blockers.get(item.id)!]);
      } else plan.state = "confirmed_nonexecuting";
      this.summarize(plan);
      this.advanceAndSave(plan, at);
      return plan;
    });
  }

  cancelPlan(ownerId: string, planId: string, value: unknown): CleanupCommandResult {
    const command = parseCleanupRevision(value);
    return this.operation(ownerId, { action: "cancel", planId, itemId: null, command }, at => {
      const plan = this.loadRevision(ownerId, planId, command.expectedRevision);
      plan.cancelled = true;
      this.clearReview(plan, "cancelled");
      this.fence(plan, at);
      this.advanceAndSave(plan, at);
      return plan;
    });
  }

  markItemUncertain(ownerId: string, planId: string, itemId: string, value: unknown): CleanupCommandResult {
    const command = parseCleanupItemCommand(value, true) as MarkCleanupUncertain;
    return this.operation(ownerId, { action: "uncertain", planId, itemId, command }, at => {
      const plan = this.loadRevision(ownerId, planId, command.expectedRevision);
      const item = this.itemRevision(plan, itemId, command.expectedItemRevision);
      if (unresolved(item).length || item.uncertainties.length >= MAX_UNCERTAINTIES || this.allPlans().some(record => record.ownerId === ownerId && record.items.some(member => member.uncertainties.some(entry => entry.id === command.uncertaintyId)))) {
        fault("cleanup_uncertainty_conflict", "Uncertainty is already recorded or its retained history is full.", 409, "policy", "Read the current item. Resolve any open uncertainty with trusted evidence; retain the journal if its history is full.");
      }
      item.uncertainties.push({ id: command.uncertaintyId, openedAt: at, resolvedAt: null, noChangeProof: null });
      this.clearReview(plan, "uncertainty");
      this.fence(plan, at);
      this.advanceAndSave(plan, at);
      // Invalidate other reviewed intents in the same transaction, including aliases.
      for (const other of this.allPlans().filter(record => record.ownerId === ownerId && record.id !== plan.id)) {
        if (this.hasRelatedUncertainty(other)) {
          const before = canonical(other);
          this.clearReview(other, "uncertainty"); this.summarize(other);
          if (canonical(other) !== before) this.advanceAndSave(other, at);
        }
      }
      return plan;
    });
  }

  reconcileItem(ownerId: string, planId: string, itemId: string, value: unknown): CleanupCommandResult {
    const command = parseCleanupItemCommand(value);
    return this.operation(ownerId, { action: "reconcile", planId, itemId, command }, at => {
      const plan = this.loadRevision(ownerId, planId, command.expectedRevision);
      const item = this.itemRevision(plan, itemId, command.expectedItemRevision), pending = unresolved(item)[0];
      if (!pending) fault("cleanup_not_uncertain", "This item has no unresolved uncertainty.", 409, "policy");
      let proof: CleanupNoChangeEvidence | null = null;
      try {
        const value = this.adapter?.verifyNoChange(ownerId, copy(item.ref), pending.id);
        if (value != null) proof = parseCleanupNoChange(value);
      } catch { /* Untrusted/malformed adapter output cannot clear uncertainty. */ }
      const checkedAt = this.sampleTime(at);
      if (!proof || proof.ownerId !== ownerId || canonical(proof.ref) !== canonical(item.ref) || proof.uncertaintyId !== pending.id || proof.verifiedAt < pending.openedAt || !validInterval(proof, checkedAt)) {
        fault("cleanup_reconciliation_unverified", "The source outcome remains uncertain.", 409, "policy", "Obtain trusted current no-change evidence; do not repeat a destructive call.");
      }
      pending.resolvedAt = checkedAt; pending.noChangeProof = proof;
      this.clearReview(plan, "review_changed");
      for (const member of plan.items) member.blockers = member.blockers.filter(reason => reason !== "uncertainty");
      this.fence(plan, at);
      this.advanceAndSave(plan, at);
      return plan;
    });
  }

  private inspect(plan: CleanupPlanRecord, at: number): Evaluation {
    let evidence: CleanupEvidence | null = null;
    try {
      const value = this.adapter?.inspect(plan.ownerId, copy({ targets: plan.items.map(item => item.ref), keepers: plan.keepers }));
      if (value == null) return evaluate(plan, null, this.sampleTime(at));
      evidence = parseCleanupEvidence(value);
    } catch { return evaluate(plan, null, this.sampleTime(at), "evidence_invalid"); }
    return evaluate(plan, evidence, this.sampleTime(at));
  }

  private review(plan: CleanupPlanRecord, at: number): void {
    const checked = this.inspect(plan, at);
    plan.evidence = null; plan.reviewFingerprint = null; plan.state = "blocked";
    for (const item of plan.items) {
      item.blockers = checked.blockers.get(item.id)!;
      if (checked.evidence) {
        const physical = checked.evidence.sources.find(source => source.ref.sourceId === item.ref.sourceId)!.physicalSourceId;
        if (item.physicalSourceId === null) item.physicalSourceId = physical;
        else if (item.physicalSourceId !== physical) item.blockers = unique([...item.blockers, "source_alias"]);
      }
    }
    if (this.hasRelatedUncertainty(plan, checked.evidence)) for (const item of plan.items) item.blockers = unique([...item.blockers, "uncertainty"]);
    if (checked.evidence && plan.items.every(item => item.blockers.length === 0)) {
      plan.evidence = checked.evidence; plan.reviewFingerprint = fingerprint(plan, checked.evidence); plan.state = "reviewable";
    }
    this.fence(plan, this.currentTime(at));
  }

  private clearReview(plan: CleanupPlanRecord, reason: CleanupBlocker): void {
    plan.evidence = null; plan.reviewFingerprint = null;
    for (const item of plan.items) item.blockers = unique([...item.blockers, reason]);
  }

  private fence(plan: CleanupPlanRecord, at: number): void {
    if (at >= plan.expiresAt) plan.expired = true;
    if (plan.expired) this.clearReview(plan, "expired");
    if (plan.cancelled) this.clearReview(plan, "cancelled");
    if (plan.evidence && !validInterval(plan.evidence, at)) this.clearReview(plan, "evidence_expired");
    if (this.hasRelatedUncertainty(plan)) this.clearReview(plan, "uncertainty");
    this.summarize(plan);
  }

  private summarize(plan: CleanupPlanRecord): void {
    for (const item of plan.items) {
      if (unresolved(item).length) item.blockers = unique([...item.blockers, "uncertainty"]);
      item.state = unresolved(item).length ? "uncertain" : plan.cancelled ? "cancelled" : plan.expired ? "expired" : plan.evidence ? (plan.state === "confirmed_nonexecuting" ? "confirmed_nonexecuting" : "reviewable") : item.uncertainties.length ? "reconciled_no_change" : "blocked";
    }
    plan.blockers = unique(plan.items.flatMap(item => item.blockers));
    plan.state = plan.blockers.includes("uncertainty") ? "uncertain" : plan.cancelled ? "cancelled" : plan.expired ? "expired" : plan.evidence ? (plan.state === "confirmed_nonexecuting" ? "confirmed_nonexecuting" : "reviewable") : "blocked";
  }

  private hasRelatedUncertainty(plan: CleanupPlanRecord, evidence = plan.evidence): boolean {
    const refs = [...plan.items.map(item => item.ref), ...plan.keepers];
    const physical = new Set([...plan.items.map(item => item.physicalSourceId), ...(evidence?.sources.map(source => source.physicalSourceId) ?? [])].filter((id): id is string => id !== null));
    const records = this.allPlans().filter(record => !(record.ownerId === plan.ownerId && record.id === plan.id));
    records.push(plan);
    return records.some(record => record.ownerId === plan.ownerId && record.items.some(item => {
      if (!unresolved(item).length) return false;
      if (item.physicalSourceId === null) return true;
      return refs.some(ref => ref.sourceId === item.ref.sourceId || ref.versionId === item.ref.versionId || ref.assetId === item.ref.assetId) || physical.has(item.physicalSourceId);
    }));
  }

  private itemRevision(plan: CleanupPlanRecord, itemId: string, revision: number): CleanupItemRecord {
    cleanupId(itemId);
    const item = plan.items.find(item => item.id === itemId);
    if (!item || item.revision !== revision) fault("cleanup_revision_conflict", "The cleanup item changed or is unavailable.", 409, "policy", "Read the current plan and review its items.");
    return item;
  }
  private loadRevision(ownerId: string, planId: string, revision: number): CleanupPlanRecord {
    const plan = this.load(ownerId, planId);
    if (plan.revision !== revision) fault("cleanup_revision_conflict", "The cleanup plan changed.", 409, "policy", "Read and review the current plan.");
    return plan;
  }
  private load(ownerId: string, planId: string): CleanupPlanRecord {
    const row = this.db.prepare("SELECT * FROM cleanup_plans WHERE owner_id = ? AND plan_id = ?").get(ownerId, planId) as Row | undefined;
    if (!row) fault("cleanup_plan_not_found", "This cleanup plan is unavailable.", 404, "policy", "Read the current owner's plan list.");
    return this.decodeRow(row);
  }
  private decodeRow(row: Row): CleanupPlanRecord {
    ensure(Buffer.byteLength(row.record_json) <= MAX_RECORD_BYTES);
    const plan = decodePlan(JSON.parse(row.record_json));
    ensure(plan.ownerId === row.owner_id && plan.id === row.plan_id && plan.revision === row.revision && plan.state === row.state && plan.expiresAt === row.expires_at);
    return plan;
  }
  private allPlans(db = this.db): CleanupPlanRecord[] {
    return (db.prepare("SELECT * FROM cleanup_plans").all() as Row[]).map(row => this.decodeRow(row));
  }
  private advanceAndSave(plan: CleanupPlanRecord, at: number): void {
    at = this.sampleTime(at);
    this.fence(plan, at);
    const prior = plan.revision;
    plan.revision = next(prior); plan.updatedAt = at;
    for (const item of plan.items) item.revision = plan.revision;
    this.save(plan, prior);
  }
  private save(plan: CleanupPlanRecord, prior: number | null): void {
    decodePlan(plan);
    const json = JSON.stringify(plan);
    ensure(Buffer.byteLength(json) <= MAX_RECORD_BYTES);
    if (prior === null) this.db.prepare("INSERT INTO cleanup_plans VALUES (?, ?, ?, ?, ?, ?)").run(plan.ownerId, plan.id, plan.revision, plan.state, plan.expiresAt, json);
    else {
      const result = this.db.prepare("UPDATE cleanup_plans SET revision = ?, state = ?, expires_at = ?, record_json = ? WHERE owner_id = ? AND plan_id = ? AND revision = ?").run(plan.revision, plan.state, plan.expiresAt, json, plan.ownerId, plan.id, prior);
      ensure(result.changes === 1);
    }
  }

  private operation(ownerId: string, descriptor: Descriptor, action: (at: number) => CleanupPlanRecord): CleanupCommandResult {
    cleanupId(ownerId);
    if (descriptor.planId !== null) cleanupId(descriptor.planId);
    if (descriptor.itemId !== null) cleanupId(descriptor.itemId);
    const key = (descriptor.command as { idempotencyKey: string }).idempotencyKey;
    const observed = this.tick();
    try { return this.transaction(() => {
      this.validateDatabase(this.db);
      const at = this.sampleTime(observed);
      const prior = this.db.prepare("SELECT * FROM cleanup_operations WHERE owner_id = ? AND idempotency_key = ?").get(ownerId, key) as Operation | undefined;
      if (prior) {
        if (prior.fingerprint !== hash(descriptor)) fault("cleanup_idempotency_conflict", "This cleanup command key already has different intent.", 409, "policy", "Use a new key only after reviewing the intended change.");
        return { plan: copy(decodePlan(JSON.parse(prior.result_json))), replayed: true };
      }
      ensure(this.count("cleanup_operations") < MAX_OPERATIONS);
      const plan = action(at);
      this.db.prepare("INSERT INTO cleanup_operations VALUES (?, ?, ?, ?, ?)").run(ownerId, key, hash(descriptor), canonical(descriptor), JSON.stringify(plan));
      this.db.prepare("UPDATE cleanup_meta SET operation_count = operation_count + 1 WHERE singleton = 1").run();
      return { plan: copy(plan), replayed: false };
    }); } catch (error) {
      this.tick(); // Retain observed time even when the state transaction rolls back.
      throw error;
    }
  }
  private tick(): number {
    // Persist observation of time separately: even a rejected command must not let
    // a later clock rollback revive a previously observed expired review.
    return this.transaction(() => {
      this.validateDatabase(this.db);
      const observed = Math.max(cleanupInteger(this.now()), this.observedTime);
      this.observedTime = observed;
      const meta = this.db.prepare("SELECT clock_high_water FROM cleanup_meta WHERE singleton = 1").get() as { clock_high_water: number };
      const at = Math.max(meta.clock_high_water, observed);
      this.db.prepare("UPDATE cleanup_meta SET clock_high_water = ? WHERE singleton = 1").run(at);
      return at;
    });
  }
  private currentTime(observed: number): number {
    const meta = this.db.prepare("SELECT clock_high_water FROM cleanup_meta WHERE singleton = 1").get() as { clock_high_water: number };
    return Math.max(observed, cleanupInteger(meta.clock_high_water));
  }
  private sampleTime(observed: number): number {
    const at = Math.max(this.currentTime(observed), cleanupInteger(this.now()), this.observedTime);
    this.observedTime = at;
    this.db.prepare("UPDATE cleanup_meta SET clock_high_water = ? WHERE singleton = 1").run(at);
    return at;
  }
  private count(table: "cleanup_plans" | "cleanup_operations"): number {
    return (this.db.prepare(`SELECT COUNT(*) AS count FROM ${table}`).get() as { count: number }).count;
  }
  private transaction<T>(action: () => T): T {
    this.db.exec("BEGIN IMMEDIATE");
    try { const result = action(); this.db.exec("COMMIT"); return result; }
    catch (error) { this.db.exec("ROLLBACK"); throw error; }
  }
  private readValidation(db: DatabaseSync, allowEmpty = false): boolean {
    db.exec("BEGIN");
    try { const empty = this.validateDatabase(db, allowEmpty); db.exec("COMMIT"); return empty; }
    catch (error) { db.exec("ROLLBACK"); throw error; }
  }

  /** Read-only checks; run before any journal-mode/DDL mutation on existing files. */
  private validateDatabase(db: DatabaseSync, allowEmpty = false): boolean {
    const checks = db.prepare("PRAGMA quick_check").all() as { quick_check: string }[];
    ensure(checks.length === 1 && checks[0].quick_check === "ok");
    const version = (db.prepare("PRAGMA user_version").get() as { user_version: number }).user_version;
    const application = (db.prepare("PRAGMA application_id").get() as { application_id: number }).application_id;
    const schema = canonical(objects(db));
    const pages = (db.prepare("PRAGMA page_count").get() as { page_count: number }).page_count;
    if (allowEmpty && version === 0 && application === 0 && schema === "[]" && pages === 0) return true;
    ensure(version === 1 && application === APPLICATION_ID && schema === expectedObjects);
    const metadata = db.prepare("SELECT * FROM cleanup_meta").all() as { singleton: number; identity: string; schema_version: number; clock_high_water: number; operation_count: number }[];
    ensure(metadata.length === 1 && metadata[0].singleton === 1 && metadata[0].identity === "still-cleanup-journal" && metadata[0].schema_version === 1);
    const highWater = cleanupInteger(metadata[0].clock_high_water);
    const plans = this.allPlans(db);
    ensure(plans.length <= MAX_PLANS && plans.every(plan => plan.updatedAt <= highWater));
    const uncertaintyKeys = plans.flatMap(plan => plan.items.flatMap(item => item.uncertainties.map(entry => `${plan.ownerId}:${entry.id}`)));
    ensure(new Set(uncertaintyKeys).size === uncertaintyKeys.length);
    const operations = db.prepare("SELECT * FROM cleanup_operations").all() as Operation[];
    ensure(operations.length <= MAX_OPERATIONS && operations.length === cleanupInteger(metadata[0].operation_count, 0, MAX_OPERATIONS));
    const creates = new Set<string>(), cancellations = new Set<string>(), confirmations = new Set<string>(), openings = new Set<string>(), resolutions = new Set<string>();
    for (const row of operations) {
      cleanupId(row.owner_id); cleanupId(row.idempotency_key);
      ensure(Buffer.byteLength(row.command_json) <= MAX_RECORD_BYTES && Buffer.byteLength(row.result_json) <= MAX_RECORD_BYTES);
      const descriptor = cleanupObject(JSON.parse(row.command_json), ["action", "planId", "itemId", "command"]);
      const action = cleanupChoice(descriptor.action, ["create", "revalidate", "confirm", "cancel", "uncertain", "reconcile"]);
      const command = action === "create" ? parseCreateCleanupPlan(descriptor.command) : action === "confirm" ? parseConfirmCleanup(descriptor.command) : action === "uncertain" ? parseCleanupItemCommand(descriptor.command, true) : action === "reconcile" ? parseCleanupItemCommand(descriptor.command) : parseCleanupRevision(descriptor.command);
      ensure(command.idempotencyKey === row.idempotency_key && hash(descriptor) === row.fingerprint);
      if (action === "create") ensure(descriptor.planId === null && descriptor.itemId === null);
      else { cleanupId(descriptor.planId); if (action === "uncertain" || action === "reconcile") cleanupId(descriptor.itemId); else ensure(descriptor.itemId === null); }
      const result = decodePlan(JSON.parse(row.result_json));
      const current = plans.find(plan => plan.ownerId === row.owner_id && plan.id === result.id);
      ensure(current && result.ownerId === row.owner_id && result.revision <= current.revision && result.updatedAt <= highWater && (action === "create" || descriptor.planId === result.id));
      ensure(canonical(result.items.map(item => ({ id: item.id, ref: item.ref }))) === canonical(current.items.map(item => ({ id: item.id, ref: item.ref }))) && canonical(result.keepers) === canonical(current.keepers) && result.expiresAt === current.expiresAt && result.createdAt === current.createdAt);
      ensure((!result.cancelled || current.cancelled) && (!result.expired || current.expired));
      if (action === "create") {
        const created = parseCreateCleanupPlan(command);
        ensure(result.revision === 1 && !result.cancelled && result.state !== "confirmed_nonexecuting" && result.items.every(item => item.uncertainties.length === 0));
        ensure(canonical(created.targets) === canonical(result.items.map(item => item.ref)) && canonical(created.keepers) === canonical(result.keepers) && result.expiresAt - result.createdAt === created.expiresInMs);
        const key = `${result.ownerId}:${result.id}`;
        ensure(!creates.has(key)); creates.add(key);
      } else {
        const expected = (command as { expectedRevision: number }).expectedRevision;
        ensure(result.revision === expected + 1);
        if (action === "revalidate") ensure(result.state !== "confirmed_nonexecuting");
        if (action === "confirm") {
          ensure(result.state !== "reviewable");
          if (result.state === "confirmed_nonexecuting") {
            ensure(result.reviewFingerprint === parseConfirmCleanup(command).expectedReviewFingerprint);
            confirmations.add(`${result.ownerId}:${result.id}:${result.revision}:${result.reviewFingerprint}`);
          }
        }
        if (action === "cancel") { ensure(result.cancelled); cancellations.add(`${result.ownerId}:${result.id}`); }
        if (action === "uncertain" || action === "reconcile") {
          const member = result.items.find(item => item.id === descriptor.itemId);
          ensure(member && parseCleanupItemCommand(descriptor.command, action === "uncertain").expectedItemRevision === expected);
          const last = member.uncertainties.at(-1);
          ensure(last && (action === "uncertain" ? last.id === (command as MarkCleanupUncertain).uncertaintyId && last.resolvedAt === null : last.resolvedAt !== null));
          ensure(result.state !== "reviewable" && result.state !== "confirmed_nonexecuting");
          const key = `${result.ownerId}:${result.id}:${member.id}:${last.id}`;
          if (action === "uncertain") { ensure(!openings.has(key)); openings.add(key); }
          else { ensure(!resolutions.has(key)); resolutions.add(key); }
        }
      }
      for (const item of result.items) {
        const member = current.items.find(member => member.id === item.id)!;
        if (item.physicalSourceId !== null) ensure(member.physicalSourceId === item.physicalSourceId);
        ensure(item.uncertainties.length <= member.uncertainties.length);
        item.uncertainties.forEach((entry, index) => {
          const later = member.uncertainties[index];
          ensure(entry.id === later.id && entry.openedAt === later.openedAt && (entry.resolvedAt === null || canonical(entry) === canonical(later)));
        });
      }
      if (result.revision === current.revision) ensure(canonical(result) === canonical(current));
    }
    for (const plan of plans) {
      const key = `${plan.ownerId}:${plan.id}`;
      ensure(creates.has(key) && (!plan.cancelled || cancellations.has(key)));
      if (plan.state === "confirmed_nonexecuting") ensure(confirmations.has(`${key}:${plan.revision}:${plan.reviewFingerprint}`));
      for (const item of plan.items) for (const entry of item.uncertainties) {
        const memberKey = `${key}:${item.id}:${entry.id}`;
        ensure(openings.has(memberKey) && (entry.resolvedAt === null || resolutions.has(memberKey)));
      }
    }
    return false;
  }
}
