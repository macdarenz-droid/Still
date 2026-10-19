import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import type { TestContext } from "node:test";
import { ControlPlaneError } from "../../packages/contracts/src/index.ts";
import { ControlPlane } from "../../services/workflows/src/store.ts";
import type { StoreOptions } from "../../services/workflows/src/store.ts";

const OWNER = "owner-a";
const providers: NonNullable<StoreOptions["providers"]> = [
  { kind: "document_conversion", providerId: "test-provider", estimateMinor: 20 },
  { kind: "backup", providerId: "test-provider", estimateMinor: 10 },
];
function consent(key = "consent-1", expectedRevision = 0, granted = true) {
  return { schemaVersion: 1, idempotencyKey: key, expectedRevision, granted, assetIds: ["asset-a", "asset-b"], purposes: ["document_conversion", "backup"], providerIds: ["test-provider"] };
}
function job(key = "job-1") {
  return { schemaVersion: 1, idempotencyKey: key, kind: "document_conversion", inputs: [{ assetId: "asset-a", versionId: "version-a" }], consentRevision: 1, policyRevision: 0, budget: { currency: "USD", maxMinor: 50 }, parameters: { format: "pdf" } };
}
function backupPolicy(key: string, expectedRevision: number, enabled: boolean) {
  return { schemaVersion: 1, idempotencyKey: key, expectedRevision, enabled, includedCollectionIds: ["collection-a"], includeExisting: false, includeVideos: false, network: "wifi", destinationId: "destination-a" };
}
function backup(key: string, origin: "manual" | "automatic") {
  return { ...job(key), kind: "backup", policyRevision: 1, parameters: { origin, destinationId: "destination-a" } };
}
function storeFor(t: TestContext, options: StoreOptions = {}): ControlPlane {
  const store = new ControlPlane(":memory:", {
    providers, verifyInputs: () => true,
    automaticBackupEligibility: () => ({ includedCollections: true, mediaTypes: true, network: true }), ...options,
  });
  t.after(() => store.close());
  return store;
}
function fails(action: () => unknown, code: string): void {
  assert.throws(action, error => error instanceof ControlPlaneError && error.detail.code === code);
}

test("S01: fresh state has no consent, automatic backup, cloud catalogue or available provider", t => {
  const store = storeFor(t, { providers: [] });
  assert.equal(store.getConsent(OWNER).granted, false);
  assert.equal(store.getBackupPolicy(OWNER).enabled, false);
  assert.equal(store.capabilities().cloudCatalogueEnabled, false);
  assert.ok(store.capabilities().providers.every(p => !p.available && p.reason === "provider_unconfigured"));
  fails(() => store.createJob(OWNER, job()), "provider_unconfigured");
  assert.deepEqual(store.listJobs(OWNER), []);
  assert.deepEqual(store.readEvents(OWNER), []);
});

test("S01: job admission validates consent scope, immutable versions and trusted estimate", t => {
  let accessible = true;
  const store = storeFor(t, { verifyInputs: () => accessible });
  fails(() => store.createJob(OWNER, job()), "consent_required");
  store.setConsent(OWNER, consent());
  fails(() => store.createJob(OWNER, { ...job(), inputs: [{ assetId: "asset-unapproved", versionId: "version-a" }] }), "consent_required");
  fails(() => store.createJob(OWNER, { ...job(), consentRevision: 2 }), "consent_required");
  fails(() => store.createJob(OWNER, { ...job(), budget: { currency: "USD", maxMinor: 19 } }), "budget_exceeded");
  accessible = false;
  fails(() => store.createJob(OWNER, job()), "input_unavailable");
  accessible = true;
  const created = store.createJob(OWNER, job());
  assert.equal(created.state, "queued");
  assert.equal(created.budget.reservedMinor, 20);
  assert.equal(created.budget.incurredMinor, 0);
  assert.equal(store.readEvents(OWNER).length, 2);
});

test("S01: registered providers cannot admit inputs without a source verifier", t => {
  const store = new ControlPlane(":memory:", { providers });
  t.after(() => store.close());
  store.setConsent(OWNER, consent());
  fails(() => store.createJob(OWNER, job()), "input_unavailable");
});

test("S01: consent purpose and provider are independently enforced", t => {
  const store = storeFor(t);
  store.setConsent(OWNER, { ...consent(), providerIds: ["different-provider"] });
  fails(() => store.createJob(OWNER, job()), "consent_required");
  store.setConsent(OWNER, { ...consent("change-purpose", 1), purposes: ["backup"] });
  fails(() => store.createJob(OWNER, { ...job(), consentRevision: 2 }), "consent_required");
});

test("S01: duplicate commands and reordered JSON keys emit one state transition", t => {
  const store = storeFor(t);
  const grant = consent();
  assert.deepEqual(store.setConsent(OWNER, grant), store.setConsent(OWNER, Object.fromEntries(Object.entries(grant).reverse())));
  const first = store.createJob(OWNER, job());
  assert.deepEqual(store.createJob(OWNER, job()), first);
  assert.equal(store.listJobs(OWNER).length, 1);
  assert.equal(store.readEvents(OWNER).length, 2);
  fails(() => store.createJob(OWNER, { ...job(), budget: { currency: "USD", maxMinor: 60 } }), "idempotency_conflict");
  fails(() => store.setConsent(OWNER, { ...consent("different-operation"), expectedRevision: 0 }), "revision_conflict");
  assert.equal(store.readEvents(OWNER).length, 2);
});

test("S01: owner partition covers reads, idempotency, consent and events", t => {
  const store = storeFor(t);
  store.setConsent(OWNER, consent());
  store.setConsent("owner-b", consent());
  const first = store.createJob(OWNER, job());
  const second = store.createJob("owner-b", job());
  assert.notEqual(first.id, second.id);
  fails(() => store.getJob("owner-b", first.id), "job_not_found");
  fails(() => store.cancelJob("owner-b", first.id, { schemaVersion: 1, idempotencyKey: "cancel-other", expectedRevision: 1 }), "job_not_found");
  assert.equal(store.listJobs("owner-b")[0].id, second.id);
  assert.ok(store.readEvents(OWNER).every(({ event }) => event.ownerId === OWNER));
  assert.equal(store.getConsent("unknown-owner").revision, 0);
});

test("S01: queued cancellation releases reservation exactly once and checks revisions", t => {
  const store = storeFor(t);
  store.setConsent(OWNER, consent());
  const created = store.createJob(OWNER, job());
  const command = { schemaVersion: 1, idempotencyKey: "cancel-1", expectedRevision: 1 };
  fails(() => store.cancelJob(OWNER, created.id, { ...command, expectedRevision: 2 }), "revision_conflict");
  const stopped = store.cancelJob(OWNER, created.id, command);
  assert.equal(stopped.state, "cancelled");
  assert.equal(stopped.budget.reservedMinor, 0);
  assert.deepEqual(store.cancelJob(OWNER, created.id, command), stopped);
  assert.equal(store.claimNext("worker-a"), null);
});

test("S01: current input access is rechecked after enqueue and before transmission", t => {
  let accessible = true;
  const store = storeFor(t, { verifyInputs: () => accessible });
  store.setConsent(OWNER, consent());
  const created = store.createJob(OWNER, job());
  accessible = false;
  assert.equal(store.claimNext("worker-a"), null);
  assert.equal(store.getJob(OWNER, created.id).state, "cancelled");
  accessible = true;
  const other = store.createJob(OWNER, job("job-2"));
  const lease = store.claimNext("worker-a");
  assert.equal(lease?.job.id, other.id);
  assert.ok(lease);
  assert.equal(lease.ownerId, OWNER);
  fails(() => store.assertCanTransmit("owner-b", created.id, lease.token), "lease_invalid");
  accessible = false;
  fails(() => store.assertCanTransmit(OWNER, other.id, lease.token), "transmission_forbidden");
});

test("S01: consent revocation atomically stops queued work and fences in-flight output", t => {
  const store = storeFor(t);
  store.setConsent(OWNER, consent());
  const running = store.createJob(OWNER, job());
  const queued = store.createJob(OWNER, job("job-2"));
  const lease = store.claimNext("worker-a");
  assert.ok(lease);
  assert.equal(store.assertCanTransmit(OWNER, running.id, lease.token).state, "running");
  store.setConsent(OWNER, consent("revoke", 1, false));
  assert.equal(store.getJob(OWNER, running.id).state, "cancelling");
  assert.equal(store.getJob(OWNER, queued.id).state, "cancelled");
  assert.equal(store.getJob(OWNER, queued.id).budget.reservedMinor, 0);
  fails(() => store.assertCanTransmit(OWNER, running.id, lease.token), "transmission_forbidden");
  const result = store.finishStopped(OWNER, running.id, lease.token, 12);
  assert.equal(result.state, "cancelled");
  assert.equal(result.budget.incurredMinor, 12);
  assert.equal(result.budget.reservedMinor, 0);
  fails(() => store.finishStopped(OWNER, running.id, lease.token, 12), "lease_invalid");
  assert.equal(store.getJob(OWNER, running.id).state, "cancelled");
});

test("S01: disabling automatic backup stops automatic jobs and preserves manual work", t => {
  const store = storeFor(t);
  store.setConsent(OWNER, consent());
  store.setBackupPolicy(OWNER, backupPolicy("enable", 0, true));
  const manual = store.createJob(OWNER, backup("manual", "manual"));
  const automatic = store.createJob(OWNER, backup("auto", "automatic"));
  store.setBackupPolicy(OWNER, backupPolicy("disable", 1, false));
  assert.equal(store.getJob(OWNER, automatic.id).state, "cancelled");
  assert.equal(store.getJob(OWNER, manual.id).state, "queued");
  fails(() => store.createJob(OWNER, backup("after-disable", "automatic")), "backup_policy_changed");
});

test("S01: worker leases exclude a second worker and fence expired attempts without blind retry", t => {
  let time = 1_000;
  const store = storeFor(t, { now: () => time });
  store.setConsent(OWNER, consent());
  const created = store.createJob(OWNER, job());
  const lease = store.claimNext("worker-a", 100);
  assert.ok(lease);
  assert.equal(store.claimNext("worker-b"), null);
  fails(() => store.assertCanTransmit(OWNER, created.id, "wrong-token"), "lease_invalid");
  time += 101;
  fails(() => store.assertCanTransmit(OWNER, created.id, lease.token), "lease_invalid");
  assert.equal(store.claimNext("worker-b"), null);
  const failed = store.getJob(OWNER, created.id);
  assert.equal(failed.state, "failed");
  assert.equal(failed.error?.code, "reconciliation_required");
  assert.equal(failed.attempt, 1);
  assert.equal(failed.budget.reservedMinor, 20);
  fails(() => store.finishStopped(OWNER, created.id, lease.token, 0), "lease_invalid");
});

test("S01: cancellation during a lease blocks new stages and retains real incurred costs", t => {
  const store = storeFor(t);
  store.setConsent(OWNER, consent());
  const created = store.createJob(OWNER, job());
  const lease = store.claimNext("worker-a");
  assert.ok(lease);
  store.cancelJob(OWNER, created.id, { schemaVersion: 1, idempotencyKey: "cancel-running", expectedRevision: lease.job.revision });
  fails(() => store.assertCanTransmit(OWNER, created.id, lease.token), "transmission_forbidden");
  fails(() => store.finishStopped(OWNER, created.id, lease.token, -1), "invalid_usage");
  const final = store.finishStopped(OWNER, created.id, lease.token, 20);
  assert.equal(final.state, "cancelled");
  assert.equal(final.budget.incurredMinor, 20);
});

test("S01: provider overspend is recorded truthfully and prevents further stages", t => {
  const store = storeFor(t);
  store.setConsent(OWNER, consent());
  const created = store.createJob(OWNER, job());
  const lease = store.claimNext("worker-a");
  assert.ok(lease);
  const final = store.finishStopped(OWNER, created.id, lease.token, 51);
  assert.equal(final.budget.incurredMinor, 51);
  assert.equal(final.error?.code, "budget_overrun");
  fails(() => store.assertCanTransmit(OWNER, created.id, lease.token), "lease_invalid");
});

test("S01: automatic backup requires collection, video and current network proof", t => {
  let eligibility = { includedCollections: true, mediaTypes: true, network: true };
  const store = storeFor(t, { automaticBackupEligibility: (_owner, _inputs, policy) => {
    assert.deepEqual(policy.includedCollectionIds, ["collection-a"]);
    assert.equal(policy.includeVideos, false);
    assert.equal(policy.network, "wifi");
    return eligibility;
  } });
  store.setConsent(OWNER, consent());
  store.setBackupPolicy(OWNER, backupPolicy("enable", 0, true));
  for (const field of ["includedCollections", "mediaTypes", "network"] as const) {
    eligibility = { includedCollections: true, mediaTypes: true, network: true, [field]: false };
    fails(() => store.createJob(OWNER, backup(`reject-${field}`, "automatic")), "backup_scope_unverified");
  }
  eligibility = { includedCollections: true, mediaTypes: true, network: true };
  const queued = store.createJob(OWNER, backup("eligible-at-admission", "automatic"));
  eligibility.network = false;
  assert.equal(store.claimNext("worker-a"), null);
  assert.equal(store.getJob(OWNER, queued.id).state, "cancelled");
  eligibility.network = true;
  const running = store.createJob(OWNER, backup("eligible-at-dispatch", "automatic"));
  const lease = store.claimNext("worker-a");
  assert.ok(lease);
  eligibility.mediaTypes = false;
  fails(() => store.assertCanTransmit(OWNER, running.id, lease.token), "transmission_forbidden");
});

test("S01: automatic scope resolver defaults to denial even with a verified source", t => {
  const store = new ControlPlane(":memory:", { providers, verifyInputs: () => true });
  t.after(() => store.close());
  store.setConsent(OWNER, consent());
  store.setBackupPolicy(OWNER, backupPolicy("enable", 0, true));
  fails(() => store.createJob(OWNER, backup("missing-policy-resolver", "automatic")), "backup_scope_unverified");
});

test("S01: expired attempts reconcile after reopen with owner, revision, attempt and duplicate fencing", () => {
  const directory = mkdtempSync(join(tmpdir(), "still-receipt-"));
  const path = join(directory, "state.sqlite");
  let time = 1_000;
  const options: StoreOptions = { providers, verifyInputs: () => true, now: () => time };
  const initial = new ControlPlane(path, options);
  initial.setConsent(OWNER, consent());
  const created = initial.createJob(OWNER, job());
  const lease = initial.claimNext("worker-a", 100);
  assert.ok(lease);
  initial.cancelJob(OWNER, created.id, { schemaVersion: 1, idempotencyKey: "cancel-before-crash", expectedRevision: lease.job.revision });
  initial.close();
  time += 101;
  const reopened = new ControlPlane(path, options);
  try {
    assert.equal(reopened.claimNext("worker-b"), null);
    const expired = reopened.getJob(OWNER, created.id);
    assert.equal(expired.error?.code, "reconciliation_required");
    const command = { schemaVersion: 1, idempotencyKey: "receipt-operation-1", expectedRevision: expired.revision, expectedAttempt: expired.attempt, incurredMinor: 12, receiptId: "provider-receipt-1" };
    fails(() => reopened.reconcileExpiredAttempt("owner-b", created.id, command), "job_not_found");
    fails(() => reopened.reconcileExpiredAttempt(OWNER, created.id, { ...command, expectedAttempt: 2 }), "revision_conflict");
    fails(() => reopened.reconcileExpiredAttempt(OWNER, created.id, { ...command, expectedRevision: expired.revision - 1 }), "revision_conflict");
    const reconciled = reopened.reconcileExpiredAttempt(OWNER, created.id, command);
    assert.equal(reconciled.state, "cancelled");
    assert.equal(reconciled.budget.incurredMinor, 12);
    assert.equal(reconciled.budget.reservedMinor, 0);
    assert.deepEqual(reopened.reconcileExpiredAttempt(OWNER, created.id, command), reconciled);
    fails(() => reopened.reconcileExpiredAttempt(OWNER, created.id, { ...command, incurredMinor: 13 }), "idempotency_conflict");
    fails(() => reopened.reconcileExpiredAttempt(OWNER, created.id, { ...command, idempotencyKey: "receipt-operation-2", expectedRevision: reconciled.revision }), "attempt_not_reconcilable");
    fails(() => reopened.finishStopped(OWNER, created.id, lease.token, 12), "lease_invalid");
  } finally { reopened.close(); rmSync(directory, { recursive: true, force: true }); }
});

test("S01: durable database reopen preserves records, idempotency and ordered outbox", () => {
  const directory = mkdtempSync(join(tmpdir(), "still-reopen-"));
  const path = join(directory, "state.sqlite");
  const options: StoreOptions = { providers, verifyInputs: () => true };
  const initial = new ControlPlane(path, options);
  initial.setConsent(OWNER, consent());
  const created = initial.createJob(OWNER, job());
  const events = initial.readEvents(OWNER);
  initial.close();
  const reopened = new ControlPlane(path, options);
  try {
    assert.deepEqual(reopened.getJob(OWNER, created.id), created);
    assert.deepEqual(reopened.createJob(OWNER, job()), created);
    assert.equal(reopened.listJobs(OWNER).length, 1);
    assert.deepEqual(reopened.readEvents(OWNER), events);
    assert.equal(reopened.readEvents(OWNER, events[0].offset).length, 1);
    assert.equal(new Set(events.map(item => item.event.eventId)).size, 2);
  } finally { reopened.close(); rmSync(directory, { recursive: true, force: true }); }
});

test("S01: a failed outbox write rolls back state and idempotency together", () => {
  const directory = mkdtempSync(join(tmpdir(), "still-atomic-"));
  const path = join(directory, "state.sqlite");
  const store = new ControlPlane(path);
  const probe = new DatabaseSync(path);
  try {
    probe.exec("CREATE TRIGGER reject_event BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT, 'injected storage fault'); END;");
    assert.throws(() => store.setConsent(OWNER, consent()), /injected storage fault/);
    assert.equal(store.getConsent(OWNER).revision, 0);
    assert.equal(store.readEvents(OWNER).length, 0);
    assert.equal((probe.prepare("SELECT COUNT(*) AS count FROM operations").get() as { count: number }).count, 0);
    probe.exec("DROP TRIGGER reject_event");
    assert.equal(store.setConsent(OWNER, consent()).revision, 1);
  } finally { probe.close(); store.close(); rmSync(directory, { recursive: true, force: true }); }
});
