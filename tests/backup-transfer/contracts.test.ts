import assert from "node:assert/strict";
import test from "node:test";
import {
  TRANSFER_PROOF_SCOPE, TransferProofError, parseCreateTransfer, parseDispatchTransfer,
  parseTransferRevision, transferBinding, transferId, transferInteger, transferObject,
  transferProviderObject, transferSource,
} from "../../packages/contracts/src/backup-transfer-recovery.ts";
import type {
  CreateTransfer, DispatchTransfer, TransferBinding, TransferSource,
} from "../../packages/contracts/src/backup-transfer-recovery.ts";

function source(): TransferSource {
  return {
    sourceId: "generated_source_1", versionId: "generated_version_2", sourceRevision: "source_revision_3",
    componentSetRevision: "component_set_4", componentId: "generated_component_5", componentRevision: "component_revision_6",
  };
}
function binding(): TransferBinding {
  return { connectionEpoch: "generated_connection_epoch_7", destinationId: "generated_destination_8", source: source(), totalBytes: 4096 };
}
function create(): CreateTransfer { return { idempotencyKey: "create_fixture_1", binding: binding() }; }
function revision() { return { idempotencyKey: "revision_fixture_1", expectedRevision: 3 }; }
function dispatch(): DispatchTransfer { return { ...revision(), kind: "append", byteLength: 1024, leaseMs: 1000 }; }
function without(value: object, key: string): Record<string, unknown> {
  const data: Record<string, unknown> = { ...value }; delete data[key]; return data;
}
function rejects(action: () => unknown): void {
  assert.throws(action, error => error instanceof TransferProofError && error.code === "transfer_invalid_contract");
}
const NONOBJECTS: unknown[] = [undefined, null, false, true, 0, "generated_fixture", [], new Map(), new Date(0), new Uint8Array(2)];
const INVALID_IDS: unknown[] = [undefined, null, 1, false, "", "a".repeat(97), "fixture space", " fixture", "fixture\n", "fixture\r", "fixture\0", "fixture\u2028",
  "fixture.name", "fixture@example.test", "/tmp/generated-fixture", "../fixture", "a/b", "a\\b", "content://generated/1", "file:///tmp/generated", "https://example.test/session", "é"];
const INVALID_NUMBERS: unknown[] = [undefined, null, true, "1", NaN, Infinity, -Infinity, -1, 0.5, Number.MAX_SAFE_INTEGER + 1];

test("S53 contract: every source/version/component revision is retained without aliases", () => {
  const input = source(), parsed = transferSource(input);
  assert.deepEqual(parsed, input); assert.notEqual(parsed, input);
  for (const key of Object.keys(input) as Array<keyof TransferSource>) {
    const changed = { ...source(), [key]: `changed_${key}` };
    assert.equal(transferSource(changed)[key], changed[key]);
    rejects(() => transferSource(without(source(), key)));
  }
  input.componentRevision = "later_component_revision"; input.versionId = "later_version";
  assert.equal(parsed.componentRevision, "component_revision_6"); assert.equal(parsed.versionId, "generated_version_2");
});

test("S53 contract: opaque identifiers reject email, session URLs and source paths", () => {
  for (const value of INVALID_IDS) {
    rejects(() => transferId(value));
    for (const field of Object.keys(source())) rejects(() => transferSource({ ...source(), [field]: value }));
    rejects(() => transferBinding({ ...binding(), connectionEpoch: value }));
    rejects(() => transferBinding({ ...binding(), destinationId: value }));
    rejects(() => parseTransferRevision({ ...revision(), idempotencyKey: value }));
  }
  assert.equal(transferId("A_0-valid"), "A_0-valid");
  assert.equal(transferId("a".repeat(96)), "a".repeat(96));
});

test("S53 contract: account epoch and destination remain independent immutable inputs", () => {
  const first = binding(), next = { ...binding(), connectionEpoch: "fresh_reauthorization_epoch" };
  const oldParsed = transferBinding(first), newParsed = transferBinding(next);
  assert.notEqual(oldParsed.connectionEpoch, newParsed.connectionEpoch);
  assert.equal(newParsed.destinationId, oldParsed.destinationId);
  assert.deepEqual(newParsed.source, oldParsed.source);
  const changedDestination = transferBinding({ ...first, destinationId: "other_destination" });
  assert.equal(changedDestination.connectionEpoch, oldParsed.connectionEpoch);
  assert.equal(changedDestination.destinationId, "other_destination");
});

test("S53 contract: binding snapshots reconstruct the source and preserve declared total", () => {
  const input = binding(), parsed = transferBinding(input);
  assert.deepEqual(parsed, input); assert.notEqual(parsed, input); assert.notEqual(parsed.source, input.source);
  input.source.sourceRevision = "later_source"; input.connectionEpoch = "later_epoch"; input.totalBytes = 1;
  assert.equal(parsed.source.sourceRevision, "source_revision_3");
  assert.equal(parsed.connectionEpoch, "generated_connection_epoch_7"); assert.equal(parsed.totalBytes, 4096);
  for (const totalBytes of [1, 1_048_576]) assert.equal(transferBinding({ ...binding(), totalBytes }).totalBytes, totalBytes);
  for (const totalBytes of [...INVALID_NUMBERS, 0, 1_048_577]) rejects(() => transferBinding({ ...binding(), totalBytes }));
});

test("S53 contract: create reconstructs nested binding before callers can change it", () => {
  const input = create(), parsed = parseCreateTransfer(input);
  assert.deepEqual(parsed, input); assert.notEqual(parsed, input);
  assert.notEqual(parsed.binding, input.binding); assert.notEqual(parsed.binding.source, input.binding.source);
  input.binding.source.componentSetRevision = "later_set"; input.binding.destinationId = "later_destination";
  assert.equal(parsed.binding.source.componentSetRevision, "component_set_4");
  assert.equal(parsed.binding.destinationId, "generated_destination_8");
});

test("S53 contract: callers cannot include authority, status, progress or production claims", () => {
  const forbidden = {
    ownerId: "caller_owner", attemptId: "caller_attempt", state: "uploaded_unverified", acknowledgedOffset: 4096,
    pending: {}, receipt: {}, trusted: true, immutable: true, fidelity: "original_bytes", verified: true,
    testOnly: true, productionCapability: true, scope: TRANSFER_PROOF_SCOPE,
  };
  for (const [key, value] of Object.entries(forbidden)) rejects(() => parseCreateTransfer({ ...create(), [key]: value }));
});

test("S53 contract: sensitive fields are rejected at request, binding and source boundaries", () => {
  const forbidden = {
    email: "generated@example.test", displayName: "Generated account", accessToken: "generated-not-a-token",
    refreshToken: "generated-not-a-token", oauthToken: "generated-not-a-token", token: "generated-not-a-token",
    resumableUrl: "https://example.test/generated", url: "https://example.test/generated",
    path: "/tmp/generated-fixture", uri: "content://generated/1", filename: "generated-fixture",
    mediaBytes: [1, 2], bytes: new Uint8Array([1, 2]), content: "generated content",
  };
  for (const [key, value] of Object.entries(forbidden)) {
    rejects(() => parseCreateTransfer({ ...create(), [key]: value }));
    rejects(() => transferBinding({ ...binding(), [key]: value }));
    rejects(() => transferSource({ ...source(), [key]: value }));
  }
});

test("S53 contract: wrong object kinds and inherited request shapes are rejected", () => {
  const parsers: Array<(value: unknown) => unknown> = [parseCreateTransfer, parseTransferRevision, parseDispatchTransfer, transferBinding, transferSource, transferProviderObject];
  for (const parse of parsers) for (const input of NONOBJECTS) rejects(() => parse(input));
  class Request { idempotencyKey = "class_request"; binding = binding(); }
  rejects(() => parseCreateTransfer(new Request()));
  rejects(() => parseCreateTransfer(Object.create(create())));
  rejects(() => transferSource(Object.create(source())));
});

test("S53 contract: every parser requires its exact field set", () => {
  const cases: Array<[(value: unknown) => unknown, object]> = [
    [parseCreateTransfer, create()], [parseTransferRevision, revision()], [parseDispatchTransfer, dispatch()],
    [transferBinding, binding()], [transferSource, source()],
    [transferProviderObject, { objectId: "object_1", sessionId: "session_2", objectRevision: "object_revision_3" }],
  ];
  for (const [parse, valid] of cases) {
    for (const key of Object.keys(valid)) rejects(() => parse(without(valid, key)));
    rejects(() => parse({ ...valid, unknown: "must_reject" }));
  }
});

test("S53 contract: null-prototype input is reconstructed into ordinary owned objects", () => {
  const input = Object.assign(Object.create(null), create());
  input.binding = Object.assign(Object.create(null), binding());
  input.binding.source = Object.assign(Object.create(null), source());
  const parsed = parseCreateTransfer(input);
  assert.deepEqual(parsed, create()); assert.equal(Object.getPrototypeOf(parsed), Object.prototype);
  assert.equal(Object.getPrototypeOf(parsed.binding), Object.prototype); assert.equal(Object.getPrototypeOf(parsed.binding.source), Object.prototype);
  const poisoned = JSON.parse('{"idempotencyKey":"generated_request","binding":null,"__proto__":{"trusted":true}}');
  rejects(() => parseCreateTransfer(poisoned));
});

test("S53 contract: expected revisions require positive safe integers without coercion", () => {
  for (const expectedRevision of [...INVALID_NUMBERS, 0]) {
    rejects(() => parseTransferRevision({ ...revision(), expectedRevision }));
    rejects(() => parseDispatchTransfer({ ...dispatch(), expectedRevision }));
  }
  for (const expectedRevision of [1, Number.MAX_SAFE_INTEGER]) assert.equal(parseTransferRevision({ ...revision(), expectedRevision }).expectedRevision, expectedRevision);
});

test("S53 contract: append dispatch admits only positive bounded byte counts", () => {
  for (const byteLength of [1, 65_536]) assert.equal(parseDispatchTransfer({ ...dispatch(), byteLength }).byteLength, byteLength);
  for (const byteLength of [...INVALID_NUMBERS, 0, 65_537]) rejects(() => parseDispatchTransfer({ ...dispatch(), byteLength }));
  const parsed = parseDispatchTransfer(dispatch());
  assert.equal(parsed.kind, "append"); assert.equal(parsed.byteLength, 1024); assert.equal(parsed.leaseMs, 1000);
});

test("S53 contract: create and finalize submit no bytes rather than an append payload", () => {
  for (const kind of ["create", "finalize"] as const) {
    const parsed = parseDispatchTransfer({ ...dispatch(), kind, byteLength: 0 });
    assert.equal(parsed.kind, kind); assert.equal(parsed.byteLength, 0);
    for (const byteLength of [...INVALID_NUMBERS, 1, 65_536]) rejects(() => parseDispatchTransfer({ ...dispatch(), kind, byteLength }));
  }
});

test("S53 contract: dispatch kinds are exact protocol values", () => {
  for (const kind of [undefined, null, false, 1, "", "upload", "resume", "verify", "restore", "APPEND", "append ", " create"])
    rejects(() => parseDispatchTransfer({ ...dispatch(), kind }));
});

test("S53 contract: a lease is bounded time metadata and cannot carry caller settlement", () => {
  for (const leaseMs of [1, 300_000]) assert.equal(parseDispatchTransfer({ ...dispatch(), leaseMs }).leaseMs, leaseMs);
  for (const leaseMs of [...INVALID_NUMBERS, 0, 300_001]) rejects(() => parseDispatchTransfer({ ...dispatch(), leaseMs }));
  for (const [key, value] of Object.entries({ settled: true, remoteStopped: true, status: "not_applied", connectionEpoch: "replacement_epoch", sessionId: "replacement_session", fromOffset: 1, toOffset: 4096 }))
    rejects(() => parseDispatchTransfer({ ...dispatch(), [key]: value }));
});

test("S53 contract: provider handles preserve object, session and opaque revision separately", () => {
  const input = { objectId: "object_1", sessionId: "session_2", objectRevision: "object_revision_3" }, parsed = transferProviderObject(input);
  assert.deepEqual(parsed, input); assert.notEqual(parsed, input);
  input.sessionId = "late_session"; input.objectRevision = "late_revision";
  assert.equal(parsed.sessionId, "session_2"); assert.equal(parsed.objectRevision, "object_revision_3");
  for (const field of ["objectId", "sessionId", "objectRevision"] as const)
    for (const value of INVALID_IDS) rejects(() => transferProviderObject({ ...input, [field]: value }));
  rejects(() => transferProviderObject({ ...input, resumableUrl: "https://example.test/generated" }));
});

test("S53 contract: integer helper enforces explicit inclusive bounds", () => {
  for (const value of INVALID_NUMBERS) rejects(() => transferInteger(value));
  assert.equal(transferInteger(0), 0); assert.equal(transferInteger(Number.MAX_SAFE_INTEGER), Number.MAX_SAFE_INTEGER);
  for (const value of [3, 7]) assert.equal(transferInteger(value, 3, 7), value);
  for (const value of [2, 8]) rejects(() => transferInteger(value, 3, 7));
});

test("S53 contract: required fields must be enumerable own fields", () => {
  const hidden = { idempotencyKey: "generated_request" };
  Object.defineProperty(hidden, "binding", { value: binding(), enumerable: false });
  rejects(() => parseCreateTransfer(hidden));
  rejects(() => transferObject({ present: "value" }, ["inherited"]));
  assert.deepEqual(transferObject({ exact: "value" }, ["exact"]), { exact: "value" });
});
