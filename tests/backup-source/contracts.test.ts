import assert from "node:assert/strict";
import test from "node:test";
import { BackupRecoveryError } from "../../packages/contracts/src/backup-recovery.ts";
import {
  SOURCE_SNAPSHOT_LIMITS, SOURCE_SNAPSHOT_SCOPE, SourceSnapshotError,
  parseSourceSnapshotRequest, snapshotData, snapshotSource,
} from "../../packages/contracts/src/backup-source-snapshot.ts";
import type { BackupSourceRef } from "../../packages/contracts/src/backup-recovery.ts";

function source(): BackupSourceRef {
  return { sourceId: "generated-source-1", versionId: "generated-version-1",
    sourceRevision: "source-revision-1", componentSetRevision: "component-set-revision-1" };
}
function request() { return { source: source(), timeoutMs: 1_000 }; }
function without(value: object, key: string): Record<string, unknown> {
  const copy: Record<string, unknown> = { ...value }; delete copy[key]; return copy;
}
function rejects(action: () => unknown, expected: "structure" | "scalar" = "structure") {
  assert.throws(action, error => {
    if (expected === "structure") {
      assert.ok(error instanceof SourceSnapshotError);
      assert.equal(error.code, "snapshot_invalid_contract");
    } else {
      assert.ok(error instanceof BackupRecoveryError);
      assert.equal(error.code, "backup_invalid_contract");
    }
    return true;
  });
}
const INVALID_OBJECTS: unknown[] = [undefined, null, true, false, 0, 1, "request", [], new Map(), new Set(), new Date(0)];
const INVALID_IDS: unknown[] = [undefined, null, true, 1, "", "a".repeat(97), "../fixture", "a/b", "a\\b", "a.b",
  "/sdcard/DCIM/private.jpg", "content://media/external/images/media/1", "file:///tmp/private",
  "https://example.test/media?signature=synthetic", "id space", " id", "id\n", "id\0", "é"];

test("request preserves exact revisions without retaining a nested caller alias", () => {
  const input = request(), parsed = parseSourceSnapshotRequest(input);
  assert.deepEqual(parsed, input);
  assert.notEqual(parsed, input);
  assert.notEqual(parsed.source, input.source);
  input.source.sourceId = "changed-source";
  input.source.versionId = "changed-version";
  input.source.sourceRevision = "changed-revision";
  input.source.componentSetRevision = "changed-set";
  input.timeoutMs = 20_000;
  assert.deepEqual(parsed, request());
});

test("source reconstruction preserves each exact identity field independently", () => {
  const input = source(), parsed = snapshotSource(input);
  assert.deepEqual(parsed, input);
  assert.notEqual(parsed, input);
  for (const key of Object.keys(input) as Array<keyof BackupSourceRef>) input[key] = `changed-${key}`;
  assert.deepEqual(parsed, source());
});

test("request and source reject every non-record shape and missing required field", () => {
  for (const value of INVALID_OBJECTS) {
    rejects(() => parseSourceSnapshotRequest(value));
    rejects(() => snapshotSource(value));
  }
  for (const key of Object.keys(request())) rejects(() => parseSourceSnapshotRequest(without(request(), key)));
  for (const key of Object.keys(source())) rejects(() => snapshotSource(without(source(), key)));
});

test("caller authority, source bytes and production or provider fields never enter requests", () => {
  const forbidden: Record<string, unknown> = {
    ownerId: "other-owner", evidence: { immutable: true }, sourceVerifier: "caller-verifier", immutable: true,
    fidelity: "original_bytes", representation: "original_bytes", productionCapability: true, testOnly: false,
    scope: "production", session: { scope: SOURCE_SNAPSHOT_SCOPE, testOnly: true }, snapshot: "caller-snapshot",
    componentIds: ["caller-component"], bytes: [1, 2, 3], sha256: "a".repeat(64), byteLength: 3,
    provider: "drive", connectionEpoch: "epoch", destinationId: "destination", credentials: { token: "synthetic" },
    email: "fixture@example.test", path: "/sdcard/DCIM/private.jpg", uri: "content://media/1",
    url: "https://example.test/media", originalFilename: "private.jpg",
  };
  for (const [key, value] of Object.entries(forbidden)) {
    rejects(() => parseSourceSnapshotRequest({ ...request(), [key]: value }));
    rejects(() => parseSourceSnapshotRequest({ ...request(), source: { ...source(), [key]: value } }));
  }
});

test("non-enumerable extra fields are rejected at both request and source boundaries", () => {
  const input = request();
  Object.defineProperty(input, "credentials", { value: "synthetic-secret", enumerable: false });
  rejects(() => parseSourceSnapshotRequest(input));
  const nested = source();
  Object.defineProperty(nested, "uri", { value: "content://media/1", enumerable: false });
  rejects(() => parseSourceSnapshotRequest({ ...request(), source: nested }));
});

test("symbol extra fields cannot hide caller authority outside JSON", () => {
  const authority = Symbol("authority");
  rejects(() => parseSourceSnapshotRequest({ ...request(), [authority]: true }));
  rejects(() => parseSourceSnapshotRequest({ ...request(), source: { ...source(), [authority]: true } }));
});

test("top-level required getters are rejected without evaluating them", () => {
  let getterCalls = 0;
  for (const key of ["source", "timeoutMs"] as const) {
    const input = request();
    Object.defineProperty(input, key, { enumerable: true, get() { getterCalls++; return key === "source" ? source() : 1_000; } });
    rejects(() => parseSourceSnapshotRequest(input));
  }
  assert.equal(getterCalls, 0);
});

test("nested source getters are rejected without reading a changing identity", () => {
  let getterCalls = 0;
  for (const key of Object.keys(source()) as Array<keyof BackupSourceRef>) {
    const input = source();
    Object.defineProperty(input, key, { enumerable: true, get() { getterCalls++; return "replacement-identity"; } });
    rejects(() => parseSourceSnapshotRequest({ ...request(), source: input }));
  }
  assert.equal(getterCalls, 0);
});

test("setter-only required properties are not interpreted as valid source values", () => {
  let setterCalls = 0;
  const input = request();
  Object.defineProperty(input, "source", { enumerable: true, set(_value: unknown) { setterCalls++; } });
  rejects(() => parseSourceSnapshotRequest(input));
  const nested = source();
  Object.defineProperty(nested, "sourceRevision", { enumerable: true, set(_value: unknown) { setterCalls++; } });
  rejects(() => snapshotSource(nested));
  assert.equal(setterCalls, 0);
});

test("hidden extra getters are rejected without extracting their contents", () => {
  let getterCalls = 0;
  const input = request();
  Object.defineProperty(input, "credentials", { enumerable: false, get() { getterCalls++; throw new Error("must not read"); } });
  rejects(() => parseSourceSnapshotRequest(input));
  const nested = source();
  Object.defineProperty(nested, Symbol("uri"), { enumerable: false, get() { getterCalls++; throw new Error("must not read"); } });
  rejects(() => snapshotSource(nested));
  assert.equal(getterCalls, 0);
});

test("required fields must be enumerable data properties", () => {
  for (const key of ["source", "timeoutMs"] as const) {
    const input = request();
    Object.defineProperty(input, key, { value: input[key], enumerable: false });
    rejects(() => parseSourceSnapshotRequest(input));
  }
  for (const key of Object.keys(source()) as Array<keyof BackupSourceRef>) {
    const nested = source();
    Object.defineProperty(nested, key, { value: nested[key], enumerable: false });
    rejects(() => snapshotSource(nested));
  }
});

test("inherited properties and class instances cannot supply a request or source", () => {
  class RequestLookalike { source = source(); timeoutMs = 1_000; }
  rejects(() => parseSourceSnapshotRequest(new RequestLookalike()));
  rejects(() => parseSourceSnapshotRequest(Object.create(request())));
  rejects(() => snapshotSource(Object.assign(Object.create({ immutable: true }), source())));
  const nested = Object.create({ sourceRevision: "source-revision-1" });
  Object.assign(nested, without(source(), "sourceRevision"));
  rejects(() => snapshotSource(nested));
});

test("plain null-prototype and frozen data records remain supported", () => {
  const nested = Object.freeze(Object.assign(Object.create(null), source()));
  const input = Object.freeze(Object.assign(Object.create(null), { ...request(), source: nested }));
  assert.deepEqual(parseSourceSnapshotRequest(input), request());
  assert.deepEqual(snapshotSource(Object.freeze(source())), source());
});

test("all source identities enforce bounded opaque IDs instead of paths or URLs", () => {
  for (const key of Object.keys(source()) as Array<keyof BackupSourceRef>) {
    for (const value of INVALID_IDS) rejects(() => snapshotSource({ ...source(), [key]: value }), "scalar");
    assert.equal(snapshotSource({ ...source(), [key]: "a".repeat(96) })[key], "a".repeat(96));
  }
  assert.equal(snapshotSource({ ...source(), sourceId: "A_0-valid" }).sourceId, "A_0-valid");
});

test("timeout is an explicit positive safe integer capped at thirty seconds", () => {
  for (const timeoutMs of [undefined, null, true, "1000", -1, -0, 0, 0.5, NaN, Infinity, -Infinity, 30_001, Number.MAX_SAFE_INTEGER + 1]) {
    rejects(() => parseSourceSnapshotRequest({ ...request(), timeoutMs }), "scalar");
  }
  assert.equal(parseSourceSnapshotRequest({ ...request(), timeoutMs: 1 }).timeoutMs, 1);
  assert.equal(parseSourceSnapshotRequest({ ...request(), timeoutMs: 30_000 }).timeoutMs, 30_000);
});

test("published limits are immutable and describe bounded synthetic acquisition", () => {
  assert.equal(SOURCE_SNAPSHOT_SCOPE, "synthetic_source_snapshot_v1");
  assert.deepEqual(SOURCE_SNAPSHOT_LIMITS, { components: 8, componentBytes: 1_048_576,
    retainedBytes: 8_388_608, readBytes: 65_536, timeoutMs: 30_000 });
  assert.ok(Object.isFrozen(SOURCE_SNAPSHOT_LIMITS));
  assert.throws(() => { (SOURCE_SNAPSHOT_LIMITS as { timeoutMs: number }).timeoutMs = 60_000; }, TypeError);
  assert.equal(SOURCE_SNAPSHOT_LIMITS.timeoutMs, 30_000);
});

test("data extraction reads property descriptors without exposing a caller-owned wrapper", () => {
  const input = { value: "generated" };
  const parsed = snapshotData(input, ["value"]);
  assert.deepEqual(parsed, input);
  assert.notEqual(parsed, input);
  input.value = "changed";
  assert.equal(parsed.value, "generated");
});
