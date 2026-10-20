import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import {
  BACKUP_PROOF_SCOPE, BackupRecoveryError, backupDigest, backupId, backupInteger,
  backupSource, parseBackupRevision, parseRequestBackup, parseRestoreBackup,
  parseSyntheticEvidence, parseTransferBackup,
} from "../../packages/contracts/src/backup-recovery.ts";
import type {
  BackupComponent, BackupSourceRef, RequestBackup, SyntheticInputEvidence,
} from "../../packages/contracts/src/backup-recovery.ts";

function source(): BackupSourceRef {
  return { sourceId: "synthetic-source-a", versionId: "fixture-version-1",
    sourceRevision: "source-revision-1", componentSetRevision: "components-revision-1" };
}
function request(): RequestBackup {
  return { idempotencyKey: "request-1", source: source(), destinationId: "synthetic-destination" };
}
function component(componentId = "component-primary", role: BackupComponent["role"] = "primary"): BackupComponent {
  const bytes = Uint8Array.from({ length: 32 }, (_, index) => (index * 17 + 3) % 256);
  return { componentId, componentRevision: "component-revision-1", role,
    byteLength: bytes.length, sha256: createHash("sha256").update(bytes).digest("hex") };
}
function evidence(): SyntheticInputEvidence {
  return { schemaVersion: 1, testOnly: true, ownerId: "synthetic-owner-a", source: source(),
    representation: "generated_synthetic_bytes", fidelity: "synthetic_fixture_only",
    components: [component(), component("component-motion", "motion"), component("component-sidecar", "sidecar")] };
}
function revision() { return { idempotencyKey: "command-1", expectedRevision: 3 }; }
function transfer() { return { ...revision(), componentId: "component-primary", maxBytes: 65_536 }; }
function restore() { return { ...revision(), restoreId: "synthetic-restore-1" }; }
function without(value: object, key: string): Record<string, unknown> {
  const copy: Record<string, unknown> = { ...value }; delete copy[key]; return copy;
}
function rejects(action: () => unknown) {
  assert.throws(action, error => {
    assert.ok(error instanceof BackupRecoveryError);
    assert.equal(error.code, "backup_invalid_contract");
    return true;
  });
}
const NONOBJECTS: unknown[] = [undefined, null, false, true, "fixture", 1, []];
const INVALID_IDS: unknown[] = [undefined, null, 1, "", "a".repeat(97), "../fixture", "a/b", "a\\b", "a.b",
  "/sdcard/DCIM/fixture.jpg", "content://media/1", "file:///tmp/fixture", "https://example.test/object?token=x",
  "fixture space", " fixture", "fixture\n", "fixture\0", "é"];
const INVALID_NUMBERS: unknown[] = [undefined, null, true, "1", -1, 0.5, NaN, Infinity, -Infinity, Number.MAX_SAFE_INTEGER + 1];

test("request retains exact source revisions and reconstructs nested caller data", () => {
  const input = request(), parsed = parseRequestBackup(input);
  assert.deepEqual(parsed, input);
  assert.notEqual(parsed, input);
  assert.notEqual(parsed.source, input.source);
  input.source.sourceId = "changed-after-parse";
  input.source.componentSetRevision = "changed-components";
  assert.equal(parsed.source.sourceId, "synthetic-source-a");
  assert.equal(parsed.source.componentSetRevision, "components-revision-1");
});

test("request cannot carry caller-declared fidelity, evidence, owner or readiness authority", () => {
  for (const [key, value] of Object.entries({ ownerId: "other-owner", evidence: evidence(), trustedEvidence: evidence(),
    verifier: "caller-verifier", immutable: true, fidelity: "immutable_original", pinProofId: "caller-proof",
    productionCapability: true, testOnly: false, state: "verified", objects: [], manifest: { scope: BACKUP_PROOF_SCOPE },
    uri: "content://media/1", path: "/sdcard/DCIM/fixture", url: "https://example.test/object", credentials: { token: "synthetic" } })) {
    rejects(() => parseRequestBackup({ ...request(), [key]: value }));
  }
});

test("all commands reject missing fields, nonobjects and extra fields", () => {
  const parsers: Array<[(value: unknown) => unknown, Record<string, unknown>]> = [
    [parseRequestBackup, request() as unknown as Record<string, unknown>],
    [parseBackupRevision, revision()], [parseTransferBackup, transfer()], [parseRestoreBackup, restore()],
  ];
  for (const [parse, valid] of parsers) {
    for (const value of NONOBJECTS) rejects(() => parse(value));
    for (const key of Object.keys(valid)) rejects(() => parse(without(valid, key)));
    rejects(() => parse({ ...valid, unexpected: "reject" }));
  }
});

test("source references require every exact opaque field and reject path-like handles", () => {
  for (const key of Object.keys(source())) rejects(() => backupSource(without(source(), key)));
  for (const key of ["sourceId", "versionId", "sourceRevision", "componentSetRevision"] as const) {
    for (const value of INVALID_IDS) rejects(() => backupSource({ ...source(), [key]: value }));
    assert.equal(backupSource({ ...source(), [key]: "a".repeat(96) })[key], "a".repeat(96));
  }
  rejects(() => backupSource({ ...source(), byteLength: 32 }));
  rejects(() => parseRequestBackup({ ...request(), source: { ...source(), sha256: component().sha256 } }));
});

test("destination, request, component and restore identifiers use the same bounded opaque grammar", () => {
  for (const value of INVALID_IDS) {
    rejects(() => backupId(value));
    rejects(() => parseRequestBackup({ ...request(), idempotencyKey: value }));
    rejects(() => parseRequestBackup({ ...request(), destinationId: value }));
    rejects(() => parseTransferBackup({ ...transfer(), componentId: value }));
    rejects(() => parseRestoreBackup({ ...restore(), restoreId: value }));
  }
  assert.equal(backupId("A_0-valid"), "A_0-valid");
  assert.equal(backupId("a".repeat(96)), "a".repeat(96));
});

test("commands reject class instances rather than inherited request authority", () => {
  class RequestLookalike {
    idempotencyKey = "request-1";
    source = source();
    destinationId = "synthetic-destination";
  }
  rejects(() => parseRequestBackup(new RequestLookalike()));
  rejects(() => backupSource(Object.assign(Object.create({ authority: true }), source())));
  const nullPrototype = Object.assign(Object.create(null), request());
  assert.deepEqual(parseRequestBackup(nullPrototype), request());
});

test("expected revisions are positive safe integers with no coercion", () => {
  for (const value of [...INVALID_NUMBERS, 0]) {
    rejects(() => parseBackupRevision({ ...revision(), expectedRevision: value }));
    rejects(() => parseTransferBackup({ ...transfer(), expectedRevision: value }));
    rejects(() => parseRestoreBackup({ ...restore(), expectedRevision: value }));
  }
  assert.equal(parseBackupRevision({ ...revision(), expectedRevision: 1 }).expectedRevision, 1);
  assert.equal(parseBackupRevision({ ...revision(), expectedRevision: Number.MAX_SAFE_INTEGER }).expectedRevision, Number.MAX_SAFE_INTEGER);
});

test("transfer chunks enforce a positive actual read bound of 64 KiB", () => {
  for (const value of [...INVALID_NUMBERS, 0, 65_537]) rejects(() => parseTransferBackup({ ...transfer(), maxBytes: value }));
  assert.equal(parseTransferBackup({ ...transfer(), maxBytes: 1 }).maxBytes, 1);
  assert.equal(parseTransferBackup(transfer()).maxBytes, 65_536);
});

test("primitive integer and digest validation rejects coercion and malformed checksum encodings", () => {
  for (const value of INVALID_NUMBERS) rejects(() => backupInteger(value));
  assert.equal(backupInteger(0), 0);
  for (const value of [undefined, null, 1, "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), ` ${"a".repeat(64)}`]) {
    rejects(() => backupDigest(value));
  }
  assert.equal(backupDigest(component().sha256), component().sha256);
});

test("synthetic evidence preserves the entire component set without retaining caller aliases", () => {
  const input = evidence(), parsed = parseSyntheticEvidence(input);
  assert.deepEqual(parsed, input);
  assert.notEqual(parsed.source, input.source);
  assert.notEqual(parsed.components, input.components);
  for (let index = 0; index < input.components.length; index++) assert.notEqual(parsed.components[index], input.components[index]);
  input.source.versionId = "changed-version";
  input.components[0]!.sha256 = "f".repeat(64);
  input.components.splice(1);
  assert.equal(parsed.source.versionId, "fixture-version-1");
  assert.equal(parsed.components.length, 3);
  assert.equal(parsed.components[0]!.sha256, component().sha256);
});

test("evidence is restricted to version-one generated synthetic fixtures", () => {
  for (const value of NONOBJECTS) rejects(() => parseSyntheticEvidence(value));
  for (const key of Object.keys(evidence())) rejects(() => parseSyntheticEvidence(without(evidence(), key)));
  for (const [key, values] of Object.entries({ schemaVersion: [0, 2, "1", null], testOnly: [false, "true", 1],
    representation: ["original_bytes", "readable_representation", "generated_synthetic_bytes_v2", null],
    fidelity: ["immutable_original", "original", "synthetic", null] })) {
    for (const value of values) rejects(() => parseSyntheticEvidence({ ...evidence(), [key]: value }));
  }
  for (const key of ["uri", "path", "filename", "credential", "url", "productionCapability", "verified"]) {
    rejects(() => parseSyntheticEvidence({ ...evidence(), [key]: "forbidden-extra-field" }));
  }
});

test("evidence validates owner and source identity independently of component contents", () => {
  for (const ownerId of INVALID_IDS) rejects(() => parseSyntheticEvidence({ ...evidence(), ownerId }));
  rejects(() => parseSyntheticEvidence({ ...evidence(), source: { ...source(), versionId: "https://example.test/version" } }));
  rejects(() => parseSyntheticEvidence({ ...evidence(), source: { ...source(), pinned: true } }));
});

test("component cardinality is one through eight and always includes one primary", () => {
  const primary = component();
  const maximum = [primary, ...Array.from({ length: 7 }, (_, index) => component(`sidecar-${index}`, "sidecar"))];
  assert.equal(parseSyntheticEvidence({ ...evidence(), components: [primary] }).components.length, 1);
  assert.equal(parseSyntheticEvidence({ ...evidence(), components: maximum }).components.length, 8);
  for (const components of [undefined, null, {}, [], [...maximum, component("sidecar-8", "sidecar")],
    [component("only-motion", "motion")], [primary, component("second-primary")]]) {
    rejects(() => parseSyntheticEvidence({ ...evidence(), components }));
  }
});

test("component identity uniqueness is exact and array holes are never accepted as components", () => {
  rejects(() => parseSyntheticEvidence({ ...evidence(), components: [component(), component()] }));
  const sparse = new Array<BackupComponent>(2);
  sparse[0] = component();
  rejects(() => parseSyntheticEvidence({ ...evidence(), components: sparse }));
});

test("every component binds role, revision, actual positive byte length and SHA-256", () => {
  for (const key of Object.keys(component())) {
    rejects(() => parseSyntheticEvidence({ ...evidence(), components: [without(component(), key)] }));
  }
  for (const key of ["componentId", "componentRevision"] as const) {
    for (const value of INVALID_IDS) rejects(() => parseSyntheticEvidence({ ...evidence(), components: [{ ...component(), [key]: value }] }));
  }
  for (const role of [null, "original", "thumbnail", "PRIMARY", "", 0]) {
    rejects(() => parseSyntheticEvidence({ ...evidence(), components: [{ ...component(), role }] }));
  }
  for (const byteLength of [...INVALID_NUMBERS, 0, 1_048_577]) {
    rejects(() => parseSyntheticEvidence({ ...evidence(), components: [{ ...component(), byteLength }] }));
  }
  for (const byteLength of [1, 1_048_576]) {
    assert.equal(parseSyntheticEvidence({ ...evidence(), components: [{ ...component(), byteLength }] }).components[0]!.byteLength, byteLength);
  }
  for (const sha256 of [null, "A".repeat(64), "a".repeat(63), "z".repeat(64)]) {
    rejects(() => parseSyntheticEvidence({ ...evidence(), components: [{ ...component(), sha256 }] }));
  }
  rejects(() => parseSyntheticEvidence({ ...evidence(), components: [{ ...component(), bytes: [1, 2, 3] }] }));
});

test("evidence never silently substitutes one representation or source revision for another", () => {
  const input = evidence();
  input.source.sourceRevision = "exact-source-revision-2";
  input.source.componentSetRevision = "exact-set-revision-3";
  input.components[1]!.componentRevision = "exact-motion-revision-4";
  const parsed = parseSyntheticEvidence(input);
  assert.equal(parsed.source.sourceRevision, "exact-source-revision-2");
  assert.equal(parsed.source.componentSetRevision, "exact-set-revision-3");
  assert.equal(parsed.components[1]!.componentRevision, "exact-motion-revision-4");
  assert.equal(parsed.representation, "generated_synthetic_bytes");
  assert.equal(parsed.fidelity, "synthetic_fixture_only");
  assert.equal(parsed.testOnly, true);
});
