import assert from "node:assert/strict";
import { request } from "node:http";
import type { IncomingHttpHeaders } from "node:http";
import { once } from "node:events";
import test from "node:test";
import type { TestContext } from "node:test";
import { JOB_KINDS } from "../../packages/contracts/src/index.ts";
import type { BackupPolicyRecord, Capabilities, ConsentRecord, JobRecord, ProductError } from "../../packages/contracts/src/index.ts";
import { createApi } from "../../services/api/src/app.ts";
import type { ApiPrincipal } from "../../services/api/src/app.ts";
import { ControlPlane } from "../../services/workflows/src/store.ts";
import type { StoreOptions } from "../../services/workflows/src/store.ts";

const ALICE_TOKEN = "alice-local-test-token-with-at-least-32-characters";
const BOB_TOKEN = "bob-local-test-token-with-at-least-32-characters";
const PRINCIPALS: ApiPrincipal[] = [
  { ownerId: "alice", token: ALICE_TOKEN },
  { ownerId: "bob", token: BOB_TOKEN },
];
interface Reply<T = unknown> { status: number; headers: IncomingHttpHeaders; body: T }
interface ExchangeOptions {
  path: string;
  method?: string;
  token?: string;
  body?: unknown;
  rawBody?: string;
  chunks?: string[];
  headers?: Record<string, string>;
}
type Failure = { error: ProductError };

async function fixture(t: TestContext, options: StoreOptions = {}, principals = PRINCIPALS) {
  const store = new ControlPlane(":memory:", options);
  const server = createApi({ store, principals });
  t.after(async () => {
    server.closeAllConnections();
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
    store.close();
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  const port = address.port;

  function exchange<T = unknown>(options: ExchangeOptions): Promise<Reply<T>> {
    const raw = options.rawBody ?? (options.body === undefined ? undefined : JSON.stringify(options.body));
    const headers: Record<string, string> = {
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
      ...(raw !== undefined || options.chunks ? { "Content-Type": "application/json" } : {}),
      ...(raw !== undefined ? { "Content-Length": String(Buffer.byteLength(raw)) } : {}),
      ...options.headers,
    };
    return new Promise((resolve, reject) => {
      const outgoing = request({ host: "127.0.0.1", port, path: options.path, method: options.method ?? "GET", headers, agent: false }, response => {
        const parts: Buffer[] = [];
        response.on("data", (part: Buffer) => parts.push(part));
        response.on("error", reject);
        response.on("end", () => {
          try { resolve({ status: response.statusCode ?? 0, headers: response.headers, body: JSON.parse(Buffer.concat(parts).toString("utf8")) as T }); }
          catch (error) { reject(error); }
        });
      });
      outgoing.on("error", reject);
      if (options.chunks) for (const chunk of options.chunks) outgoing.write(chunk);
      outgoing.end(raw);
    });
  }
  return { store, exchange };
}

function consent(key = "grant-consent", assetId = "asset-alice") {
  return { schemaVersion: 1, idempotencyKey: key, expectedRevision: 0, granted: true,
    assetIds: [assetId], purposes: ["document_conversion"], providerIds: ["synthetic-admission"] };
}
function job(key = "create-job", assetId = "asset-alice") {
  return { schemaVersion: 1, idempotencyKey: key, kind: "document_conversion",
    inputs: [{ assetId, versionId: `${assetId}-v1` }], consentRevision: 1, policyRevision: 0,
    budget: { currency: "USD", maxMinor: 250 }, parameters: { format: "pdf" } };
}
function failure(reply: Reply<Failure>, status: number, code: string) {
  assert.equal(reply.status, status);
  assert.equal(reply.body.error.code, code);
  assert.equal(typeof reply.body.error.retryable, "boolean");
  assert.ok(reply.body.error.message.length > 0);
  assert.ok(reply.body.error.recoveryAction.length > 0);
}

test("public health and capabilities work without authentication or cloud providers", async t => {
  const { exchange } = await fixture(t, {}, []);
  const health = await exchange<{ status: string; schemaVersion: number }>({ path: "/health" });
  assert.equal(health.status, 200);
  assert.deepEqual(health.body, { status: "ok", schemaVersion: 1 });
  const capabilities = await exchange<Capabilities>({ path: "/v1/capabilities" });
  assert.equal(capabilities.status, 200);
  assert.deepEqual(capabilities.body, {
    schemaVersion: 1, localGallery: { requiresAccount: false }, cloudCatalogueEnabled: false,
    automaticBackupEnabledByDefault: false,
    providers: JOB_KINDS.map(kind => ({ kind, available: false, reason: "provider_unconfigured" })),
  });
  assert.equal(capabilities.headers["cache-control"], "no-store");
  assert.equal(capabilities.headers["x-content-type-options"], "nosniff");
  assert.equal(capabilities.headers["x-frame-options"], "DENY");
  assert.match(capabilities.headers["content-type"] ?? "", /^application\/json/);
});

test("private endpoints fail closed when authentication is unconfigured", async t => {
  const { store, exchange } = await fixture(t, {}, []);
  for (const path of ["/v1/consent", "/v1/backup-policy", "/v1/jobs"]) {
    const reply = await exchange<Failure>({ path, token: ALICE_TOKEN });
    failure(reply, 503, "authentication_unconfigured");
    assert.equal(reply.body.error.scope, "authentication");
  }
  failure(await exchange<Failure>({ path: "/v1/consent", method: "PUT", body: consent() }), 503, "authentication_unconfigured");
  assert.equal(store.getConsent("alice").revision, 0);
  assert.deepEqual(store.readEvents("alice"), []);
});

test("private endpoints reject missing, wrong and malformed bearer tokens before writes", async t => {
  const { store, exchange } = await fixture(t);
  const rejectedHeaders: Record<string, string>[] = [{}, { Authorization: "Bearer unknown-token" }, { Authorization: `Basic ${ALICE_TOKEN}` }, { Authorization: `bearer ${ALICE_TOKEN}` }];
  for (const headers of rejectedHeaders) {
    const reply = await exchange<Failure>({ path: "/v1/consent", method: "PUT", body: consent(), headers });
    failure(reply, 401, "unauthorized");
    assert.equal(reply.body.error.scope, "authentication");
    assert.ok(!JSON.stringify(reply.body).includes(ALICE_TOKEN));
  }
  assert.equal(store.getConsent("alice").revision, 0);
  assert.deepEqual(store.readEvents("alice"), []);
  assert.equal((await exchange({ path: "/v1/consent", token: ALICE_TOKEN })).status, 200);
});

test("tokens isolate consent, idempotency, job listing, lookup and cancellation by owner", async t => {
  const { exchange } = await fixture(t, {
    providers: [{ kind: "document_conversion", providerId: "synthetic-admission", estimateMinor: 50 }],
    verifyInputs: () => true,
  });
  const grantAlice = await exchange<ConsentRecord>({ path: "/v1/consent", method: "PUT", token: ALICE_TOKEN, body: consent() });
  assert.equal(grantAlice.status, 200);
  assert.equal(grantAlice.body.revision, 1);
  const bobInitial = await exchange<ConsentRecord>({ path: "/v1/consent", token: BOB_TOKEN });
  assert.equal(bobInitial.body.revision, 0);
  assert.deepEqual(bobInitial.body.assetIds, []);
  failure(await exchange<Failure>({ path: "/v1/jobs", method: "POST", token: BOB_TOKEN, body: job() }), 409, "consent_required");

  const alice = await exchange<JobRecord>({ path: "/v1/jobs", method: "POST", token: ALICE_TOKEN, body: job() });
  assert.equal(alice.status, 201);
  assert.equal(alice.body.state, "queued");
  assert.deepEqual(alice.body.budget, { currency: "USD", maxMinor: 250, reservedMinor: 50, incurredMinor: 0 });
  const replay = await exchange<JobRecord>({ path: "/v1/jobs", method: "POST", token: ALICE_TOKEN, body: job() });
  assert.deepEqual(replay.body, alice.body);
  failure(await exchange<Failure>({ path: `/v1/jobs/${alice.body.id}`, token: BOB_TOKEN }), 404, "job_not_found");
  failure(await exchange<Failure>({ path: `/v1/jobs/${alice.body.id}/cancel`, method: "POST", token: BOB_TOKEN,
    body: { schemaVersion: 1, idempotencyKey: "cross-owner-cancel", expectedRevision: 1 } }), 404, "job_not_found");

  // The same operation keys belong to separate owner namespaces.
  assert.equal((await exchange({ path: "/v1/consent", method: "PUT", token: BOB_TOKEN, body: consent("grant-consent", "asset-bob") })).status, 200);
  const bob = await exchange<JobRecord>({ path: "/v1/jobs", method: "POST", token: BOB_TOKEN, body: job("create-job", "asset-bob") });
  assert.equal(bob.status, 201);
  assert.notEqual(bob.body.id, alice.body.id);
  assert.deepEqual((await exchange<{ items: JobRecord[] }>({ path: "/v1/jobs", token: ALICE_TOKEN })).body.items.map(item => item.id), [alice.body.id]);
  assert.deepEqual((await exchange<{ items: JobRecord[] }>({ path: "/v1/jobs", token: BOB_TOKEN })).body.items.map(item => item.id), [bob.body.id]);

  assert.equal((await exchange({ path: "/v1/consent", method: "PUT", token: BOB_TOKEN,
    body: { ...consent("revoke-bob", "asset-bob"), expectedRevision: 1, granted: false } })).status, 200);
  assert.equal((await exchange<JobRecord>({ path: `/v1/jobs/${bob.body.id}`, token: BOB_TOKEN })).body.state, "cancelled");
  const aliceUnchanged = await exchange<JobRecord>({ path: `/v1/jobs/${alice.body.id}`, token: ALICE_TOKEN });
  assert.equal(aliceUnchanged.body.state, "queued");
  assert.equal(aliceUnchanged.body.revision, 1);
  const cancelled = await exchange<JobRecord>({ path: `/v1/jobs/${alice.body.id}/cancel`, method: "POST", token: ALICE_TOKEN,
    body: { schemaVersion: 1, idempotencyKey: "cancel-alice", expectedRevision: 1 } });
  assert.equal(cancelled.status, 200);
  assert.equal(cancelled.body.state, "cancelled");
  assert.equal(cancelled.body.budget.reservedMinor, 0);
});

test("backup policy writes are owner isolated and reject stale revisions", async t => {
  const { exchange } = await fixture(t);
  const policy = { schemaVersion: 1, idempotencyKey: "disabled-backup-settings", expectedRevision: 0,
    enabled: false, includedCollectionIds: ["album-alice"], includeExisting: true, includeVideos: false,
    network: "wifi", destinationId: null };
  const written = await exchange<BackupPolicyRecord>({ path: "/v1/backup-policy", method: "PUT", token: ALICE_TOKEN, body: policy });
  assert.equal(written.status, 200);
  assert.equal(written.body.revision, 1);
  assert.deepEqual(written.body.includedCollectionIds, ["album-alice"]);
  const bob = await exchange<BackupPolicyRecord>({ path: "/v1/backup-policy", token: BOB_TOKEN });
  assert.equal(bob.body.revision, 0);
  assert.equal(bob.body.enabled, false);
  assert.deepEqual(bob.body.includedCollectionIds, []);
  failure(await exchange<Failure>({ path: "/v1/backup-policy", method: "PUT", token: ALICE_TOKEN,
    body: { ...policy, idempotencyKey: "stale-backup-settings" } }), 409, "revision_conflict");
});

test("request schemas reject credential fields, owner overrides and path or URL references", async t => {
  const { store, exchange } = await fixture(t);
  const cases = [
    { path: "/v1/consent", method: "PUT", body: { ...consent(), ownerId: "bob" } },
    { path: "/v1/consent", method: "PUT", body: { ...consent(), credentials: { token: "never-store-this" } } },
    { path: "/v1/jobs", method: "POST", body: { ...job(), token: "never-store-this" } },
    { path: "/v1/jobs", method: "POST", body: { ...job(), credentials: { apiKey: "never-store-this" } } },
    { path: "/v1/jobs", method: "POST", body: { ...job(), budget: { currency: "USD", maxMinor: 250, apiKey: "never-store-this" } } },
    { path: "/v1/jobs", method: "POST", body: { ...job(), parameters: { format: "pdf", accessToken: "never-store-this" } } },
    { path: "/v1/jobs", method: "POST", body: { ...job(), inputs: [{ assetId: "asset-alice", versionId: "v1", uri: "file:///private/media.jpg" }] } },
    ...["/sdcard/DCIM/private.jpg", "../private", "file:///private/media.jpg", "https://example.test/media.jpg", "content://media/123"].map(assetId => ({
      path: "/v1/jobs", method: "POST", body: { ...job(), inputs: [{ assetId, versionId: "v1" }] },
    })),
    { path: "/v1/jobs", method: "POST", body: { ...job(), inputs: [{ assetId: "asset-alice", versionId: "../../private" }] } },
    { path: "/v1/jobs", method: "POST", body: { ...job(), schemaVersion: 2 }, code: "unsupported_schema" },
  ];
  for (const item of cases) {
    const reply = await exchange<Failure>({ ...item, token: ALICE_TOKEN });
    failure(reply, 400, "code" in item ? item.code! : "invalid_request");
    assert.ok(!JSON.stringify(reply.body).includes("never-store-this"));
    assert.ok(!JSON.stringify(reply.body).includes("private.jpg"));
  }
  assert.equal(store.getConsent("alice").revision, 0);
  assert.deepEqual(store.listJobs("alice"), []);
  assert.deepEqual(store.readEvents("alice"), []);
  for (const path of ["/v1/jobs/..%2Fprivate", "/v1/jobs/file%3A%2F%2Fprivate", "/v1/jobs/a%20b", `/v1/jobs/${"a".repeat(129)}`]) {
    failure(await exchange<Failure>({ path, token: ALICE_TOKEN }), 404, "route_not_found");
  }
});

test("budgets must be bounded integer minor units and parsing precedes provider admission", async t => {
  const { store, exchange } = await fixture(t);
  for (const maxMinor of ["250", null, 0, -1, 1.5, 100_001, Number.MAX_SAFE_INTEGER + 1]) {
    failure(await exchange<Failure>({ path: "/v1/jobs", method: "POST", token: ALICE_TOKEN,
      body: { ...job(), budget: { currency: "USD", maxMinor } } }), 400, "invalid_request");
  }
  failure(await exchange<Failure>({ path: "/v1/jobs", method: "POST", token: ALICE_TOKEN,
    body: { ...job(), budget: { currency: "EUR", maxMinor: 250 } } }), 400, "invalid_request");
  for (const maxMinor of [1, 100_000]) {
    failure(await exchange<Failure>({ path: "/v1/jobs", method: "POST", token: ALICE_TOKEN,
      body: { ...job(), budget: { currency: "USD", maxMinor } } }), 503, "provider_unconfigured");
  }
  assert.deepEqual(store.listJobs("alice"), []);
  assert.deepEqual(store.readEvents("alice"), []);
});

test("malformed JSON, unsupported media types and oversized bodies leave state untouched", async t => {
  const { store, exchange } = await fixture(t);
  for (const rawBody of ["{", "", "{\"schemaVersion\":1,}"]) {
    failure(await exchange<Failure>({ path: "/v1/consent", method: "PUT", token: ALICE_TOKEN, rawBody }), 400, "invalid_json");
  }
  failure(await exchange<Failure>({ path: "/v1/consent", method: "PUT", token: ALICE_TOKEN,
    body: consent(), headers: { "Content-Type": "text/plain" } }), 415, "unsupported_media_type");
  failure(await exchange<Failure>({ path: "/v1/consent", method: "PUT", token: ALICE_TOKEN,
    rawBody: "x".repeat(32_769) }), 413, "request_too_large");
  failure(await exchange<Failure>({ path: "/v1/consent", method: "PUT", token: ALICE_TOKEN,
    chunks: ["x".repeat(16_384), "x".repeat(16_385)] }), 413, "request_too_large");
  assert.equal(store.getConsent("alice").revision, 0);
  assert.deepEqual(store.readEvents("alice"), []);
  // A rejected upload must not stop subsequent valid requests on the server.
  assert.equal((await exchange({ path: "/v1/consent", method: "PUT", token: ALICE_TOKEN, body: consent(),
    headers: { "Content-Type": "application/json; charset=utf-8" } })).status, 200);
});

test("unconfigured cloud services explicitly fail without creating jobs, policies or events", async t => {
  const { store, exchange } = await fixture(t);
  const commands = [
    job(),
    { ...job(), kind: "image_try_on", inputs: [{ assetId: "base", versionId: "base-v1" }, { assetId: "reference", versionId: "reference-v1" }],
      parameters: { borrow: "outfit", referenceVersionId: "reference-v1" } },
    { ...job(), kind: "video_edit", parameters: { prompt: "Trim the selected clip", aspectRatio: "16:9" } },
    { ...job(), kind: "backup", parameters: { origin: "manual", destinationId: "destination" } },
  ];
  for (const body of commands) {
    const reply = await exchange<Failure>({ path: "/v1/jobs", method: "POST", token: ALICE_TOKEN, body });
    failure(reply, 503, "provider_unconfigured");
    assert.equal(reply.body.error.scope, "provider");
    assert.match(reply.body.error.message, /No media was uploaded or processing started/);
    assert.equal(reply.body.error.retryable, false);
  }
  failure(await exchange<Failure>({ path: "/v1/backup-policy", method: "PUT", token: ALICE_TOKEN,
    body: { schemaVersion: 1, idempotencyKey: "enable-automatic-backup", expectedRevision: 0, enabled: true,
      includedCollectionIds: ["album"], includeExisting: false, includeVideos: false, network: "wifi", destinationId: "destination" } }), 503, "provider_unconfigured");
  assert.deepEqual((await exchange<{ items: JobRecord[] }>({ path: "/v1/jobs", token: ALICE_TOKEN })).body.items, []);
  assert.equal(store.getBackupPolicy("alice").revision, 0);
  assert.equal(store.getBackupPolicy("alice").enabled, false);
  assert.deepEqual(store.readEvents("alice"), []);
});
