/** Generated-fixture ports only. No file/media ingestion or production adapter. */
import { createHash, randomUUID } from "node:crypto";
import { constants, closeSync, existsSync, fstatSync, fsyncSync, lstatSync, mkdirSync, mkdtempSync, openSync, readFileSync, readSync, readdirSync, renameSync, rmSync, writeSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { backupAssert, backupDigest, backupId, backupInteger, backupObject, parseBackupManifest, parsePortableBackupArchive, parseSyntheticEvidence, BackupRecoveryError } from "../../packages/contracts/src/backup-recovery.ts";
import type { BackupComponent, BackupManifest, BackupObject, BackupSourceRef, ComponentProgress, ComponentRole, PortableBackupArchive, SyntheticInputEvidence } from "../../packages/contracts/src/backup-recovery.ts";
import { consumeSyntheticTransferredSet } from "../backup-transfer/synthetic-world.ts";
import type { SyntheticTransferredSet } from "../backup-transfer/synthetic-world.ts";

export function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value !== null && typeof value === "object") return `{${Object.entries(value).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([key, item]) => `${JSON.stringify(key)}:${canonical(item)}`).join(",")}}`;
  return JSON.stringify(value);
}
export function digest(value: Uint8Array | string): string { return createHash("sha256").update(value).digest("hex"); }
export const EMPTY_SHA256 = digest(new Uint8Array());
const evidenceProvenance = new WeakMap<object, string>();
const chunks = new WeakMap<object, { bytes: Uint8Array; ownerId: string }>();
export function isGeneratedEvidence(value: unknown): boolean {
  return value !== null && typeof value === "object" && evidenceProvenance.get(value) === canonical(value);
}
export interface SyntheticChunk {
  readonly componentId: string;
  readonly offset: number;
  readonly byteLength: number;
  readonly sha256: string;
  readonly prefixSha256: string;
  readonly evidenceFingerprint: string;
}
export interface TrustedSyntheticSourceVerifier {
  inspect(ownerId: string, source: BackupSourceRef): unknown;
  capture(ownerId: string, source: BackupSourceRef, componentId: string, offset: number, maxBytes: number): SyntheticChunk;
}
export function isGeneratedChunk(value: unknown): value is SyntheticChunk {
  return value !== null && typeof value === "object" && chunks.has(value);
}
export function generatedBytes(seed: number, byteLength: number): Uint8Array {
  backupInteger(seed, 1, 0xffff_ffff); backupInteger(byteLength, 1, 1_048_576);
  const bytes = new Uint8Array(byteLength);
  let state = seed >>> 0;
  for (let i = 0; i < bytes.length; i++) { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; bytes[i] = state & 255; }
  return bytes;
}

/** Only accepts generator parameters, never caller-provided bytes, paths or identifiers. */
export class SyntheticSources implements TrustedSyntheticSourceVerifier {
  private readonly fixtures = new Map<string, { evidence: SyntheticInputEvidence; bytes: Map<string, Uint8Array> }>();
  private closeFailure = false;
  private unsettled = false;

  generate(ownerId: string, components: { role: ComponentRole; seed: number; byteLength: number }[]): BackupSourceRef {
    backupId(ownerId);
    backupAssert(components.length >= 1 && components.length <= 8);
    const bytes = new Map<string, Uint8Array>();
    const definitions = components.map(component => {
      const data = generatedBytes(component.seed, component.byteLength), componentId = randomUUID();
      bytes.set(componentId, data);
      return { componentId, componentRevision: randomUUID(), role: component.role, byteLength: data.length, sha256: digest(data) };
    });
    const source = { sourceId: randomUUID(), versionId: randomUUID(), sourceRevision: randomUUID(), componentSetRevision: randomUUID() };
    const evidence = parseSyntheticEvidence({ schemaVersion: 1, testOnly: true, ownerId, source, representation: "generated_synthetic_bytes", fidelity: "synthetic_fixture_only", components: definitions });
    this.fixtures.set(source.sourceId, { evidence, bytes });
    return structuredClone(source);
  }
  inspect(ownerId: string, source: BackupSourceRef): unknown {
    backupAssert(!this.unsettled, "backup_close_unsettled");
    const fixture = this.fixtures.get(source.sourceId);
    if (!fixture || fixture.evidence.ownerId !== ownerId) return null;
    const evidence = structuredClone(fixture.evidence);
    evidenceProvenance.set(evidence, canonical(evidence));
    return evidence;
  }
  capture(ownerId: string, source: BackupSourceRef, componentId: string, offset: number, maxBytes: number): SyntheticChunk {
    const evidence = this.inspect(ownerId, source);
    backupAssert(isGeneratedEvidence(evidence), "backup_source_unavailable");
    const parsed = parseSyntheticEvidence(evidence);
    backupAssert(canonical(parsed.source) === canonical(source), "backup_source_changed");
    const data = this.fixtures.get(source.sourceId)!.bytes.get(componentId);
    backupAssert(data && parsed.components.some(item => item.componentId === componentId), "backup_component_changed");
    backupInteger(offset, 0, data.length - 1); backupInteger(maxBytes, 1, 65_536);
    const payload = data.slice(offset, offset + maxBytes);
    if (this.closeFailure) { this.closeFailure = false; this.unsettled = true; throw new BackupRecoveryError("backup_close_failed"); }
    const result = Object.freeze({ componentId, offset, byteLength: payload.length, sha256: digest(payload), prefixSha256: digest(data.subarray(0, offset + payload.length)), evidenceFingerprint: digest(canonical(parsed)) });
    chunks.set(result, { bytes: payload, ownerId });
    return result;
  }
  failNextCloseForTest(): void { this.closeFailure = true; }
  settleForTest(): void { this.unsettled = false; }
  removeForTest(source: BackupSourceRef): void { this.fixtures.delete(source.sourceId); }
  changeForTest(source: BackupSourceRef, change: "source" | "components" | "component"): void {
    const fixture = this.fixtures.get(source.sourceId); backupAssert(fixture);
    if (change === "source") fixture.evidence.source.sourceRevision = randomUUID();
    else if (change === "components") fixture.evidence.source.componentSetRevision = randomUUID();
    else fixture.evidence.components[0].componentRevision = randomUUID();
  }
}

type StoreFault = "disk_full" | "write_close" | "read_close" | "before_metadata_replace" | "after_object_commit" | "after_restore_commit";
type ObjectInfo = BackupObject & { ownerId: string; destinationId: string; manifestFingerprint: string };
interface Root { path: string; unsettled: Set<number>; fault: StoreFault | null; readbackGuard?: () => void }
const ownedRoots = new WeakSet<object>();

/** Private temporary generated-byte store. Receipts remain fixture-only, including after reopen. */
export class SyntheticObjectStore {
  private readonly root: Root;
  constructor(root?: Root) {
    if (root !== undefined) { backupAssert(ownedRoots.has(root), "backup_fixture_root_denied"); this.root = root; }
    else {
      this.root = { path: mkdtempSync(join(tmpdir(), "still-synthetic-backup-")), unsettled: new Set(), fault: null };
      ownedRoots.add(this.root);
      mkdirSync(join(this.root.path, "objects")); mkdirSync(join(this.root.path, "restores"));
    }
  }
  reopen(): SyntheticObjectStore { return new SyntheticObjectStore(this.root); }
  injectFailureForTest(fault: StoreFault): void { this.root.fault = fault; }
  unsettledHandlesForTest(): number { return this.root.unsettled.size; }
  settleForTest(): void {
    for (const fd of [...this.root.unsettled]) { closeSync(fd); this.root.unsettled.delete(fd); }
  }
  private check(checkReadback = true): void { backupAssert(this.root.unsettled.size === 0, "backup_close_unsettled"); if (checkReadback) this.root.readbackGuard?.(); }
  /** CP18 imports an attested actual object readback, never another upload. */
  importTransferredForSyntheticPipeline(ownerId: string, receipt: SyntheticTransferredSet): PortableBackupArchive {
    this.check(); backupAssert(this.root.readbackGuard === undefined && readdirSync(join(this.root.path, "objects")).length === 0, "backup_readback_store_not_empty");
    return consumeSyntheticTransferredSet(ownerId, receipt, (description, objects, checkCurrent, destinationId) => {
      const manifest = parseBackupManifest({ schemaVersion: 1, scope: "synthetic_backup_recovery_v1", testOnly: true, manifestId: randomUUID(), source: description.source, representation: description.representation, fidelity: description.fidelity, components: description.components });
      backupAssert(objects.length === manifest.components.length && new Set(objects.map(item => item.objectId)).size === objects.length, "backup_component_missing");
      this.root.readbackGuard = checkCurrent;
      const imported = manifest.components.map(component => {
        const object = objects.find(item => item.componentId === component.componentId);
        backupAssert(object && object.bytes.length === component.byteLength && digest(object.bytes) === component.sha256, "backup_readback_bytes_mismatch");
        const result: BackupObject = { componentId: component.componentId, objectId: object.objectId, objectRevision: object.objectRevision, byteLength: object.bytes.length, sha256: digest(object.bytes) };
        const base = this.base(ownerId, destinationId, result.objectId);
        this.writeNew(`${base}-${result.objectRevision}.bin`, object.bytes);
        this.writeMeta(`${base}.json`, { ...result, ownerId, destinationId, manifestFingerprint: digest(canonical(manifest)) });
        return result;
      });
      checkCurrent();
      const archive = parsePortableBackupArchive({ schemaVersion: 1, scope: "synthetic_backup_recovery_v1", testOnly: true, productionCapability: false, destinationId, manifest, objects: imported });
      this.verify(ownerId, archive); return archive;
    });
  }
  private fault(point: StoreFault): void {
    if (this.root.fault === point) { this.root.fault = null; throw new BackupRecoveryError(point === "disk_full" ? "backup_disk_full" : point === "after_object_commit" || point === "after_restore_commit" || point === "before_metadata_replace" ? "backup_outcome_unknown" : "backup_close_failed"); }
  }
  private base(ownerId: string, destinationId: string, objectId: string): string {
    backupId(ownerId); backupId(destinationId); backupId(objectId);
    return join(this.root.path, "objects", digest(canonical([ownerId, destinationId, objectId])));
  }
  private read(path: string): Uint8Array {
    this.check();
    const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      const stat = fstatSync(fd); backupAssert(stat.isFile() && stat.nlink === 1 && stat.size <= 1_048_576, "backup_fixture_file_invalid");
      const data = new Uint8Array(stat.size); let offset = 0;
      while (offset < data.length) { const read = readSync(fd, data, offset, data.length - offset, null); backupAssert(read > 0, "backup_short_read"); offset += read; }
      const extra = new Uint8Array(1); backupAssert(readSync(fd, extra, 0, 1, null) === 0, "backup_length_changed");
      return data;
    } finally {
      try { this.fault("read_close"); closeSync(fd); } catch (error) { this.root.unsettled.add(fd); throw error; }
    }
  }
  private writeNew(path: string, data: Uint8Array): void {
    this.check(); this.fault("disk_full");
    const fd = openSync(path, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    try {
      let offset = 0;
      while (offset < data.length) { const written = writeSync(fd, data, offset, data.length - offset); backupAssert(written > 0, "backup_short_write"); offset += written; }
      fsyncSync(fd);
    } finally {
      try { this.fault("write_close"); closeSync(fd); } catch (error) { this.root.unsettled.add(fd); throw error; }
    }
  }
  private writeMeta(path: string, data: unknown): void {
    const temporary = `${path}-${randomUUID()}.pending`;
    this.writeNew(temporary, new TextEncoder().encode(canonical(data)));
    this.fault("before_metadata_replace");
    renameSync(temporary, path);
    const fd = openSync(join(this.root.path, path.includes("/restores/") ? "restores" : "objects"), constants.O_RDONLY | constants.O_DIRECTORY);
    try { fsyncSync(fd); } finally { try { closeSync(fd); } catch (error) { this.root.unsettled.add(fd); throw error; } }
  }
  inspect(ownerId: string, destinationId: string, objectId: string): BackupObject | null { return this.inspectBound(ownerId, destinationId, objectId); }
  private inspectBound(ownerId: string, destinationId: string, objectId: string, manifestFingerprint?: string): BackupObject | null {
    this.check(); const base = this.base(ownerId, destinationId, objectId);
    if (!existsSync(`${base}.json`)) {
      const prefix = base.slice(base.lastIndexOf("/") + 1);
      backupAssert(!readdirSync(join(this.root.path, "objects")).some(name => name.startsWith(prefix)), "backup_outcome_unknown");
      return null;
    }
    const parsed = JSON.parse(new TextDecoder("utf8", { fatal: true }).decode(this.read(`${base}.json`)));
    const meta = backupObject(parsed, ["ownerId", "destinationId", "componentId", "objectId", "objectRevision", "byteLength", "sha256", "manifestFingerprint"]);
    backupAssert(meta.ownerId === ownerId && meta.destinationId === destinationId && meta.objectId === objectId, "backup_object_identity_changed");
    backupDigest(meta.manifestFingerprint);
    backupAssert(manifestFingerprint === undefined || meta.manifestFingerprint === manifestFingerprint, "backup_manifest_changed");
    const result = { componentId: backupId(meta.componentId), objectId, objectRevision: backupInteger(meta.objectRevision, 1), byteLength: backupInteger(meta.byteLength, 1, 1_048_576), sha256: String(meta.sha256) };
    const prefix = base.slice(base.lastIndexOf("/") + 1);
    const unresolved = readdirSync(join(this.root.path, "objects")).some(name => {
      if (!name.startsWith(prefix)) return false;
      if (name.endsWith(".pending")) return true;
      const revision = /^-([0-9]+)\.bin$/.exec(name.slice(prefix.length));
      return revision !== null && Number(revision[1]) > result.objectRevision;
    });
    backupAssert(!unresolved, "backup_outcome_unknown");
    const data = this.read(`${base}-${result.objectRevision}.bin`);
    backupAssert(data.length === result.byteLength && digest(data) === result.sha256, "backup_object_corrupt");
    return result;
  }
  append(ownerId: string, destinationId: string, before: ComponentProgress, chunk: SyntheticChunk, manifestValue: BackupManifest): BackupObject {
    this.check(); backupAssert(isGeneratedChunk(chunk), "backup_fixture_bytes_denied");
    backupAssert(chunks.get(chunk)!.ownerId === ownerId, "backup_fixture_owner_denied");
    const manifest = parseBackupManifest(manifestValue), { scope: omittedScope, manifestId: omittedId, ...portable } = manifest;
    backupAssert(chunk.evidenceFingerprint === digest(canonical({ ...portable, ownerId })), "backup_manifest_changed");
    const manifestFingerprint = digest(canonical(manifest));
    backupAssert(chunk.componentId === before.componentId && chunk.offset === before.uploadedBytes, "backup_offset_conflict");
    const current = this.inspectBound(ownerId, destinationId, before.objectId, manifestFingerprint);
    backupAssert(before.objectRevision === null ? current === null && before.uploadedBytes === 0 : current && current.componentId === before.componentId && current.objectRevision === before.objectRevision && current.byteLength === before.uploadedBytes && current.sha256 === before.prefixSha256, "backup_object_revision_conflict");
    const base = this.base(ownerId, destinationId, before.objectId);
    const previous = current ? this.read(`${base}-${current.objectRevision}.bin`) : new Uint8Array();
    const payload = chunks.get(chunk)!.bytes;
    const combined = new Uint8Array(previous.length + payload.length); combined.set(previous); combined.set(payload, previous.length);
    backupAssert(combined.length <= 1_048_576 && digest(combined) === chunk.prefixSha256, "backup_chunk_invalid");
    const result: BackupObject = { componentId: before.componentId, objectId: before.objectId, objectRevision: (current?.objectRevision ?? 0) + 1, byteLength: combined.length, sha256: digest(combined) };
    this.writeNew(`${base}-${result.objectRevision}.bin`, combined);
    this.writeMeta(`${base}.json`, { ...result, ownerId, destinationId, manifestFingerprint });
    this.fault("after_object_commit");
    return result;
  }
  verify(ownerId: string, archiveValue: PortableBackupArchive): BackupObject[] {
    this.check(); const archive = parsePortableBackupArchive(archiveValue);
    backupAssert(archive.objects.length === archive.manifest.components.length && new Set(archive.objects.map(item => item.objectId)).size === archive.objects.length);
    const verified = archive.manifest.components.map(component => {
      const expected = archive.objects.find(item => item.componentId === component.componentId);
      backupAssert(expected, "backup_component_missing");
      const actual = this.inspectBound(ownerId, archive.destinationId, expected.objectId, digest(canonical(archive.manifest)));
      backupAssert(actual && canonical(actual) === canonical(expected) && actual.byteLength === component.byteLength && actual.sha256 === component.sha256, "backup_object_revision_conflict");
      return actual;
    });
    this.check(); return verified;
  }
  private restoreBase(ownerId: string, restoreId: string): string {
    backupId(ownerId); backupId(restoreId);
    return join(this.root.path, "restores", digest(canonical([ownerId, restoreId])));
  }
  restore(ownerId: string, restoreId: string, archiveValue: PortableBackupArchive): void {
    this.check(); const archive = parsePortableBackupArchive(archiveValue), base = this.restoreBase(ownerId, restoreId);
    backupAssert(!readdirSync(join(this.root.path, "restores")).some(name => name.startsWith(base.slice(base.lastIndexOf("/") + 1))), "backup_restore_collision");
    const objects = this.verify(ownerId, archive);
    for (const object of objects) {
      const data = this.read(`${this.base(ownerId, archive.destinationId, object.objectId)}-${object.objectRevision}.bin`);
      this.writeNew(`${base}-${object.componentId}.bin`, data);
    }
    this.writeMeta(`${base}.json`, { manifest: archive.manifest });
    this.fault("after_restore_commit");
    this.check();
  }
  verifyRestore(ownerId: string, restoreId: string, manifestValue: BackupManifest): "absent" | "complete" {
    this.check(); const manifest = parseBackupManifest(manifestValue), base = this.restoreBase(ownerId, restoreId);
    if (!existsSync(`${base}.json`)) {
      backupAssert(!readdirSync(join(this.root.path, "restores")).some(name => name.startsWith(base.slice(base.lastIndexOf("/") + 1))), "backup_restore_partial");
      return "absent";
    }
    const meta = JSON.parse(new TextDecoder("utf8", { fatal: true }).decode(this.read(`${base}.json`)));
    backupAssert(canonical(meta) === canonical({ manifest }), "backup_restore_identity_changed");
    for (const component of manifest.components) {
      const data = this.read(`${base}-${component.componentId}.bin`);
      backupAssert(data.length === component.byteLength && digest(data) === component.sha256, "backup_restore_corrupt");
    }
    this.check(); return "complete";
  }
  readRestoredForTest(ownerId: string, restoreId: string, componentId: string): Uint8Array { backupId(componentId); return this.read(`${this.restoreBase(ownerId, restoreId)}-${componentId}.bin`); }
  damageForTest(ownerId: string, destinationId: string, objectId: string, mode: "corrupt" | "truncate" | "missing" | "revision" | "identity"): void {
    const current = this.inspect(ownerId, destinationId, objectId); backupAssert(current);
    const base = this.base(ownerId, destinationId, objectId), path = `${base}-${current.objectRevision}.bin`;
    if (mode === "missing") { rmSync(`${base}.json`); return; }
    if (mode === "identity" || mode === "revision") {
      const meta = JSON.parse(new TextDecoder().decode(this.read(`${base}.json`))) as ObjectInfo;
      if (mode === "identity") meta.objectId = randomUUID();
      else { meta.objectRevision++; this.writeNew(`${base}-${meta.objectRevision}.bin`, this.read(path)); }
      this.writeMeta(`${base}.json`, meta); return;
    }
    const bytes = this.read(path); if (mode === "corrupt") bytes[0] ^= 255;
    const fd = openSync(path, constants.O_WRONLY | constants.O_TRUNC | constants.O_NOFOLLOW);
    try { const payload = mode === "truncate" ? bytes.subarray(0, bytes.length - 1) : bytes; writeSync(fd, payload); fsyncSync(fd); } finally { closeSync(fd); }
  }
  disposeForTest(): void {
    this.check(false); const stat = lstatSync(this.root.path); backupAssert(stat.isDirectory() && !stat.isSymbolicLink());
    rmSync(this.root.path, { recursive: true });
  }
}
