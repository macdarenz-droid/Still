import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { setImmediate as nextTurn } from "node:timers/promises";
import test from "node:test";
import type { TestContext } from "node:test";
import { SOURCE_SNAPSHOT_LIMITS as LIMITS, SourceSnapshotError } from "../../packages/contracts/src/backup-source-snapshot.ts";
import type { SourceSnapshotSession } from "../../packages/contracts/src/backup-source-snapshot.ts";
import { generatedBytes } from "../../services/backup/synthetic-store.ts";
import { SourceSnapshotAcquisition } from "../../services/backup-source/acquisition.ts";
import { acceptsGeneratedInventory, MutableSyntheticSource, openGeneratedStream } from "../../services/backup-source/synthetic-source.ts";
import type { GeneratedComponentDefinition } from "../../services/backup-source/synthetic-source.ts";

const OWNER = "generated_owner";
const DEFINITIONS: GeneratedComponentDefinition[] = [{ role: "primary", seed: 101, byteLength: 150_003 }, { role: "motion", seed: 202, byteLength: 30_017 }, { role: "sidecar", seed: 303, byteLength: 79 }];
const code = (expected: string) => (error: unknown) => error instanceof SourceSnapshotError && error.code === expected;
function fails(action: () => unknown, expected: string): void { assert.throws(action, code(expected)); }
function deferred() { let resolve!: () => void; const promise = new Promise<void>(done => { resolve = done; }); return { promise, resolve }; }
function fixture(t: TestContext, definitions = DEFINITIONS) {
  const source = new MutableSyntheticSource(), ref = source.generate(OWNER, definitions), clock = { at: 10_000 }, authority = { current: true };
  const controllers: SourceSnapshotAcquisition[] = [];
  const open = () => { const value = new SourceSnapshotAcquisition({ source, now: () => clock.at }); controllers.push(value); return value; };
  const controller = open(), begin = (target = controller, check = () => authority.current, timeoutMs = 1000) => target.begin(OWNER, { source: ref, timeoutMs }, check);
  t.after(async () => { source.hooksForTest({}); for (const value of controllers) value.dispose(); await controller.settle(OWNER); });
  return { source, ref, clock, authority, controller, open, begin };
}

test("S57 default and forged source authority deny snapshot acquisition", async t => {
  const f = fixture(t), denied = new SourceSnapshotAcquisition();
  fails(() => denied.begin(OWNER, { source: f.ref, timeoutMs: 1000 }, () => true), "snapshot_source_authority_denied");
  const fake = new SourceSnapshotAcquisition({ source: Object.create(MutableSyntheticSource.prototype) as MutableSyntheticSource });
  const fakeSession = fake.begin(OWNER, { source: f.ref, timeoutMs: 1000 }, () => true);
  await assert.rejects(fake.acquire(fakeSession), code("snapshot_source_authority_denied"));
  const inventory = f.source.inventoryForTest(OWNER, f.ref);
  assert.equal(acceptsGeneratedInventory(f.source, structuredClone(inventory)), false);
  assert.equal(acceptsGeneratedInventory(new MutableSyntheticSource(), inventory), false);
  const wrong = f.controller.begin("other_owner", { source: f.ref, timeoutMs: 1000 }, () => true);
  await assert.rejects(f.controller.acquire(wrong), code("snapshot_source_changed"));
  assert.equal(f.source.countsForTest().opens, 0);
});

test("S57 complete generated primary and companions publish one immutable test-only snapshot", async t => {
  const f = fixture(t), handle = await f.controller.acquire(f.begin());
  const description = f.controller.verifyContentsForTest(OWNER, handle);
  assert.equal(description.productionCapability, false); assert.equal(description.testOnly, true);
  assert.equal(description.representation, "generated_synthetic_bytes"); assert.equal(description.fidelity, "synthetic_fixture_only");
  assert.deepEqual(description.source, f.ref);
  assert.deepEqual(description.components.map(item => ({ role: item.role, byteLength: item.byteLength, sha256: item.sha256 })), DEFINITIONS.map(item => ({ role: item.role, byteLength: item.byteLength, sha256: createHash("sha256").update(generatedBytes(item.seed, item.byteLength)).digest("hex") })));
  assert.deepEqual(f.source.countsForTest(), { opens: 3, closes: 3, unsettled: 0 });
  assert.equal(f.controller.retainedBytesForTest(), DEFINITIONS.reduce((sum, item) => sum + item.byteLength, 0));
  for (const item of [description, description.source, description.components, ...description.components]) assert.ok(Object.isFrozen(item));
  for (const forbidden of ["ownerId", "path", "uri", "filename", "email", "token", "destinationId", "objectId", "bytes"]) assert.equal(Object.hasOwn(description, forbidden), false);
  const copied = f.controller.describe(OWNER, handle); assert.notEqual(copied, description); assert.deepEqual(copied, description);
  f.controller.release(OWNER, handle); assert.equal(f.controller.retainedBytesForTest(), 0);
});

test("S57 unknown partial and unsupported inventories never open a component", async t => {
  for (const completeness of ["unknown", "partial", "unsupported"] as const) {
    const f = fixture(t); f.source.completenessForTest(OWNER, f.ref, completeness);
    await assert.rejects(f.controller.acquire(f.begin()), code("snapshot_inventory_incomplete"));
    assert.deepEqual(f.source.countsForTest(), { opens: 0, closes: 0, unsettled: 0 });
    assert.equal(f.controller.retainedBytesForTest(), 0);
  }
});

test("S57 required source and full inventory fences reject mutation while a later component is read", async t => {
  for (const change of ["source", "version", "set", "component", "remove", "add", "reorder", "bytes"] as const) {
    const f = fixture(t); let changed = false;
    f.source.hooksForTest({ read(event) { if (event.componentIndex === 1 && !changed) { changed = true; f.source.changeForTest(OWNER, f.ref, change); } } });
    await assert.rejects(f.controller.acquire(f.begin()), error => error instanceof SourceSnapshotError && ["snapshot_source_changed", "snapshot_inventory_changed"].includes(error.code));
    assert.equal(changed, true); assert.equal(f.source.countsForTest().opens, 2);
    assert.equal(f.source.countsForTest().unsettled, 0); assert.equal(f.controller.retainedBytesForTest(), 0);
  }
});

test("S57 a final publication fence rejects added or revised inventory after every component closed", async t => {
  for (const change of ["add", "component", "bytes"] as const) {
    const f = fixture(t);
    f.source.hooksForTest({ inspect(stage) { if (stage === "before_publication") f.source.changeForTest(OWNER, f.ref, change); } });
    await assert.rejects(f.controller.acquire(f.begin()), code("snapshot_inventory_changed"));
    assert.deepEqual(f.source.countsForTest(), { opens: 3, closes: 3, unsettled: 0 });
    assert.equal(f.controller.retainedBytesForTest(), 0);
  }
});

test("S58 generated read chunks do not alias mutable source storage", async t => {
  const f = fixture(t), inventory = f.source.inventoryForTest(OWNER, f.ref), first = inventory.components[0];
  const stream = openGeneratedStream(f.source, OWNER, f.ref, first);
  try {
    const captured = (await stream.read(LIMITS.readBytes))!, before = captured.slice();
    f.source.changeForTest(OWNER, f.ref, "bytes");
    assert.deepEqual(captured, before);
    captured[0] ^= 255;
    assert.notEqual(f.source.inventoryForTest(OWNER, f.ref).components[0].sha256, inventory.components[0].sha256, "source revision must change when source bytes change");
  } finally { await stream.close(); }
});

test("S58 short extra corrupt failed and zero-length reads never publish partial snapshots", async t => {
  const cases = { short: "snapshot_length_mismatch", extra: "snapshot_length_mismatch", corrupt: "snapshot_hash_mismatch", io: "snapshot_read_failed", zero: "snapshot_read_invalid" } as const;
  for (const fault of Object.keys(cases) as (keyof typeof cases)[]) {
    const f = fixture(t); f.source.faultForTest(0, fault);
    await assert.rejects(f.controller.acquire(f.begin()), code(cases[fault]));
    assert.deepEqual(f.source.countsForTest(), { opens: 1, closes: 1, unsettled: 0 });
    assert.equal(f.controller.retainedBytesForTest(), 0);
  }
});

test("S58 cancellation and recreation retain a blocked read until actual close", async t => {
  const f = fixture(t), entered = deferred(), leave = deferred();
  f.source.hooksForTest({ async read(event) { if (event.readIndex === 0) { entered.resolve(); await leave.promise; } } });
  const session = f.begin(), result = assert.rejects(f.controller.acquire(session), code("snapshot_cancelled"));
  await entered.promise; f.controller.cancel(session); f.controller.dispose();
  const recreated = f.open(); fails(() => f.begin(recreated), "snapshot_busy");
  await assert.rejects(recreated.settle(OWNER), code("snapshot_busy"));
  assert.equal(f.source.countsForTest().unsettled, 1);
  leave.resolve(); await result;
  assert.deepEqual(f.source.countsForTest(), { opens: 1, closes: 1, unsettled: 0 });
  f.source.hooksForTest({}); const fresh = f.begin(recreated);
  await assert.rejects(recreated.acquire(session), code("snapshot_session_denied"));
  recreated.cancel(fresh);
});

test("S58 a blocked successful close retains the old worker and allocation after cancellation", async t => {
  const f = fixture(t), entered = deferred(), leave = deferred();
  f.source.hooksForTest({ async close() { entered.resolve(); await leave.promise; } });
  const session = f.begin(), result = assert.rejects(f.controller.acquire(session), code("snapshot_cancelled"));
  await entered.promise; f.controller.cancel(session); const recreated = f.open();
  fails(() => f.begin(recreated), "snapshot_busy"); assert.ok(recreated.retainedBytesForTest() > 0);
  await assert.rejects(recreated.settle(OWNER), code("snapshot_busy"));
  leave.resolve(); await result; assert.equal(recreated.retainedBytesForTest(), 0);
});

test("S58 read errors plus uncertain close stop at one open and retain ownership until explicit settlement", async t => {
  for (const failure of ["io", "runtime", "cancel"] as const) {
    const f = fixture(t); let session!: SourceSnapshotSession;
    f.source.hooksForTest({ read() { if (failure === "cancel") f.controller.cancel(session); else if (failure === "io") throw new Error("generated IO fault"); else throw new TypeError("generated runtime fault"); }, close() { throw new Error("generated unsettled close"); } });
    session = f.begin(); await assert.rejects(f.controller.acquire(session), code("snapshot_close_unsettled"));
    assert.deepEqual(f.source.countsForTest(), { opens: 1, closes: 0, unsettled: 1 });
    f.controller.dispose(); const recreated = f.open();
    fails(() => f.begin(recreated), "snapshot_busy"); assert.equal(recreated.retainedBytesForTest(), DEFINITIONS[0].byteLength);
    await assert.rejects(recreated.settle(OWNER), code("snapshot_close_unsettled"));
    await assert.rejects(recreated.settle("other_owner"), code("snapshot_busy"));
    f.source.hooksForTest({}); assert.equal(await recreated.settle(OWNER), "released");
    assert.equal(recreated.retainedBytesForTest(), 0); assert.equal(f.source.countsForTest().unsettled, 0);
    recreated.cancel(f.begin(recreated));
  }
});

test("S58 a close error after physical closure remains fenced until successful owned retry", async t => {
  const f = fixture(t); f.source.hooksForTest({ afterClose() { throw new Error("generated after-close fault"); } });
  await assert.rejects(f.controller.acquire(f.begin()), code("snapshot_close_unsettled"));
  assert.deepEqual(f.source.countsForTest(), { opens: 1, closes: 1, unsettled: 0 });
  const recreated = f.open(); fails(() => f.begin(recreated), "snapshot_busy");
  assert.equal(await recreated.settle(OWNER), "released"); assert.equal(recreated.retainedBytesForTest(), 0);
});

test("S58 concurrent settlement cannot release a still-closing original owner", async t => {
  const f = fixture(t); f.source.hooksForTest({ close() { throw new Error("first close failure"); } });
  await assert.rejects(f.controller.acquire(f.begin()), code("snapshot_close_unsettled"));
  const entered = deferred(), leave = deferred(), recreated = f.open(); let attempts = 0;
  f.source.hooksForTest({ async close() { attempts++; entered.resolve(); await leave.promise; } });
  const settling = recreated.settle(OWNER); await entered.promise;
  await assert.rejects(f.controller.settle(OWNER), code("snapshot_busy"));
  fails(() => f.begin(recreated), "snapshot_busy"); assert.equal(attempts, 1);
  leave.resolve(); assert.equal(await settling, "released"); assert.equal(recreated.retainedBytesForTest(), 0);
});

test("S58 timeout and lost authority deny publication even if later restored", async t => {
  for (const failure of ["timeout", "withdrawn", "throw"] as const) {
    const f = fixture(t); let unavailable = false;
    f.source.hooksForTest({ read() { if (failure === "timeout") f.clock.at += 1001; else unavailable = true; } });
    const session = f.begin(f.controller, () => { if (unavailable && failure === "throw") throw new Error("authority unavailable"); return !unavailable; });
    await assert.rejects(f.controller.acquire(session), code(failure === "timeout" ? "snapshot_timeout" : "snapshot_cancelled"));
    unavailable = false; f.clock.at = 1;
    await assert.rejects(f.controller.acquire(session), code("snapshot_session_denied"));
    assert.equal(f.controller.retainedBytesForTest(), 0); assert.equal(f.source.countsForTest().opens, 1);
  }
});

test("S58 foreign copied stale and wrong-owner handles cannot describe or release current bytes", async t => {
  const f = fixture(t), session = f.begin(), handle = await f.controller.acquire(session), foreign = f.open();
  for (const invalid of [structuredClone(handle), session]) fails(() => f.controller.describe(OWNER, invalid), "snapshot_handle_denied");
  fails(() => foreign.describe(OWNER, handle), "snapshot_handle_denied");
  fails(() => f.controller.release("other_owner", handle), "snapshot_handle_denied");
  await assert.rejects(foreign.settle(OWNER), code("snapshot_busy"));
  f.controller.release(OWNER, handle); fails(() => f.controller.describe(OWNER, handle), "snapshot_handle_denied");
  const next = await foreign.acquire(f.begin(foreign)); assert.notEqual(next, handle);
  fails(() => f.controller.release(OWNER, handle), "snapshot_handle_denied");
  assert.equal(foreign.verifyContentsForTest(OWNER, next).components.length, 3);
});

test("S58 current authority remains separate from frozen metadata and source changes withdraw use", async t => {
  const f = fixture(t), handle = await f.controller.acquire(f.begin()), historical = f.controller.describe(OWNER, handle);
  f.source.changeForTest(OWNER, f.ref, "bytes");
  fails(() => f.controller.describe(OWNER, handle), "snapshot_inventory_changed");
  assert.equal(historical.productionCapability, false); assert.equal(historical.fidelity, "synthetic_fixture_only");
  assert.ok(Object.isFrozen(historical));
  f.controller.release(OWNER, handle); assert.equal(f.controller.retainedBytesForTest(), 0);
  const g = fixture(t), live = await g.controller.acquire(g.begin()); g.authority.current = false;
  fails(() => g.controller.describe(OWNER, live), "snapshot_cancelled"); g.authority.current = true;
  fails(() => g.controller.describe(OWNER, live), "snapshot_cancelled"); g.controller.release(OWNER, live);
});

test("S58 a snapshot at the exact retained bound blocks a second allocation until release", async t => {
  const definitions = Array.from({ length: 8 }, (_, i) => ({ role: i === 0 ? "primary" as const : "sidecar" as const, seed: 120 + i, byteLength: LIMITS.componentBytes }));
  const f = fixture(t, definitions), handle = await f.controller.acquire(f.begin());
  assert.equal(f.controller.retainedBytesForTest(), LIMITS.retainedBytes);
  fails(() => f.begin(f.open()), "snapshot_busy");
  assert.equal(f.controller.verifyContentsForTest(OWNER, handle).components.length, 8);
  f.controller.release(OWNER, handle); assert.equal(f.controller.retainedBytesForTest(), 0);
  assert.throws(() => f.source.generate(OWNER, [...definitions, definitions[0]]));
  assert.throws(() => f.source.generate(OWNER, [{ role: "primary", seed: 1, byteLength: LIMITS.componentBytes + 1 }]));
});

test("S58 duplicate acquire and cancellation inside an authority callback cannot publish", async t => {
  const f = fixture(t), entered = deferred(), leave = deferred();
  f.source.hooksForTest({ async read() { entered.resolve(); await leave.promise; } });
  const session = f.begin(), waiting = f.controller.acquire(session); await entered.promise;
  await assert.rejects(f.controller.acquire(session), code("snapshot_busy"));
  f.controller.cancel(session); const rejected = assert.rejects(waiting, code("snapshot_cancelled")); leave.resolve(); await rejected;
  assert.equal(f.source.countsForTest().opens, 1);
  const g = fixture(t); let current!: SourceSnapshotSession, checks = 0;
  current = g.begin(g.controller, () => { if (++checks === 2) g.controller.cancel(current); return true; });
  await assert.rejects(g.controller.acquire(current), code("snapshot_cancelled"));
  assert.equal(g.source.countsForTest().opens, 0);
});

test("S58 immutable inventory and snapshot descriptors reject accessor replacement without reads", async t => {
  const f = fixture(t), inventory = f.source.inventoryForTest(OWNER, f.ref); let reads = 0;
  for (const [target, key] of [[inventory, "completeness"], [inventory.source, "versionId"], [inventory.components[0], "sha256"]] as const) {
    assert.throws(() => Object.defineProperty(target, key, { enumerable: true, get() { reads++; return "forged"; } }), TypeError);
  }
  const handle = await f.controller.acquire(f.begin()), description = f.controller.describe(OWNER, handle);
  assert.equal(Reflect.set(description.source, "versionId", "forged"), false);
  assert.equal(Reflect.set(description.components[0], "sha256", "forged"), false);
  assert.equal(reads, 0); assert.deepEqual(f.controller.verifyContentsForTest(OWNER, handle), description);
});

test("S58 same-metadata byte mutation after an earlier component closed denies the whole snapshot", async t => {
  for (const timing of ["later_read", "publication"] as const) {
    const f = fixture(t), metadata = f.source.metadataFingerprintForTest(OWNER, f.ref); let changed = false;
    const mutate = () => {
      assert.equal(f.source.countsForTest().closes >= 1, true);
      f.source.changeForTest(OWNER, f.ref, "silent_bytes"); changed = true;
      assert.equal(f.source.metadataFingerprintForTest(OWNER, f.ref), metadata, "all source/component revisions, lengths and hashes deliberately unchanged");
    };
    f.source.hooksForTest({
      read(event) { if (timing === "later_read" && event.componentIndex === 1 && event.readIndex === 0) mutate(); },
      inspect(stage) { if (timing === "publication" && stage === "before_publication") mutate(); },
    });
    await assert.rejects(f.controller.acquire(f.begin()), code("snapshot_source_bytes_changed"));
    assert.equal(changed, true); assert.equal(f.source.countsForTest().unsettled, 0);
    assert.equal(f.controller.retainedBytesForTest(), 0);
  }
});

test("S58 a deadline promptly rejects a stalled read while keeping its old worker owned", async t => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const f = fixture(t), entered = deferred(), leave = deferred(); let outcome: string | null = null;
  f.source.hooksForTest({ async read(event) { if (event.readIndex === 0) { entered.resolve(); await leave.promise; } } });
  const session = f.begin(f.controller, () => true, 50);
  const observed = f.controller.acquire(session).then(() => { outcome = "published"; }, error => { outcome = error.code; });
  try {
    await entered.promise; f.clock.at += 50; t.mock.timers.tick(50); await nextTurn();
    assert.equal(outcome, "snapshot_timeout"); assert.deepEqual(f.source.countsForTest(), { opens: 1, closes: 0, unsettled: 1 });
    const recreated = f.open(); fails(() => f.begin(recreated), "snapshot_busy");
    await assert.rejects(recreated.settle(OWNER), code("snapshot_busy"));
    assert.equal(recreated.retainedBytesForTest(), DEFINITIONS[0].byteLength);
  } finally { f.source.hooksForTest({}); leave.resolve(); await nextTurn(); await observed; }
  assert.deepEqual(f.source.countsForTest(), { opens: 1, closes: 1, unsettled: 0 });
  assert.equal(f.controller.retainedBytesForTest(), 0);
  const fresh = f.begin(); await assert.rejects(f.controller.acquire(session), code("snapshot_session_denied")); f.controller.cancel(fresh);
});

test("S58 a deadline promptly rejects a stalled close without authorizing early settlement", async t => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const f = fixture(t), entered = deferred(), leave = deferred(); let outcome: string | null = null;
  f.source.hooksForTest({ async close() { entered.resolve(); await leave.promise; } });
  const observed = f.controller.acquire(f.begin(f.controller, () => true, 50)).then(() => { outcome = "published"; }, error => { outcome = error.code; });
  try {
    await entered.promise; f.clock.at += 50; t.mock.timers.tick(50); await nextTurn();
    assert.equal(outcome, "snapshot_timeout"); assert.deepEqual(f.source.countsForTest(), { opens: 1, closes: 0, unsettled: 1 });
    f.controller.dispose(); const recreated = f.open(); fails(() => f.begin(recreated), "snapshot_busy");
    await assert.rejects(recreated.settle(OWNER), code("snapshot_busy"));
  } finally { f.source.hooksForTest({}); leave.resolve(); await nextTurn(); await observed; }
  assert.deepEqual(f.source.countsForTest(), { opens: 1, closes: 1, unsettled: 0 });
  assert.equal(f.controller.retainedBytesForTest(), 0);
});

test("S58 a timeout followed by late close failure retains the original resource for explicit retry", async t => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const f = fixture(t), entered = deferred(), leave = deferred(); let outcome: string | null = null;
  f.source.hooksForTest({ async read() { entered.resolve(); await leave.promise; }, close() { throw new Error("late close remains uncertain"); } });
  const observed = f.controller.acquire(f.begin(f.controller, () => true, 50)).then(() => { outcome = "published"; }, error => { outcome = error.code; });
  try {
    await entered.promise; f.clock.at += 50; t.mock.timers.tick(50); await nextTurn();
    assert.equal(outcome, "snapshot_timeout");
    leave.resolve(); await nextTurn(); await observed;
    assert.deepEqual(f.source.countsForTest(), { opens: 1, closes: 0, unsettled: 1 });
    f.controller.dispose(); const recreated = f.open(); fails(() => f.begin(recreated), "snapshot_busy");
    await assert.rejects(recreated.settle(OWNER), code("snapshot_close_unsettled"));
    f.source.hooksForTest({}); assert.equal(await recreated.settle(OWNER), "released");
    assert.equal(recreated.retainedBytesForTest(), 0);
  } finally { f.source.hooksForTest({}); leave.resolve(); await nextTurn(); await observed; }
});

test("S58 completed acquisition clears its timer instead of later withdrawing a completed handle", async t => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const f = fixture(t), handle = await f.controller.acquire(f.begin(f.controller, () => true, 50));
  // Advance scheduled time independently of the injected source-authority clock.
  // A capture deadline must no longer be armed after capture has completed.
  t.mock.timers.tick(50); await nextTurn();
  assert.equal(f.controller.verifyContentsForTest(OWNER, handle).components.length, 3);
  f.controller.release(OWNER, handle);
  const next = await f.controller.acquire(f.begin(f.controller, () => true, 50));
  t.mock.timers.tick(50); await nextTurn();
  assert.equal(f.controller.verifyContentsForTest(OWNER, next).components.length, 3);
});
