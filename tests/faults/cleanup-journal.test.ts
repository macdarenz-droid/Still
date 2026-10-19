import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import type { TestContext } from "node:test";
import { ControlPlaneError } from "../../packages/contracts/src/index.ts";
import type { CleanupEvidence, CleanupNoChangeEvidence, CleanupPlanRecord, CleanupSourceRef } from "../../packages/contracts/src/cleanup.ts";
import { CleanupPlans } from "../../services/workflows/src/cleanup-plans.ts";
import type { TrustedCleanupEvidence } from "../../services/workflows/src/cleanup-plans.ts";
import { ControlPlane } from "../../services/workflows/src/store.ts";

const OWNER = "owner-a", BASE = 10_000;
const ref = (sourceId: string, assetId = "asset-a", versionId = "version-a"): CleanupSourceRef => ({ assetId, versionId, sourceId, expectedSourceRevision: 1 });
const create = (key = "create", targets = [ref("target")], keepers = [ref("keeper")]) => ({ schemaVersion: 1, idempotencyKey: key, intent: "trash_specified_sources", targets, keepers, expiresInMs: 60_000 });
const revision = (plan: CleanupPlanRecord, key: string) => ({ schemaVersion: 1, idempotencyKey: key, expectedRevision: plan.revision });
const itemCommand = (plan: CleanupPlanRecord, key: string) => ({ ...revision(plan, key), expectedItemRevision: plan.items[0].revision });
const confirm = (plan: CleanupPlanRecord, key = "confirm") => ({ ...revision(plan, key), expectedReviewFingerprint: plan.reviewFingerprint });
function evidence(ownerId: string, targets: CleanupSourceRef[], keepers: CleanupSourceRef[]): CleanupEvidence {
  return {
    schemaVersion: 1, ownerId, evidenceRevision: 1, verifiedAt: BASE, validUntil: BASE + 120_000,
    protectionRevision: 0, favoriteRevision: 0, dependencyRevision: 0, copyRevision: 0,
    sources: [...targets, ...keepers].map(ref => ({ ref: structuredClone(ref), physicalSourceId: `physical-${ref.sourceId}`, pinProofId: "pinned-proof", representation: "original_bytes", access: "granted", protected: false, favorite: false, requiredDependency: false })),
    copies: targets.flatMap(target => {
      const keeper = keepers.find(keeper => keeper.assetId === target.assetId && keeper.versionId === target.versionId);
      return keeper ? [{ targetSourceId: target.sourceId, keeperSourceId: keeper.sourceId, versionId: target.versionId, identityProofId: "identity-proof", independenceProofId: "independence-proof", restoreProofId: "restore-proof" }] : [];
    }),
  };
}
function fixture(t: TestContext, trusted = true) {
  const directory = mkdtempSync(join(tmpdir(), "still-cleanup-")), path = join(directory, "journal.sqlite");
  const state = { time: BASE, mutate: (value: CleanupEvidence): unknown => value, allowNoChange: false, mutateNoChange: (value: CleanupNoChangeEvidence): unknown => value };
  const adapter: TrustedCleanupEvidence = {
    inspect(owner, selection) { return state.mutate(evidence(owner, selection.targets, selection.keepers)); },
    verifyNoChange(ownerId, source, uncertaintyId) {
      if (!state.allowNoChange) return null;
      return state.mutateNoChange({ schemaVersion: 1, ownerId, ref: source, uncertaintyId, result: "verified_no_change", proofId: "no-change-proof", verifiedAt: state.time, validUntil: state.time + 60_000 });
    },
  };
  const stores: CleanupPlans[] = [];
  const open = () => { const value = new CleanupPlans(path, { now: () => state.time, ...(trusted ? { trustedEvidence: adapter } : {}) }); stores.push(value); return value; };
  t.after(() => { for (const store of stores) { try { store.close(); } catch { /* already explicitly closed */ } } rmSync(directory, { recursive: true, force: true }); });
  return { directory, path, state, adapter, open, store: open() };
}
function fails(action: () => unknown, code: string) {
  assert.throws(action, error => error instanceof ControlPlaneError && error.detail.code === code);
}
function inactive(plan: CleanupPlanRecord) {
  assert.equal(plan.executionAvailable, false);
  assert.ok(plan.items.every(item => item.executionAvailable === false));
}
function mutateCurrent(path: string, id: string, mutate: (plan: CleanupPlanRecord) => void) {
  const db = new DatabaseSync(path);
  const row = db.prepare("SELECT record_json FROM cleanup_plans WHERE plan_id = ?").get(id) as { record_json: string };
  const plan = JSON.parse(row.record_json) as CleanupPlanRecord;
  mutate(plan);
  db.prepare("UPDATE cleanup_plans SET revision = ?, state = ?, expires_at = ?, record_json = ? WHERE plan_id = ?").run(plan.revision, plan.state, plan.expiresAt, JSON.stringify(plan), id);
  db.close();
}
function failOneWrite(store: CleanupPlans, sqlPrefix: string): () => void {
  const db = Reflect.get(store, "db") as DatabaseSync;
  const original = db.prepare.bind(db);
  let fail = true;
  Reflect.set(db, "prepare", (sql: string) => {
    if (fail && sql.startsWith(sqlPrefix)) return { run() { fail = false; throw new Error("injected transaction fault"); } };
    return original(sql);
  });
  return () => { Reflect.set(db, "prepare", original); };
}

test("S25: default adapter denies review, owner boundaries and returned copies survive reopen", t => {
  const f = fixture(t, false), command = create();
  const first = f.store.createPlan(OWNER, command);
  assert.equal(first.plan.state, "blocked"); assert.deepEqual(first.plan.blockers, ["evidence_unavailable"]); inactive(first.plan);
  command.targets[0].assetId = "changed-input";
  first.plan.items[0].ref.assetId = "changed-output"; first.plan.blockers.length = 0;
  const saved = f.store.getPlan(OWNER, first.plan.id);
  assert.equal(saved.items[0].ref.assetId, "asset-a"); assert.deepEqual(saved.blockers, ["evidence_unavailable"]);
  fails(() => f.store.getPlan("owner-b", saved.id), "cleanup_plan_not_found");
  fails(() => f.store.cancelPlan("owner-b", saved.id, revision(saved, "wrong-owner")), "cleanup_plan_not_found");
  assert.deepEqual(f.store.listPlans("owner-b"), []);
  f.store.close(); const reopened = f.open();
  const replay = reopened.createPlan(OWNER, create());
  assert.equal(replay.replayed, true); assert.deepEqual(replay.plan, saved); inactive(replay.plan);
  fails(() => reopened.createPlan(OWNER, { ...create(), expiresInMs: 70_000 }), "cleanup_idempotency_conflict");
});

test("S25: genuine trusted review and confirmation remain nonexecuting and replay is historical", t => {
  const f = fixture(t);
  const reviewed = f.store.createPlan(OWNER, create()).plan;
  assert.equal(reviewed.state, "reviewable"); assert.ok(reviewed.reviewFingerprint); inactive(reviewed);
  const confirmed = f.store.confirmPlan(OWNER, reviewed.id, confirm(reviewed)).plan;
  assert.equal(confirmed.state, "confirmed_nonexecuting"); inactive(confirmed);
  const cancelled = f.store.cancelPlan(OWNER, confirmed.id, revision(confirmed, "cancel")).plan;
  const replay = f.store.confirmPlan(OWNER, reviewed.id, confirm(reviewed));
  assert.equal(replay.replayed, true); assert.deepEqual(replay.plan, confirmed);
  assert.equal(f.store.getPlan(OWNER, reviewed.id).state, "cancelled");
  fails(() => f.store.revalidatePlan(OWNER, reviewed.id, revision(reviewed, "stale")), "cleanup_revision_conflict");
  const stillCancelled = f.store.revalidatePlan(OWNER, reviewed.id, revision(cancelled, "no-revival")).plan;
  assert.equal(stillCancelled.cancelled, true); assert.equal(stillCancelled.reviewFingerprint, null);
});

test("S25: confirmation compares every trusted fact, even with unchanged proof and evidence revision", t => {
  const f = fixture(t);
  const changes: ((value: CleanupEvidence) => void)[] = [
    value => { value.sources[0].protected = true; }, value => { value.sources[0].favorite = true; },
    value => { value.sources[0].requiredDependency = true; }, value => { value.sources[0].access = "denied"; },
    value => { value.sources[0].pinProofId = null; }, value => { value.sources[0].representation = "other"; },
    value => { value.sources[0].physicalSourceId = "changed-physical"; }, value => { value.protectionRevision++; },
    value => { value.favoriteRevision++; }, value => { value.dependencyRevision++; }, value => { value.copyRevision++; },
    value => { value.verifiedAt--; }, value => { value.validUntil--; },
    value => { value.copies[0].identityProofId = "changed-identity"; }, value => { value.copies[0].independenceProofId = "changed-independent"; }, value => { value.copies[0].restoreProofId = "changed-restore"; },
  ];
  for (const [index, change] of changes.entries()) {
    f.state.mutate = value => value;
    const plan = f.store.createPlan(OWNER, create(`create-${index}`)).plan;
    f.state.mutate = value => { change(value); return value; };
    const denied = f.store.confirmPlan(OWNER, plan.id, confirm(plan, `confirm-${index}`)).plan;
    assert.equal(denied.state, "blocked", `fact ${index}`); assert.ok(denied.blockers.includes("review_changed"));
    assert.equal(denied.reviewFingerprint, null); inactive(denied);
    fails(() => f.store.confirmPlan(OWNER, denied.id, { ...confirm(plan, `repeat-${index}`), expectedRevision: denied.revision }), "cleanup_review_required");
  }
});

test("S25: malformed, missing, wrong-owner/ref and aliased evidence cannot create readiness", t => {
  const f = fixture(t);
  const changes: ((value: CleanupEvidence) => unknown)[] = [
    () => null, () => true, value => ({ ...value, ownerId: "other-owner" }),
    value => ({ ...value, callerPinned: true }), value => ({ ...value, sources: value.sources.slice(1) }),
    value => ({ ...value, sources: [value.sources[0], value.sources[0]] }),
    value => { value.sources[0].ref.expectedSourceRevision++; return value; },
    value => { value.sources[0].physicalSourceId = value.sources[1].physicalSourceId; return value; },
    value => { value.sources[0].protected = null; return value; }, value => { value.sources[0].favorite = null; return value; },
    value => { value.sources[0].requiredDependency = null; return value; }, value => { value.sources[1].access = "unknown"; return value; },
    value => { value.copies[0].keeperSourceId = "outside-scope"; return value; }, value => { value.copies[0].versionId = "wrong-version"; return value; },
    value => { value.copies.push(value.copies[0]); return value; }, value => ({ ...value, verifiedAt: BASE + 1 }),
    value => ({ ...value, validUntil: BASE }), value => ({ ...value, validUntil: BASE + 300_001 }),
  ];
  for (const [index, change] of changes.entries()) {
    f.state.mutate = change;
    const plan = f.store.createPlan(OWNER, create(`invalid-${index}`)).plan;
    assert.equal(plan.state, "blocked", `evidence ${index}`); assert.ok(plan.blockers.length); assert.equal(plan.evidence, null); inactive(plan);
  }
});

test("S25: targeted copies and their physical aliases cannot vouch for one another", t => {
  const f = fixture(t), targets = [ref("target-a"), ref("target-b")];
  f.state.mutate = value => {
    value.copies[0].keeperSourceId = "target-b";
    value.copies[1].keeperSourceId = "target-a";
    return value;
  };
  let plan = f.store.createPlan(OWNER, create("circular", targets)).plan;
  assert.equal(plan.state, "blocked"); assert.ok(plan.items.every(item => item.blockers.includes("copy_unverified")));
  f.state.mutate = value => { value.sources[2].physicalSourceId = value.sources[1].physicalSourceId; return value; };
  plan = f.store.createPlan(OWNER, create("physical-circular", targets)).plan;
  assert.ok(plan.blockers.includes("source_alias")); assert.ok(plan.blockers.includes("copy_unverified"));
});

test("S25: uncertainty fences old/new plans and aliases across cancellation, replay and reopen", t => {
  const f = fixture(t);
  const first = f.store.createPlan(OWNER, create("first")).plan;
  const parallel = f.store.createPlan(OWNER, create("parallel")).plan;
  const uncertain = f.store.markItemUncertain(OWNER, first.id, first.items[0].id, { ...itemCommand(first, "uncertain"), uncertaintyId: "uncertainty-a" }).plan;
  assert.equal(uncertain.state, "uncertain"); assert.equal(f.store.getPlan(OWNER, parallel.id).state, "uncertain");
  const fresh = f.store.createPlan(OWNER, create("fresh", [ref("new-target")], [ref("new-keeper")])).plan;
  assert.equal(fresh.state, "uncertain"); // Same immutable version under new source references.
  f.state.mutate = value => { value.sources[0].physicalSourceId = "physical-target"; return value; };
  const alias = f.store.createPlan(OWNER, create("alias", [ref("alias", "different-asset", "different-version")], [ref("alias-keeper", "different-asset", "different-version")])).plan;
  assert.equal(alias.state, "uncertain");
  const cancelled = f.store.cancelPlan(OWNER, first.id, revision(uncertain, "cancel-uncertain")).plan;
  assert.equal(cancelled.cancelled, true); assert.equal(cancelled.state, "uncertain");
  const revalidated = f.store.revalidatePlan(OWNER, first.id, revision(cancelled, "revalidate-uncertain")).plan;
  assert.equal(revalidated.items[0].uncertainties[0].resolvedAt, null);
  const replay = f.store.createPlan(OWNER, create("first"));
  assert.equal(replay.replayed, true); assert.equal(replay.plan.state, "reviewable");
  f.store.close(); const reopened = f.open();
  assert.equal(reopened.getPlan(OWNER, first.id).state, "uncertain");
  fails(() => reopened.reconcileItem(OWNER, first.id, first.items[0].id, itemCommand(revalidated, "unverified")), "cleanup_reconciliation_unverified");
});

test("S25: only bound fresh trusted no-change evidence resolves an item, retaining its history", t => {
  const f = fixture(t); let plan = f.store.createPlan(OWNER, create()).plan;
  plan = f.store.markItemUncertain(OWNER, plan.id, plan.items[0].id, { ...itemCommand(plan, "uncertain"), uncertaintyId: "uncertainty" }).plan;
  f.state.allowNoChange = true;
  for (const [index, mutate] of [
    (value: CleanupNoChangeEvidence) => ({ ...value, ownerId: "wrong" }),
    (value: CleanupNoChangeEvidence) => ({ ...value, uncertaintyId: "wrong" }),
    (value: CleanupNoChangeEvidence) => ({ ...value, ref: ref("wrong") }),
    (value: CleanupNoChangeEvidence) => ({ ...value, validUntil: BASE }),
    (value: CleanupNoChangeEvidence) => ({ ...value, result: "restored" }),
  ].entries()) {
    f.state.mutateNoChange = mutate;
    fails(() => f.store.reconcileItem(OWNER, plan.id, plan.items[0].id, itemCommand(plan, `invalid-proof-${index}`)), "cleanup_reconciliation_unverified");
    assert.equal(f.store.getPlan(OWNER, plan.id).items[0].uncertainties[0].resolvedAt, null);
  }
  f.state.mutateNoChange = value => value;
  const reconciled = f.store.reconcileItem(OWNER, plan.id, plan.items[0].id, itemCommand(plan, "reconciled")).plan;
  assert.equal(reconciled.state, "blocked"); assert.equal(reconciled.items[0].state, "reconciled_no_change");
  assert.equal(reconciled.items[0].uncertainties[0].noChangeProof?.result, "verified_no_change");
  assert.equal(reconciled.reviewFingerprint, null); inactive(reconciled);
  f.store.close(); const reopened = f.open();
  const fresh = reopened.revalidatePlan(OWNER, plan.id, revision(reconciled, "review-again")).plan;
  assert.equal(fresh.state, "reviewable"); assert.equal(fresh.items[0].uncertainties.length, 1);
});

test("S25: uncertainty history capacity rejects additions instead of pruning recovery evidence", t => {
  const f = fixture(t); f.state.allowNoChange = true;
  let plan = f.store.createPlan(OWNER, create()).plan;
  for (let index = 0; index < 20; index++) {
    plan = f.store.markItemUncertain(OWNER, plan.id, plan.items[0].id, { ...itemCommand(plan, `open-${index}`), uncertaintyId: `uncertainty-${index}` }).plan;
    plan = f.store.reconcileItem(OWNER, plan.id, plan.items[0].id, itemCommand(plan, `resolve-${index}`)).plan;
  }
  fails(() => f.store.markItemUncertain(OWNER, plan.id, plan.items[0].id, { ...itemCommand(plan, "over-capacity"), uncertaintyId: "one-too-many" }), "cleanup_uncertainty_conflict");
  assert.deepEqual(f.store.getPlan(OWNER, plan.id), plan);
  assert.equal(plan.items[0].uncertainties.length, 20);
});

test("S25: two instances enforce revision CAS and owner-scoped idempotency", t => {
  const f = fixture(t), other = f.open();
  const original = f.store.createPlan(OWNER, create()).plan;
  const first = other.revalidatePlan(OWNER, original.id, revision(original, "review-other"));
  fails(() => f.store.cancelPlan(OWNER, original.id, revision(original, "stale-cancel")), "cleanup_revision_conflict");
  assert.equal(f.store.revalidatePlan(OWNER, original.id, revision(original, "review-other")).replayed, true);
  assert.deepEqual(f.store.getPlan(OWNER, original.id), first.plan);
  const anotherOwner = other.createPlan("owner-b", create()).plan;
  assert.notEqual(anotherOwner.id, original.id);
  fails(() => other.markItemUncertain("owner-b", original.id, original.items[0].id, { ...itemCommand(original, "cross-owner"), uncertaintyId: "u" }), "cleanup_plan_not_found");
});

test("S25: expiry survives a rejected command, process reopen and clock rollback", t => {
  const f = fixture(t); const original = f.store.createPlan(OWNER, { ...create(), expiresInMs: 1_000 }).plan;
  f.state.time = BASE + 2_000;
  fails(() => f.store.cancelPlan(OWNER, original.id, { ...revision(original, "stale"), expectedRevision: 100 }), "cleanup_revision_conflict");
  f.store.close(); f.state.time = BASE;
  const reopened = f.open(), expired = reopened.getPlan(OWNER, original.id);
  assert.equal(expired.expired, true); assert.equal(expired.state, "expired");
  assert.equal(reopened.revalidatePlan(OWNER, original.id, revision(expired, "cannot-revive")).plan.state, "expired");
});

test("S25: a second instance's clock advance between tick and plan transaction fences stale time", t => {
  const f = fixture(t), other = f.open();
  const original = f.store.createPlan(OWNER, { ...create(), expiresInMs: 1_000 }).plan;
  const tick = Reflect.get(f.store, "tick") as () => number;
  let interleave = true;
  Reflect.set(f.store, "tick", function(this: CleanupPlans) {
    const at = tick.call(this);
    if (interleave) { interleave = false; f.state.time = BASE + 2_000; other.listPlans("unrelated-owner"); f.state.time = BASE; }
    return at;
  });
  const denied = f.store.confirmPlan(OWNER, original.id, confirm(original)).plan;
  assert.equal(denied.state, "expired"); assert.equal(denied.expired, true); assert.ok(denied.updatedAt >= BASE + 2_000);
});

test("S25: evidence expiring while a trusted callback runs cannot yield fresh review or confirmation", t => {
  const f = fixture(t);
  f.state.mutate = value => ({ ...value, validUntil: BASE + 100 });
  const original = f.store.createPlan(OWNER, create()).plan;
  f.state.mutate = value => { f.state.time = BASE + 101; return { ...value, validUntil: BASE + 100 }; };
  const denied = f.store.confirmPlan(OWNER, original.id, confirm(original)).plan;
  assert.equal(denied.state, "blocked"); assert.ok(denied.blockers.includes("evidence_expired"));
  f.state.time = BASE;
  f.state.mutate = value => { f.state.time = BASE + 5_000; return value; };
  const expired = f.store.createPlan(OWNER, { ...create("slow-create"), expiresInMs: 1_000 }).plan;
  assert.equal(expired.state, "expired"); assert.equal(expired.reviewFingerprint, null);
  assert.equal(f.store.getPlan(OWNER, expired.id).state, "expired");
});

test("S25: no-change evidence expiring during reconciliation retains uncertainty across clock rollback", t => {
  const f = fixture(t); let plan = f.store.createPlan(OWNER, create()).plan;
  plan = f.store.markItemUncertain(OWNER, plan.id, plan.items[0].id, { ...itemCommand(plan, "uncertain"), uncertaintyId: "uncertainty" }).plan;
  f.state.allowNoChange = true;
  f.state.mutateNoChange = proof => { f.state.time = proof.validUntil + 1; return proof; };
  fails(() => f.store.reconcileItem(OWNER, plan.id, plan.items[0].id, itemCommand(plan, "too-late")), "cleanup_reconciliation_unverified");
  f.store.close(); f.state.time = BASE;
  const current = f.open().getPlan(OWNER, plan.id);
  assert.equal(current.state, "uncertain"); assert.equal(current.expired, true);
  assert.equal(current.items[0].uncertainties[0].resolvedAt, null);
});

test("S25: failed expiry fencing on a read still persists sampled time before reopen", t => {
  const f = fixture(t); const plan = f.store.createPlan(OWNER, { ...create(), expiresInMs: 1_000 }).plan;
  const tick = Reflect.get(f.store, "tick") as () => number;
  Reflect.set(f.store, "tick", function(this: CleanupPlans) { const at = tick.call(this); f.state.time = BASE + 5_000; return at; });
  const restore = failOneWrite(f.store, "UPDATE cleanup_plans");
  assert.throws(() => f.store.getPlan(OWNER, plan.id), /injected transaction fault/);
  restore(); f.store.close(); f.state.time = BASE;
  const current = f.open().getPlan(OWNER, plan.id);
  assert.equal(current.state, "expired"); assert.ok(current.updatedAt >= BASE + 5_000);
});

test("S25: source/journal changes and idempotency roll back together after a late transaction fault", t => {
  const f = fixture(t); const original = f.store.createPlan(OWNER, create()).plan;
  const other = f.store.createPlan(OWNER, create("parallel")).plan;
  const restore = failOneWrite(f.store, "INSERT INTO cleanup_operations");
  const command = { ...itemCommand(original, "uncertain-fault"), uncertaintyId: "uncertainty" };
  assert.throws(() => f.store.markItemUncertain(OWNER, original.id, original.items[0].id, command), /injected transaction fault/);
  restore();
  assert.deepEqual(f.store.getPlan(OWNER, original.id), original); assert.deepEqual(f.store.getPlan(OWNER, other.id), other);
  const probe = new DatabaseSync(f.path);
  assert.equal((probe.prepare("SELECT COUNT(*) AS n FROM cleanup_operations WHERE idempotency_key = 'uncertain-fault'").get() as { n: number }).n, 0); probe.close();
  const retried = f.store.markItemUncertain(OWNER, original.id, original.items[0].id, command);
  assert.equal(retried.replayed, false); assert.equal(retried.plan.state, "uncertain");
});

test("S25: constructor validation uses a single read snapshot across concurrent valid commits", t => {
  const f = fixture(t); const original = f.store.createPlan(OWNER, create()).plan;
  const prepare = DatabaseSync.prototype.prepare;
  let interleave = true;
  DatabaseSync.prototype.prepare = function(sql: string) {
    if (interleave && sql === "SELECT * FROM cleanup_operations") {
      interleave = false;
      f.store.revalidatePlan(OWNER, original.id, revision(original, "concurrent-review"));
    }
    return prepare.call(this, sql);
  };
  try { const reader = f.open(); assert.equal(reader.getPlan(OWNER, original.id).revision, 2); }
  finally { DatabaseSync.prototype.prepare = prepare; }
  assert.equal(interleave, false);
});

test("S25: foreign, dropped and vacuumed databases are rejected without changing bytes or sidecars", t => {
  const directory = mkdtempSync(join(tmpdir(), "still-foreign-")); t.after(() => rmSync(directory, { recursive: true, force: true }));
  for (const [name, sql] of [
    ["foreign", "CREATE TABLE foreign_data(value TEXT); INSERT INTO foreign_data VALUES ('retain');"],
    ["dropped", "CREATE TABLE prior_data(value TEXT); DROP TABLE prior_data;"],
    ["vacuumed", "CREATE TABLE prior_data(value TEXT); DROP TABLE prior_data; VACUUM;"],
    ["identity-only", "PRAGMA application_id = 1398032945; PRAGMA user_version = 1;"],
  ]) {
    const path = join(directory, `${name}.sqlite`), db = new DatabaseSync(path); db.exec(sql); db.close();
    const before = readFileSync(path), names = readdirSync(directory).sort();
    assert.throws(() => new CleanupPlans(path));
    assert.deepEqual(readFileSync(path), before, name); assert.deepEqual(readdirSync(directory).sort(), names, name);
  }
  const corrupt = join(directory, "corrupt.sqlite"); writeFileSync(corrupt, "not a SQLite database");
  const bytes = readFileSync(corrupt); assert.throws(() => new CleanupPlans(corrupt)); assert.deepEqual(readFileSync(corrupt), bytes);
});

test("S25: cleanup/control-plane reverse path mixups preserve existing databases", t => {
  const f = fixture(t); f.store.close();
  let probe = new DatabaseSync(f.path); probe.exec("PRAGMA journal_mode = DELETE"); probe.close();
  const journalBefore = readFileSync(f.path);
  assert.throws(() => new ControlPlane(f.path), /different application/);
  assert.deepEqual(readFileSync(f.path), journalBefore);
  const path = join(f.directory, "control.sqlite"), control = new ControlPlane(path); control.close();
  probe = new DatabaseSync(path); probe.exec("PRAGMA journal_mode = DELETE"); probe.close();
  const controlBefore = readFileSync(path), names = readdirSync(f.directory).sort();
  assert.throws(() => new CleanupPlans(path));
  assert.deepEqual(readFileSync(path), controlBefore); assert.deepEqual(readdirSync(f.directory).sort(), names);
  const original = new ControlPlane(path); assert.equal(original.capabilities().localGallery.requiresAccount, false); original.close();
});

test("S25: unsupported schema objects/versions and malformed durable semantics fail before writes", t => {
  const cases: [string, (db: DatabaseSync, plan: CleanupPlanRecord) => void][] = [
    ["trigger", db => db.exec("CREATE TRIGGER foreign_trigger BEFORE INSERT ON cleanup_plans BEGIN SELECT 1; END")],
    ["view", db => db.exec("CREATE VIEW foreign_view AS SELECT 1 AS value")],
    ["newer", db => db.exec("PRAGMA user_version = 2")],
    ["columns", db => db.exec("ALTER TABLE cleanup_plans ADD COLUMN unsupported TEXT")],
    ["fingerprint", (_db, plan) => mutateCurrent(f.path, plan.id, row => { row.reviewFingerprint = "a".repeat(64); })],
    ["row-owner", (_db, plan) => mutateCurrent(f.path, plan.id, row => { row.ownerId = "other-owner"; })],
    ["item-revision", (_db, plan) => mutateCurrent(f.path, plan.id, row => { row.items[0].revision++; })],
  ];
  // Each case owns its own real SQLite database; no repair or destructive reset.
  let f: ReturnType<typeof fixture>;
  for (const [name, mutate] of cases) {
    f = fixture(t); const plan = f.store.createPlan(OWNER, create()).plan; f.store.close();
    const db = new DatabaseSync(f.path); mutate(db, plan); db.exec("PRAGMA journal_mode = DELETE"); db.close();
    const before = readFileSync(f.path), names = readdirSync(f.directory).sort();
    assert.throws(() => f.open(), name);
    assert.deepEqual(readFileSync(f.path), before, name); assert.deepEqual(readdirSync(f.directory).sort(), names, name);
  }
});

test("S25: current-row cancellation/history rollback and missing historical operations cannot reopen", t => {
  for (const scenario of ["same-revision-cancel", "older-cancel", "drop-uncertainty", "drop-resolved-history", "drop-operation"]) {
    const f = fixture(t); let plan = f.store.createPlan(OWNER, create()).plan;
    if (scenario.includes("cancel")) {
      plan = f.store.cancelPlan(OWNER, plan.id, revision(plan, "cancel")).plan;
      if (scenario === "older-cancel") plan = f.store.revalidatePlan(OWNER, plan.id, revision(plan, "after-cancel")).plan;
    } else if (scenario !== "drop-operation") {
      plan = f.store.markItemUncertain(OWNER, plan.id, plan.items[0].id, { ...itemCommand(plan, "uncertain"), uncertaintyId: "uncertainty" }).plan;
      if (scenario === "drop-resolved-history") { f.state.allowNoChange = true; plan = f.store.reconcileItem(OWNER, plan.id, plan.items[0].id, itemCommand(plan, "reconciled")).plan; }
    }
    f.store.close();
    if (scenario === "drop-operation") {
      const db = new DatabaseSync(f.path); db.exec("DELETE FROM cleanup_operations"); db.close();
    } else mutateCurrent(f.path, plan.id, row => {
      row.cancelled = false; row.state = "blocked"; row.evidence = null; row.reviewFingerprint = null; row.blockers = ["evidence_unavailable"];
      for (const item of row.items) { item.state = "blocked"; item.blockers = ["evidence_unavailable"]; item.uncertainties = []; }
    });
    const before = readFileSync(f.path);
    assert.throws(() => f.open(), scenario); assert.deepEqual(readFileSync(f.path), before, scenario);
  }
});

test("S25: operation result substitution cannot replay a different same-owner selection", t => {
  const f = fixture(t);
  f.store.createPlan(OWNER, create("create-a"));
  f.store.createPlan(OWNER, create("create-b", [ref("target-b")], [ref("keeper-b")]));
  f.store.close();
  const db = new DatabaseSync(f.path);
  db.exec("UPDATE cleanup_operations SET result_json = (SELECT result_json FROM cleanup_operations WHERE idempotency_key = 'create-b') WHERE idempotency_key = 'create-a'"); db.close();
  const before = readFileSync(f.path); assert.throws(() => f.open()); assert.deepEqual(readFileSync(f.path), before);
});

test("S25: a current confirmation without its exact retained confirmation operation is rejected", t => {
  const f = fixture(t); const plan = f.store.createPlan(OWNER, create()).plan; f.store.close();
  mutateCurrent(f.path, plan.id, current => {
    current.revision++; current.state = "confirmed_nonexecuting";
    for (const item of current.items) { item.revision++; item.state = "confirmed_nonexecuting"; }
  });
  const before = readFileSync(f.path); assert.throws(() => f.open()); assert.deepEqual(readFileSync(f.path), before);
});

test("S25: exhausted revisions reject mutation and retain the prior record", t => {
  const f = fixture(t); const original = f.store.createPlan(OWNER, create()).plan; f.store.close();
  mutateCurrent(f.path, original.id, plan => { plan.revision = Number.MAX_SAFE_INTEGER; plan.items.forEach(item => { item.revision = plan.revision; }); });
  const reopened = f.open(), current = reopened.getPlan(OWNER, original.id);
  fails(() => reopened.revalidatePlan(OWNER, current.id, revision(current, "exhausted")), "cleanup_revision_exhausted");
  assert.deepEqual(reopened.getPlan(OWNER, current.id), current);
});
