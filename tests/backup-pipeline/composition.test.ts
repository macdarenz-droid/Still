import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import type { TestContext } from "node:test";
import { SourceSnapshotAcquisition, describeSyntheticSnapshotBorrow, readSyntheticSnapshotBorrow, releaseSyntheticSnapshotBorrow } from "../../services/backup-source/acquisition.ts";
import { MutableSyntheticSource } from "../../services/backup-source/synthetic-source.ts";
import { ComposedSyntheticPipeline } from "../../services/backup-pipeline/composed-pipeline.ts";
import { consumeSyntheticTransferredSet, SyntheticTransferWorld } from "../../services/backup-transfer/synthetic-world.ts";
import { TransferRecoveryLedger } from "../../services/backup-transfer/ledger.ts";
import { generatedBytes, SyntheticObjectStore } from "../../services/backup/synthetic-store.ts";

const OWNER = "pipeline_fixture_owner";
const DEFINITIONS = [{ role: "primary" as const, seed: 101, byteLength: 65_553 }, { role: "motion" as const, seed: 202, byteLength: 65_553 }, { role: "sidecar" as const, seed: 303, byteLength: 113 }];
function hasCode(error: unknown, code: string): boolean { return error !== null && typeof error === "object" && Reflect.get(error, "code") === code; }
function fails(action: () => unknown, code: string): void { assert.throws(action, error => error !== null && typeof error === "object" && Reflect.get(error, "code") === code); }
async function fixture(t: TestContext) {
  const source = new MutableSyntheticSource(), ref = source.generate(OWNER, DEFINITIONS), clock = { at: 10_000 };
  const snapshots = new SourceSnapshotAcquisition({ source, now: () => clock.at });
  const handle = await snapshots.acquire(snapshots.begin(OWNER, { source: ref, timeoutMs: 30_000 }, () => true));
  const pipeline = ComposedSyntheticPipeline.fromSnapshot(OWNER, snapshots, handle, { now: () => clock.at });
  const ids = pipeline.componentBindings().map(item => item.source.componentId);
  let cleaned = false;
  const cleanup = () => {
    if (cleaned) return;
    try { pipeline.reopen(); } catch { /* already open */ }
    for (const id of ids) {
      const record = pipeline.state(id);
      if (record.pending) {
        try { pipeline.world.settle(record.pending.requestId, "not_applied"); } catch { /* already physically settled */ }
        pipeline.world.reveal(record.pending.requestId); pipeline.reconcile(id);
      }
    }
    pipeline.disposeForTest(); snapshots.dispose(); cleaned = true;
  };
  t.after(cleanup);
  const upload = async () => {
    for (const id of ids) {
      if (!pipeline.state(id).object) await pipeline.complete(id, "create");
      while (pipeline.state(id).acknowledgedOffset < pipeline.state(id).binding.totalBytes) {
        const current = pipeline.state(id); await pipeline.complete(id, "append", Math.min(65_536, current.binding.totalBytes - current.acknowledgedOffset));
      }
      if (pipeline.state(id).state !== "uploaded_unverified") await pipeline.complete(id, "finalize");
    }
  };
  return { source, ref, clock, snapshots, handle, pipeline, ids, upload, cleanup };
}

test("S65 one immutable source cannot create a second coordinator or world while ready uncertain or completed", async t => {
  const f = await fixture(t);
  const denySecond = () => {
    const counts = f.pipeline.world.countsForTest(); let worldAccesses = 0, duplicate: ComposedSyntheticPipeline | undefined;
    try {
      fails(() => { duplicate = ComposedSyntheticPipeline.fromSnapshot(OWNER, f.snapshots, f.handle); }, "pipeline_source_already_tracked");
      fails(() => {
        duplicate = ComposedSyntheticPipeline.fromSnapshot(OWNER, f.snapshots, f.handle, {
          get world() { worldAccesses++; return new SyntheticTransferWorld(); }, now: () => f.clock.at,
        });
      }, "pipeline_source_already_tracked");
      assert.equal(worldAccesses, 0, "deny before evaluating or allocating a second world");
      assert.deepEqual(f.pipeline.world.countsForTest(), counts);
    } finally { duplicate?.disposeForTest(); }
  };
  denySecond();
  const uncertain = await f.pipeline.complete(f.ids[0], "create", 0, "unknown");
  assert.equal(uncertain.state, "uncertain"); denySecond();
  assert.deepEqual(f.pipeline.world.countsForTest(), { requests: 1, objects: 1 });
  f.pipeline.world.reveal(uncertain.pending!.requestId); f.pipeline.reconcile(f.ids[0]);
  await f.upload(); denySecond();
});

test("S65 completed or cancelled source reservation survives snapshot release disposal and same-source reacquisition", async t => {
  for (const state of ["created", "cancelled"] as const) {
    const f = await fixture(t), original = f.snapshots.describe(OWNER, f.handle);
    if (state === "created") await f.pipeline.complete(f.ids[0], "create");
    else f.pipeline.cancel();
    const counts = f.pipeline.world.countsForTest(); f.cleanup();
    const snapshots = new SourceSnapshotAcquisition({ source: f.source, now: () => f.clock.at });
    const handle = await snapshots.acquire(snapshots.begin(OWNER, { source: f.ref, timeoutMs: 30_000 }, () => true));
    let duplicate: ComposedSyntheticPipeline | undefined, worldAccesses = 0;
    try {
      const accepted = snapshots.describe(OWNER, handle);
      assert.notEqual(accepted.snapshotId, original.snapshotId);
      assert.deepEqual(accepted.source, original.source); assert.deepEqual(accepted.components, original.components);
      fails(() => { duplicate = ComposedSyntheticPipeline.fromSnapshot(OWNER, snapshots, handle); }, "pipeline_source_already_tracked");
      fails(() => {
        duplicate = ComposedSyntheticPipeline.fromSnapshot(OWNER, snapshots, handle, {
          get world() { worldAccesses++; return new SyntheticTransferWorld(); }, now: () => f.clock.at,
        });
      }, "pipeline_source_already_tracked");
      assert.equal(worldAccesses, 0, "no second world or journal is allocated after handle recreation");
      assert.deepEqual(f.pipeline.world.countsForTest(), counts);
    } finally { if (duplicate) duplicate.disposeForTest(); else snapshots.release(OWNER, handle); snapshots.dispose(); }
  }
});

test("S65 any overlapping immutable component denies replacement after inventory add remove reorder or sibling revision change", async t => {
  for (const change of ["add", "remove", "reorder", "component"] as const) {
    const f = await fixture(t), original = f.snapshots.describe(OWNER, f.handle);
    await f.pipeline.complete(f.ids[0], "create"); const counts = f.pipeline.world.countsForTest(); f.cleanup();
    f.source.changeForTest(OWNER, f.ref, change);
    const snapshots = new SourceSnapshotAcquisition({ source: f.source, now: () => f.clock.at });
    const handle = await snapshots.acquire(snapshots.begin(OWNER, { source: f.ref, timeoutMs: 30_000 }, () => true));
    let duplicate: ComposedSyntheticPipeline | undefined, worldAccesses = 0;
    try {
      const accepted = snapshots.describe(OWNER, handle);
      assert.deepEqual(accepted.source, original.source, "fixture keeps the same source and component-set revisions");
      assert.ok(accepted.components.some(item => original.components.some(old => old.componentId === item.componentId && old.componentRevision === item.componentRevision)));
      fails(() => {
        duplicate = ComposedSyntheticPipeline.fromSnapshot(OWNER, snapshots, handle, {
          get world() { worldAccesses++; return new SyntheticTransferWorld(); }, now: () => f.clock.at,
        });
      }, "pipeline_source_already_tracked");
      assert.equal(worldAccesses, 0); assert.deepEqual(f.pipeline.world.countsForTest(), counts);
    } finally { if (duplicate) duplicate.disposeForTest(); else snapshots.release(OWNER, handle); snapshots.dispose(); }
  }
});

test("S65 a fully cleaned constructor failure releases only provisional pre-effect claims", async () => {
  for (const stage of ["world", "binding"] as const) {
    const source = new MutableSyntheticSource(), ref = source.generate(OWNER, DEFINITIONS);
    const snapshots = new SourceSnapshotAcquisition({ source, now: () => 10_000 });
    const handle = await snapshots.acquire(snapshots.begin(OWNER, { source: ref, timeoutMs: 30_000 }, () => true));
    const world = new SyntheticTransferWorld(), bind = world.bindSnapshotForSyntheticPipeline.bind(world);
    world.bindSnapshotForSyntheticPipeline = (...args) => { bind(...args); throw new Error("injected binding failure"); };
    let pipeline: ComposedSyntheticPipeline | undefined;
    try {
      assert.throws(() => ComposedSyntheticPipeline.fromSnapshot(OWNER, snapshots, handle, {
        get world() { if (stage === "world") throw new Error("injected world failure"); return world; }, now: () => 10_000,
      }), new RegExp(`injected ${stage} failure`));
      assert.deepEqual(world.countsForTest(), { requests: 0, objects: 0 });
      pipeline = ComposedSyntheticPipeline.fromSnapshot(OWNER, snapshots, handle, { now: () => 10_000 });
      assert.deepEqual(pipeline.world.countsForTest(), { requests: 0, objects: 0 });
      fails(() => ComposedSyntheticPipeline.fromSnapshot(OWNER, snapshots, handle), "pipeline_source_already_tracked");
    } finally { if (pipeline) pipeline.disposeForTest(); else snapshots.release(OWNER, handle); snapshots.dispose(); }
    assert.equal(snapshots.retainedBytesForTest(), 0);
  }
});

test("S65 exact acquired primary motion and sidecar bytes survive async transfer and CP13 restore", async t => {
  const f = await fixture(t), description = f.snapshots.describe(OWNER, f.handle), bindings = f.pipeline.componentBindings();
  assert.equal(new Set(bindings.map(item => item.source.sourceId)).size, 1);
  assert.deepEqual(bindings.map(item => ({ ...item.source, componentId: undefined, componentRevision: undefined })), bindings.map(() => ({ ...f.ref, componentId: undefined, componentRevision: undefined })));
  for (const binding of bindings) assert.equal(f.pipeline.world.reason(OWNER, binding), null);
  await f.upload();
  const before = f.pipeline.world.countsForTest(), archive = f.pipeline.verify();
  assert.deepEqual(archive.manifest.source, description.source); assert.deepEqual(archive.manifest.components, description.components);
  assert.deepEqual(archive.objects.map(item => item.objectId), f.ids.map(id => f.pipeline.state(id).object!.objectId));
  assert.equal(archive.productionCapability, false); assert.equal(f.pipeline.productionCapability, false);
  fails(() => f.pipeline.restore("too_early"), "pipeline_snapshot_release_required");
  f.source.removeForTest(OWNER, f.ref); f.pipeline.releaseSnapshot();
  assert.equal(f.snapshots.retainedBytesForTest(), 0);
  fails(() => f.snapshots.describe(OWNER, f.handle), "snapshot_handle_denied");
  const restored = f.pipeline.restore("source_absent_restore");
  assert.deepEqual(restored.manifest.components.map(item => item.role), ["primary", "motion", "sidecar"]);
  for (const [i, component] of restored.manifest.components.entries()) {
    const actual = f.pipeline.readbackStore.readRestoredForTest(OWNER, "source_absent_restore", component.componentId);
    assert.deepEqual(actual, generatedBytes(DEFINITIONS[i].seed, DEFINITIONS[i].byteLength));
    assert.equal(createHash("sha256").update(actual).digest("hex"), component.sha256);
  }
  assert.deepEqual(f.pipeline.world.countsForTest(), before, "verification/readback/restore do not issue another modeled upload");
});

test("S65 ambiguous create append and finalize reconcile the original physical effect", async t => {
  for (const phase of ["create", "append", "finalize"] as const) {
    const f = await fixture(t), id = f.ids[0];
    if (phase !== "create") await f.pipeline.complete(id, "create");
    if (phase === "finalize") { await f.pipeline.complete(id, "append", 65_536); await f.pipeline.complete(id, "append", 17); }
    const record = await f.pipeline.complete(id, phase, phase === "append" ? 100 : 0, "unknown");
    assert.equal(record.state, "uncertain"); assert.ok(record.pending);
    const counts = f.pipeline.world.countsForTest(), operationId = record.pending.operationId;
    fails(() => f.pipeline.dispatch(id, phase, phase === "append" ? 100 : 0), "transfer_reconciliation_required");
    fails(() => f.pipeline.releaseSnapshot(), "pipeline_reconciliation_required");
    assert.ok(f.snapshots.retainedBytesForTest() > 0);
    assert.equal(f.pipeline.reconcile(id).state, "uncertain");
    f.pipeline.world.reveal(record.pending.requestId);
    const resolved = f.pipeline.reconcile(id);
    assert.equal(resolved.pending, null); assert.equal(resolved.lastObservation!.requestId, record.pending.requestId);
    assert.equal(resolved.state, phase === "finalize" ? "uploaded_unverified" : "ready");
    assert.equal(record.pending.operationId, operationId); assert.deepEqual(f.pipeline.world.countsForTest(), counts);
    f.cleanup();
  }
});

test("S65 forged copied foreign handles and descriptors cannot borrow or register byte authority", async t => {
  const f = await fixture(t), description = f.snapshots.describe(OWNER, f.handle), foreign = new SourceSnapshotAcquisition();
  for (const value of [structuredClone(f.handle), description, { scope: "synthetic_source_snapshot_v1", testOnly: true }]) {
    fails(() => f.snapshots.borrowForSyntheticPipeline(OWNER, value as never), "snapshot_handle_denied");
    fails(() => ComposedSyntheticPipeline.fromSnapshot(OWNER, f.snapshots, value as never), "snapshot_handle_denied");
  }
  fails(() => foreign.borrowForSyntheticPipeline(OWNER, f.handle), "snapshot_handle_denied");
  fails(() => f.snapshots.borrowForSyntheticPipeline("wrong_owner", f.handle), "snapshot_handle_denied");
  const borrow = f.snapshots.borrowForSyntheticPipeline(OWNER, f.handle);
  try {
    const copied = structuredClone(borrow);
    fails(() => readSyntheticSnapshotBorrow(OWNER, copied, f.ids[0], 0, 10), "snapshot_borrow_denied");
    fails(() => new SyntheticTransferWorld().bindSnapshotForSyntheticPipeline(OWNER, copied), "snapshot_borrow_denied");
    const payload = readSyntheticSnapshotBorrow(OWNER, borrow, f.ids[0], 0, 10); payload.fill(0);
    assert.deepEqual(readSyntheticSnapshotBorrow(OWNER, borrow, f.ids[0], 0, 10), generatedBytes(101, 65_553).slice(0, 10));
  } finally { releaseSyntheticSnapshotBorrow(OWNER, borrow); }
});

test("S65 payload input is derived solely from the genuine snapshot and dispatch replay never retransmits", async t => {
  const f = await fixture(t), id = f.ids[0]; await f.pipeline.complete(id, "create");
  const before = f.pipeline.world.countsForTest();
  assert.throws(() => f.pipeline.dispatch(id, "append", new Uint8Array([1, 2]) as never));
  assert.deepEqual(f.pipeline.world.countsForTest(), before);
  const issued = f.pipeline.dispatch(id, "append", 100, "append_once");
  const duplicate = f.pipeline.dispatch(id, "append", 100, "append_once");
  assert.equal(duplicate.replayed, true); assert.equal(duplicate.dispatch, null);
  f.pipeline.world.settle(issued.attempt.pending!.requestId, "applied");
  const acknowledged = await f.pipeline.acknowledge(issued.attempt.pending!.requestId);
  assert.deepEqual(await f.pipeline.acknowledge(issued.attempt.pending!.requestId), acknowledged);
  assert.equal(f.pipeline.world.countsForTest().requests, before.requests + 1);
});

test("S65 blocked and uncertain work retains a snapshot even after the caller releases its handle", async t => {
  const f = await fixture(t), id = f.ids[0]; await f.pipeline.complete(id, "create");
  const issued = f.pipeline.dispatch(id, "append", 100), requestId = issued.attempt.pending!.requestId;
  f.snapshots.release(OWNER, f.handle);
  const recreated = new SourceSnapshotAcquisition({ source: f.source });
  fails(() => recreated.begin(OWNER, { source: f.ref, timeoutMs: 1000 }, () => true), "snapshot_busy");
  await assert.rejects(recreated.settle(OWNER), error => hasCode(error, "snapshot_busy"));
  assert.ok(f.snapshots.retainedBytesForTest() > 0);
  f.pipeline.world.settle(requestId, "applied", "unknown");
  await assert.rejects(f.pipeline.acknowledge(requestId));
  assert.equal(f.pipeline.reconcile(id).state, "uncertain");
  fails(() => f.pipeline.releaseSnapshot(), "pipeline_reconciliation_required");
  assert.ok(f.snapshots.retainedBytesForTest() > 0);
  f.pipeline.world.reveal(requestId); assert.equal(f.pipeline.reconcile(id).pending, null);
  f.pipeline.releaseSnapshot(); assert.equal(f.snapshots.retainedBytesForTest(), 0);
});

test("S65 correct offsets cannot verify altered or swapped actual provider bytes", async t => {
  for (const damage of ["bytes", "swap"] as const) {
    const f = await fixture(t); await f.upload();
    const first = f.pipeline.state(f.ids[0]), second = f.pipeline.state(f.ids[1]), counts = f.pipeline.world.countsForTest();
    assert.equal(first.acknowledgedOffset, DEFINITIONS[0].byteLength); assert.equal(second.acknowledgedOffset, DEFINITIONS[1].byteLength);
    if (damage === "bytes") f.pipeline.world.corruptObjectForTest(first.object!.objectId, "bytes");
    else f.pipeline.world.swapObjectBytesForTest(first.object!.objectId, second.object!.objectId);
    assert.equal(f.pipeline.state(f.ids[0]).state, "uploaded_unverified");
    fails(() => f.pipeline.verify(), "backup_readback_bytes_mismatch");
    fails(() => f.pipeline.restore("wrong_bytes"), "pipeline_not_verified");
    assert.deepEqual(f.pipeline.world.countsForTest(), counts); f.cleanup();
  }
});

test("S65 copied readbacks and caller-selected partial or altered manifests cannot grant verification", async t => {
  const f = await fixture(t); await f.upload(); const receipt = f.pipeline.readbackForTest(), store = new SyntheticObjectStore();
  t.after(() => { store.settleForTest(); store.disposeForTest(); });
  for (const forged of [structuredClone(receipt), { ...receipt, components: [f.ids[0]] }, { ...receipt, productionCapability: true }]) {
    fails(() => store.importTransferredForSyntheticPipeline(OWNER, forged as never), "transfer_readback_authority_denied");
  }
  fails(() => store.importTransferredForSyntheticPipeline("wrong_owner", receipt), "transfer_readback_authority_denied");
  const archive = store.importTransferredForSyntheticPipeline(OWNER, receipt);
  fails(() => consumeSyntheticTransferredSet(OWNER, receipt, () => true), "transfer_readback_authority_denied");
  for (const change of ["missing", "extra", "role", "object", "revision", "source"] as const) {
    const forged = structuredClone(archive);
    if (change === "missing") { forged.manifest.components.pop(); forged.objects.pop(); }
    else if (change === "extra") { forged.manifest.components.push(forged.manifest.components[0]); forged.objects.push(forged.objects[0]); }
    else if (change === "role") { forged.manifest.components[0].role = "motion"; forged.manifest.components[1].role = "primary"; }
    else if (change === "object") [forged.objects[0].objectId, forged.objects[1].objectId] = [forged.objects[1].objectId, forged.objects[0].objectId];
    else if (change === "revision") forged.objects[0].objectRevision++;
    else forged.manifest.source.componentSetRevision = "unrelated_inventory";
    assert.throws(() => store.verify(OWNER, forged));
  }
  assert.equal(store.verify(OWNER, archive).length, 3);
});

test("S65 a readback minted before source mutation cannot be consumed later", async t => {
  const f = await fixture(t); await f.upload(); const receipt = f.pipeline.readbackForTest(), store = new SyntheticObjectStore();
  t.after(() => store.disposeForTest());
  f.source.changeForTest(OWNER, f.ref, "component");
  fails(() => store.importTransferredForSyntheticPipeline(OWNER, receipt), "snapshot_inventory_changed");
  fails(() => f.pipeline.verify(), "pipeline_components_unverified");
});

test("S65 source mutation during readback import cannot detach current-source checking or verify a partial set", async t => {
  const f = await fixture(t); await f.upload(); const receipt = f.pipeline.readbackForTest(), store = new SyntheticObjectStore();
  t.after(() => { store.settleForTest(); store.disposeForTest(); });
  const original = (Reflect.get(store, "writeNew") as (path: string, bytes: Uint8Array) => void).bind(store); let writes = 0;
  Reflect.set(store, "writeNew", (path: string, bytes: Uint8Array) => { original(path, bytes); if (++writes === 1) f.source.changeForTest(OWNER, f.ref, "component"); });
  fails(() => store.importTransferredForSyntheticPipeline(OWNER, receipt), "snapshot_inventory_changed");
  fails(() => consumeSyntheticTransferredSet(OWNER, receipt, () => true), "transfer_readback_authority_denied");
  assert.equal(writes, 1);
});

test("S65 a stale world revision byte content or missing object blocks restore after source removal", async t => {
  for (const change of ["bytes", "revision", "missing"] as const) {
    const f = await fixture(t); await f.upload(); const archive = f.pipeline.verify();
    f.source.removeForTest(OWNER, f.ref); f.pipeline.releaseSnapshot();
    f.pipeline.world.corruptObjectForTest(archive.objects[0].objectId, change);
    fails(() => f.pipeline.restore(`stale_${change}`), "transfer_readback_stale");
    f.cleanup();
  }
});

test("S65 the whole-world guard runs after the final readback read", async t => {
  const f = await fixture(t); await f.upload(); const archive = f.pipeline.verify();
  const original = (Reflect.get(f.pipeline.readbackStore, "read") as (path: string) => Uint8Array).bind(f.pipeline.readbackStore); let reads = 0;
  Reflect.set(f.pipeline.readbackStore, "read", (path: string) => { const bytes = original(path); if (++reads === 6) f.pipeline.world.corruptObjectForTest(archive.objects[0].objectId, "bytes"); return bytes; });
  fails(() => f.pipeline.readbackStore.verify(OWNER, archive), "transfer_readback_stale");
  assert.equal(reads, 6);
});

test("S65 readback disk-full and failed-close errors cannot publish a verified archive", async t => {
  for (const fault of ["disk_full", "write_close"] as const) {
    const f = await fixture(t); await f.upload(); const store = f.pipeline.readbackStore;
    const original = (Reflect.get(store, "writeNew") as (path: string, bytes: Uint8Array) => void).bind(store); let writes = 0;
    Reflect.set(store, "writeNew", (path: string, bytes: Uint8Array) => { if (++writes === 3) store.injectFailureForTest(fault); original(path, bytes); });
    fails(() => f.pipeline.verify(), fault === "disk_full" ? "backup_disk_full" : "backup_close_failed");
    fails(() => f.pipeline.restore("import_failed"), "pipeline_not_verified");
    assert.equal(f.pipeline.world.countsForTest().objects, 3);
    if (fault === "write_close") { assert.equal(store.unsettledHandlesForTest(), 1); store.settleForTest(); }
    f.cleanup();
  }
});

test("S65 cancellation retains an original physical request and prevents a late current backup claim", async t => {
  const f = await fixture(t), id = f.ids[0], issued = f.pipeline.dispatch(id, "create"), requestId = issued.attempt.pending!.requestId;
  const borrow = f.snapshots.borrowForSyntheticPipeline(OWNER, f.handle);
  f.pipeline.cancel();
  fails(() => readSyntheticSnapshotBorrow(OWNER, borrow, id, 0, 10), "snapshot_cancelled");
  releaseSyntheticSnapshotBorrow(OWNER, borrow);
  fails(() => f.pipeline.releaseSnapshot(), "pipeline_reconciliation_required");
  fails(() => f.pipeline.disposeForTest(), "pipeline_reconciliation_required");
  assert.ok(f.snapshots.retainedBytesForTest() > 0);
  f.pipeline.world.settle(requestId, "applied"); await assert.rejects(f.pipeline.acknowledge(requestId));
  const settled = f.pipeline.reconcile(id);
  assert.equal(settled.state, "cancelled"); assert.equal(settled.object, null); assert.ok(settled.lastObservation!.object);
  fails(() => f.pipeline.verify(), "pipeline_withdrawn");
  assert.equal(f.pipeline.world.countsForTest().objects, 1);
  f.pipeline.releaseSnapshot(); assert.equal(f.snapshots.retainedBytesForTest(), 0);
});

test("S65 new account epochs and disconnects cannot retarget late captured payloads", async t => {
  for (const change of ["reauthorize", "disconnect"] as const) {
    const f = await fixture(t), id = f.ids[0]; await f.pipeline.complete(id, "create");
    const issued = f.pipeline.dispatch(id, "append", 100), requestId = issued.attempt.pending!.requestId, epoch = issued.attempt.binding.connectionEpoch;
    f.pipeline.world[change](OWNER); f.pipeline.world.settle(requestId, "applied");
    await assert.rejects(f.pipeline.acknowledge(requestId));
    const settled = f.pipeline.reconcile(id);
    assert.equal(settled.state, "fenced"); assert.equal(settled.binding.connectionEpoch, epoch);
    assert.equal(settled.acknowledgedOffset, 0); assert.equal(settled.lastObservation!.offset, 100);
    fails(() => f.pipeline.dispatch(id, "append", 100), "transfer_reconciliation_required");
    fails(() => f.pipeline.verify(), "pipeline_components_unverified");
    assert.equal(f.pipeline.world.countsForTest().objects, 1); f.cleanup();
  }
});

test("S65 journal reopen rejects the old callback then reconciles and resumes the same actual object", async t => {
  const f = await fixture(t), id = f.ids[0]; await f.pipeline.complete(id, "create");
  const issued = f.pipeline.dispatch(id, "append", 100), requestId = issued.attempt.pending!.requestId, objectId = issued.attempt.object!.objectId;
  f.pipeline.closeForRestart(); assert.ok(f.snapshots.retainedBytesForTest() > 0); f.pipeline.reopen();
  f.pipeline.world.settle(requestId, "applied");
  await assert.rejects(f.pipeline.acknowledge(requestId), error => hasCode(error, "transfer_late_response_reconcile_required"));
  const reconciled = f.pipeline.reconcile(id); assert.equal(reconciled.object!.objectId, objectId); assert.equal(reconciled.acknowledgedOffset, 100);
  await f.upload(); const archive = f.pipeline.verify();
  assert.equal(archive.objects[0].objectId, objectId); assert.equal(f.pipeline.world.countsForTest().objects, 3);
});

test("S65 source component-set and inventory changes after acquisition deny any new transfer", async t => {
  for (const change of ["component", "set", "source", "remove", "add", "reorder", "silent_bytes"] as const) {
    const f = await fixture(t); f.source.changeForTest(OWNER, f.ref, change);
    fails(() => f.pipeline.dispatch(f.ids[0], "create"), "transfer_reconciliation_required");
    assert.deepEqual(f.pipeline.world.countsForTest(), { requests: 0, objects: 0 });
    f.cleanup();
  }
});

test("S65 retention never grants reads after release cancellation or controller disposal", async t => {
  const f = await fixture(t), borrow = f.snapshots.borrowForSyntheticPipeline(OWNER, f.handle);
  f.snapshots.dispose(); assert.ok(f.snapshots.retainedBytesForTest() > 0);
  fails(() => describeSyntheticSnapshotBorrow(OWNER, borrow), "snapshot_cancelled");
  fails(() => readSyntheticSnapshotBorrow(OWNER, borrow, f.ids[0], 0, 10), "snapshot_cancelled");
  releaseSyntheticSnapshotBorrow(OWNER, borrow); assert.ok(f.snapshots.retainedBytesForTest() > 0, "the coordinator still owns its separate retained borrow");
  f.pipeline.cancel(); f.pipeline.releaseSnapshot(); assert.equal(f.snapshots.retainedBytesForTest(), 0);
  fails(() => readSyntheticSnapshotBorrow(OWNER, borrow, f.ids[0], 0, 10), "snapshot_borrow_denied");
});

test("S65 the physical world owns its child lease after the original borrow is released", async t => {
  const source = new MutableSyntheticSource(), ref = source.generate(OWNER, DEFINITIONS), snapshots = new SourceSnapshotAcquisition({ source, now: () => 10_000 });
  const handle = await snapshots.acquire(snapshots.begin(OWNER, { source: ref, timeoutMs: 30_000 }, () => true));
  const parent = snapshots.borrowForSyntheticPipeline(OWNER, handle), world = new SyntheticTransferWorld(), bindings = world.bindSnapshotForSyntheticPipeline(OWNER, parent);
  const journal = new TransferRecoveryLedger(":memory:", { world, now: () => 10_000 });
  const record = journal.create(OWNER, { idempotencyKey: "world_child", binding: bindings[0] }).attempt;
  const issued = journal.dispatch(OWNER, record.id, { idempotencyKey: "physical_create", expectedRevision: record.revision, kind: "create", byteLength: 0, leaseMs: 1000 });
  const waiting = world.launch(issued.dispatch!), requestId = issued.attempt.pending!.requestId;
  try {
    snapshots.release(OWNER, handle); releaseSyntheticSnapshotBorrow(OWNER, parent);
    assert.ok(snapshots.retainedBytesForTest() > 0);
    const recreated = new SourceSnapshotAcquisition({ source });
    fails(() => recreated.begin(OWNER, { source: ref, timeoutMs: 1000 }, () => true), "snapshot_busy");
    fails(() => readSyntheticSnapshotBorrow(OWNER, parent, bindings[0].source.componentId, 0, 10), "snapshot_borrow_denied");
  } finally {
    world.settle(requestId, "not_applied"); await waiting;
    const current = journal.get(OWNER, record.id); journal.reconcile(OWNER, record.id, { idempotencyKey: "settle_child", expectedRevision: current.revision });
    journal.close(); snapshots.dispose();
  }
  assert.equal(snapshots.retainedBytesForTest(), 0);
});
