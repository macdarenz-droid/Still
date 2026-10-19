import assert from "node:assert/strict";
import test from "node:test";
import { ControlPlaneError } from "../../packages/contracts/src/index.ts";
import {
  cleanupRef, parseCleanupEvidence, parseCleanupItemCommand, parseCleanupNoChange,
  parseCleanupRevision, parseConfirmCleanup, parseCreateCleanupPlan,
} from "../../packages/contracts/src/cleanup.ts";
import type {
  CleanupEvidence, CleanupNoChangeEvidence, CleanupSourceRef, CreateCleanupPlan,
} from "../../packages/contracts/src/cleanup.ts";

function ref(sourceId = "source-target"): CleanupSourceRef {
  return { assetId: "asset-a", versionId: "version-a-1", sourceId, expectedSourceRevision: 3 };
}
function create(): CreateCleanupPlan {
  return { schemaVersion: 1, idempotencyKey: "review-request-1", intent: "trash_specified_sources",
    targets: [ref()], keepers: [ref("source-keeper")], expiresInMs: 60_000 };
}
function revision() { return { schemaVersion: 1, idempotencyKey: "review-command-1", expectedRevision: 4 }; }
function confirmation() { return { ...revision(), expectedReviewFingerprint: "a1".repeat(32) }; }
function item() { return { ...revision(), expectedItemRevision: 2 }; }
function evidence(): CleanupEvidence {
  return { schemaVersion: 1, ownerId: "owner-a", evidenceRevision: 2,
    verifiedAt: 1_800_000_000_000, validUntil: 1_800_000_060_000,
    protectionRevision: 3, favoriteRevision: 4, dependencyRevision: 5, copyRevision: 6,
    sources: [ref(), ref("source-keeper")].map(source => ({ ref: source,
      physicalSourceId: `physical-${source.sourceId}`, pinProofId: "pin-proof",
      representation: "original_bytes", access: "granted", protected: false,
      favorite: false, requiredDependency: false })),
    copies: [{ targetSourceId: "source-target", keeperSourceId: "source-keeper", versionId: "version-a-1",
      identityProofId: "identity-proof", independenceProofId: "independence-proof", restoreProofId: "restore-proof" }] };
}
function noChange(): CleanupNoChangeEvidence {
  return { schemaVersion: 1, ownerId: "owner-a", ref: ref(), uncertaintyId: "uncertainty-1",
    result: "verified_no_change", proofId: "no-change-proof", verifiedAt: 1_800_000_000_000,
    validUntil: 1_800_000_060_000 };
}
function without(value: object, key: string): Record<string, unknown> {
  const copy: Record<string, unknown> = { ...value }; delete copy[key]; return copy;
}
function rejects(action: () => unknown, code = "invalid_cleanup_request") {
  assert.throws(action, error => {
    assert.ok(error instanceof ControlPlaneError);
    assert.equal(error.status, 400);
    assert.equal(error.detail.code, code);
    assert.equal(error.detail.retryable, false);
    assert.equal(error.detail.scope, "request");
    assert.ok(error.detail.message.length > 0);
    assert.ok(error.detail.recoveryAction.length > 0);
    return true;
  });
}
const INVALID_INTEGERS: unknown[] = [undefined, null, true, "1", -1, 0.5, NaN, Infinity, -Infinity, Number.MAX_SAFE_INTEGER + 1];

test("create preserves exact selected sources and copies nested request data", () => {
  const input = create();
  const parsed = parseCreateCleanupPlan(input);
  assert.deepEqual(parsed, input);
  assert.notEqual(parsed.targets, input.targets);
  assert.notEqual(parsed.targets[0], input.targets[0]);
  assert.notEqual(parsed.keepers[0], input.keepers[0]);
  input.targets[0]!.sourceId = "changed-after-parse";
  input.keepers.length = 0;
  assert.equal(parsed.targets[0]!.sourceId, "source-target");
  assert.equal(parsed.keepers[0]!.sourceId, "source-keeper");
});

test("create rejects absent fields, nonobjects and caller-injected ownership or authority", () => {
  for (const value of [null, undefined, false, "request", [], ...Object.keys(create()).map(key => without(create(), key))]) {
    rejects(() => parseCreateCleanupPlan(value));
  }
  for (const [key, value] of Object.entries({ ownerId: "owner-b", evidence: evidence(), trustedEvidence: evidence(),
    executionAvailable: true, state: "confirmed_nonexecuting", providerUri: "content://media/1",
    credentials: { token: "never-store-this" }, restoreWindowMs: 60_000 })) {
    rejects(() => parseCreateCleanupPlan({ ...create(), [key]: value }));
  }
});

test("commands require schema one and the sole nonexecuting source-scoped intent", () => {
  for (const schemaVersion of [0, 2, "1", null]) {
    rejects(() => parseCreateCleanupPlan({ ...create(), schemaVersion }), "unsupported_cleanup_schema");
    rejects(() => parseCleanupRevision({ ...revision(), schemaVersion }), "unsupported_cleanup_schema");
    rejects(() => parseConfirmCleanup({ ...confirmation(), schemaVersion }), "unsupported_cleanup_schema");
    rejects(() => parseCleanupItemCommand({ ...item(), schemaVersion }), "unsupported_cleanup_schema");
  }
  for (const intent of ["delete_everywhere", "phone_only", "trash", "restore", "TRASH_SPECIFIED_SOURCES", null]) {
    rejects(() => parseCreateCleanupPlan({ ...create(), intent }));
  }
});

test("source references reject filesystem, provider and signed URL handles in every opaque ID", () => {
  const invalid = ["", "../private", "/sdcard/DCIM/photo.jpg", "file:///private/photo.jpg",
    "content://media/external/images/media/1", "https://example.test/photo?token=secret", "a/b", "a\\b",
    "a.b", "a:b", "a b", " a", "a\n", "é", "_leading", "-leading", "a".repeat(129)];
  for (const key of ["assetId", "versionId", "sourceId"] as const) {
    for (const value of invalid) rejects(() => cleanupRef({ ...ref(), [key]: value }));
    assert.equal(cleanupRef({ ...ref(), [key]: "a".repeat(128) })[key], "a".repeat(128));
  }
  for (const value of invalid) rejects(() => parseCreateCleanupPlan({ ...create(), idempotencyKey: value }));
  for (const key of Object.keys(ref())) rejects(() => cleanupRef(without(ref(), key)));
  rejects(() => cleanupRef({ ...ref(), uri: "content://media/1" }));
});

test("source and command revisions must be positive safe integer observations", () => {
  for (const value of [...INVALID_INTEGERS, 0]) {
    rejects(() => cleanupRef({ ...ref(), expectedSourceRevision: value }));
    rejects(() => parseCleanupRevision({ ...revision(), expectedRevision: value }));
    rejects(() => parseConfirmCleanup({ ...confirmation(), expectedRevision: value }));
    rejects(() => parseCleanupItemCommand({ ...item(), expectedItemRevision: value }));
  }
  assert.equal(cleanupRef({ ...ref(), expectedSourceRevision: Number.MAX_SAFE_INTEGER }).expectedSourceRevision, Number.MAX_SAFE_INTEGER);
  assert.equal(parseCleanupRevision({ ...revision(), expectedRevision: Number.MAX_SAFE_INTEGER }).expectedRevision, Number.MAX_SAFE_INTEGER);
});

test("create bounds the review lifetime and never accepts a caller expiry timestamp", () => {
  for (const expiresInMs of [...INVALID_INTEGERS, 0, 999, 900_001]) {
    rejects(() => parseCreateCleanupPlan({ ...create(), expiresInMs }));
  }
  for (const expiresInMs of [1_000, 900_000]) {
    assert.equal(parseCreateCleanupPlan({ ...create(), expiresInMs }).expiresInMs, expiresInMs);
  }
  rejects(() => parseCreateCleanupPlan({ ...create(), expiresAt: 1_800_000_000_000 }));
});

test("target and keeper lists have independent finite bounds", () => {
  const sources = (prefix: string, count: number) => Array.from({ length: count }, (_, i) => ref(`${prefix}-${i}`));
  assert.equal(parseCreateCleanupPlan({ ...create(), targets: sources("target", 50), keepers: sources("keeper", 50) }).targets.length, 50);
  assert.deepEqual(parseCreateCleanupPlan({ ...create(), keepers: [] }).keepers, []);
  for (const targets of [[], null, {}, "source-target", sources("target", 51)]) rejects(() => parseCreateCleanupPlan({ ...create(), targets }));
  for (const keepers of [null, {}, "source-keeper", sources("keeper", 51)]) rejects(() => parseCreateCleanupPlan({ ...create(), keepers }));
});

test("selection rejects duplicated source scope including target/keeper overlap", () => {
  for (const selection of [
    { targets: [ref(), ref()], keepers: [] },
    { targets: [ref()], keepers: [ref()] },
    { targets: [ref()], keepers: [ref("keeper"), ref("keeper")] },
    { targets: [ref(), { ...ref(), expectedSourceRevision: 4 }], keepers: [] },
    { targets: [ref()], keepers: [{ ...ref(), assetId: "asset-b", versionId: "version-b" }] },
  ]) rejects(() => parseCreateCleanupPlan({ ...create(), ...selection }));
});

test("a version cannot belong to conflicting assets while distinct copies remain expressible", () => {
  for (const selection of [
    { targets: [ref(), { ...ref("second"), assetId: "asset-b" }], keepers: [] },
    { targets: [ref()], keepers: [{ ...ref("keeper"), assetId: "asset-b" }] },
  ]) rejects(() => parseCreateCleanupPlan({ ...create(), ...selection }));
  const parsed = parseCreateCleanupPlan(create());
  assert.equal(parsed.targets[0]!.versionId, parsed.keepers[0]!.versionId);
  assert.notEqual(parsed.targets[0]!.sourceId, parsed.keepers[0]!.sourceId);
});

test("confirmation binds an exact revision to a canonical review fingerprint", () => {
  assert.deepEqual(parseConfirmCleanup(confirmation()), confirmation());
  for (const expectedReviewFingerprint of [null, true, "", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), ` ${"a".repeat(64)}`]) {
    rejects(() => parseConfirmCleanup({ ...confirmation(), expectedReviewFingerprint }));
  }
  rejects(() => parseConfirmCleanup(without(confirmation(), "expectedReviewFingerprint")));
  for (const field of ["ownerId", "targets", "keepers", "evidence", "executionAvailable", "outcome"]) {
    rejects(() => parseConfirmCleanup({ ...confirmation(), [field]: "caller-supplied" }));
  }
});

test("revision and item commands cannot carry results, proofs or replacement selections", () => {
  assert.deepEqual(parseCleanupRevision(revision()), revision());
  assert.deepEqual(parseCleanupItemCommand(item()), item());
  for (const key of Object.keys(revision())) rejects(() => parseCleanupRevision(without(revision(), key)));
  for (const key of Object.keys(item())) rejects(() => parseCleanupItemCommand(without(item(), key)));
  for (const field of ["ownerId", "ref", "targets", "result", "evidence", "proofId", "deleted", "executionAvailable"]) {
    rejects(() => parseCleanupRevision({ ...revision(), [field]: true }));
    rejects(() => parseCleanupItemCommand({ ...item(), [field]: true }));
  }
});

test("uncertainty registration requires its own opaque ID without accepting a claimed outcome", () => {
  const uncertain = { ...item(), uncertaintyId: "uncertainty-1" };
  assert.deepEqual(parseCleanupItemCommand(uncertain, true), uncertain);
  rejects(() => parseCleanupItemCommand(item(), true));
  rejects(() => parseCleanupItemCommand(uncertain));
  for (const uncertaintyId of [null, "../entry", "https://example.test/proof", ""]) {
    rejects(() => parseCleanupItemCommand({ ...uncertain, uncertaintyId }, true));
  }
  for (const field of ["outcome", "result", "proofId", "noChangeProof", "resolvedAt"]) {
    rejects(() => parseCleanupItemCommand({ ...uncertain, [field]: "verified_no_change" }, true));
  }
});

test("trusted evidence keeps unknown facts explicit and detaches nested proof data", () => {
  const input = evidence();
  input.sources[0] = { ...input.sources[0]!, pinProofId: null, representation: "unknown", access: "unknown",
    protected: null, favorite: null, requiredDependency: null };
  const parsed = parseCleanupEvidence(input);
  assert.deepEqual(parsed, input);
  assert.equal(parsed.sources[0]!.protected, null);
  assert.equal(parsed.sources[0]!.favorite, null);
  assert.equal(parsed.sources[0]!.requiredDependency, null);
  input.sources[0]!.ref.sourceId = "different-source";
  input.copies[0]!.identityProofId = "different-proof";
  assert.equal(parsed.sources[0]!.ref.sourceId, "source-target");
  assert.equal(parsed.copies[0]!.identityProofId, "identity-proof");
});

test("trusted evidence never coerces missing or malformed protection/favorite/dependency facts", () => {
  for (const key of ["protected", "favorite", "requiredDependency"] as const) {
    for (const value of [undefined, 0, 1, "false", "unknown", {}, []]) {
      const input = evidence();
      rejects(() => parseCleanupEvidence({ ...input, sources: [{ ...input.sources[0], [key]: value }] }));
    }
    for (const value of [true, false, null]) {
      const input = evidence();
      const parsed = parseCleanupEvidence({ ...input, sources: [{ ...input.sources[0], [key]: value }] });
      assert.equal(parsed.sources[0]![key], value);
    }
  }
});

test("evidence schemas reject extra metadata and unsupported representation or access claims", () => {
  for (const key of Object.keys(evidence())) rejects(() => parseCleanupEvidence(without(evidence(), key)));
  for (const schemaVersion of [0, 2, "1"]) rejects(() => parseCleanupEvidence({ ...evidence(), schemaVersion }));
  rejects(() => parseCleanupEvidence({ ...evidence(), executionAvailable: true }));
  for (const key of Object.keys(evidence().sources[0]!)) {
    rejects(() => parseCleanupEvidence({ ...evidence(), sources: [without(evidence().sources[0]!, key)] }));
  }
  for (const [key, value] of [["representation", "thumbnail"], ["representation", "pinned"], ["access", true],
    ["access", "permitted"], ["uri", "content://media/1"]] as const) {
    rejects(() => parseCleanupEvidence({ ...evidence(), sources: [{ ...evidence().sources[0], [key]: value }] }));
  }
});

test("proof IDs remain opaque metadata rather than serialized media paths or credentials", () => {
  for (const key of ["physicalSourceId", "pinProofId"] as const) {
    rejects(() => parseCleanupEvidence({ ...evidence(), sources: [{ ...evidence().sources[0], [key]: "content://media/1" }] }));
  }
  for (const key of Object.keys(evidence().copies[0]!)) {
    rejects(() => parseCleanupEvidence({ ...evidence(), copies: [without(evidence().copies[0]!, key)] }));
    rejects(() => parseCleanupEvidence({ ...evidence(), copies: [{ ...evidence().copies[0], [key]: "https://example.test/media?secret=1" }] }));
  }
  rejects(() => parseCleanupEvidence({ ...evidence(), copies: [{ ...evidence().copies[0], credential: "secret" }] }));
  rejects(() => parseCleanupEvidence({ ...evidence(), ownerId: "../owner" }));
});

test("evidence revision and epoch-millisecond fields have safe integer bounds", () => {
  for (const key of ["evidenceRevision", "verifiedAt", "validUntil", "protectionRevision", "favoriteRevision", "dependencyRevision", "copyRevision"] as const) {
    for (const value of INVALID_INTEGERS) rejects(() => parseCleanupEvidence({ ...evidence(), [key]: value }));
    assert.equal(parseCleanupEvidence({ ...evidence(), [key]: Number.MAX_SAFE_INTEGER })[key], Number.MAX_SAFE_INTEGER);
  }
  for (const key of ["evidenceRevision", "validUntil"] as const) rejects(() => parseCleanupEvidence({ ...evidence(), [key]: 0 }));
  for (const key of ["verifiedAt", "protectionRevision", "favoriteRevision", "dependencyRevision", "copyRevision"] as const) {
    assert.equal(parseCleanupEvidence({ ...evidence(), [key]: 0 })[key], 0);
  }
});

test("evidence collections reject oversize or nonarray proofs without requiring caller completeness", () => {
  const input = evidence();
  assert.equal(parseCleanupEvidence({ ...input, sources: Array.from({ length: 100 }, () => input.sources[0]),
    copies: Array.from({ length: 50 }, () => input.copies[0]) }).sources.length, 100);
  // Shape parsing is separate from the workflow's owner, freshness, exact-scope and alias checks.
  assert.deepEqual(parseCleanupEvidence({ ...input, sources: [], copies: [] }).sources, []);
  for (const sources of [null, {}, Array.from({ length: 101 }, () => input.sources[0])]) rejects(() => parseCleanupEvidence({ ...input, sources }));
  for (const copies of [null, {}, Array.from({ length: 51 }, () => input.copies[0])]) rejects(() => parseCleanupEvidence({ ...input, copies }));
});

test("verified-no-change evidence is specific to one owner, source revision and uncertainty", () => {
  const input = noChange();
  const parsed = parseCleanupNoChange(input);
  assert.deepEqual(parsed, input);
  assert.notEqual(parsed.ref, input.ref);
  input.ref.expectedSourceRevision = 9;
  assert.equal(parsed.ref.expectedSourceRevision, 3);
  for (const key of Object.keys(noChange())) rejects(() => parseCleanupNoChange(without(noChange(), key)));
  for (const result of ["success", "deleted", "trashed", "restored", "no_change", null]) {
    rejects(() => parseCleanupNoChange({ ...noChange(), result }));
  }
  for (const key of ["ownerId", "uncertaintyId", "proofId"] as const) rejects(() => parseCleanupNoChange({ ...noChange(), [key]: "file:///proof" }));
  rejects(() => parseCleanupNoChange({ ...noChange(), ref: { ...ref(), expectedSourceRevision: 0 } }));
});

test("no-change proofs cannot smuggle execution outcomes, unsupported schemas or unsafe times", () => {
  for (const schemaVersion of [0, 2, "1"]) rejects(() => parseCleanupNoChange({ ...noChange(), schemaVersion }));
  for (const field of ["executionAvailable", "restoredBytes", "trashReceipt", "credentials", "restoreWindowMs"]) {
    rejects(() => parseCleanupNoChange({ ...noChange(), [field]: true }));
  }
  for (const key of ["verifiedAt", "validUntil"] as const) {
    for (const value of INVALID_INTEGERS) rejects(() => parseCleanupNoChange({ ...noChange(), [key]: value }));
  }
  rejects(() => parseCleanupNoChange({ ...noChange(), validUntil: 0 }));
  assert.equal(parseCleanupNoChange({ ...noChange(), verifiedAt: 0 }).verifiedAt, 0);
});
