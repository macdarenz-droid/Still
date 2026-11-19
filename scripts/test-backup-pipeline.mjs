import { readdirSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
let entries;
try { entries = readdirSync(new URL("../tests/backup-pipeline/", import.meta.url)); }
catch { console.error("Required composed backup pipeline test suite is missing"); process.exit(1); }
const files = entries.filter(name => name.endsWith(".test.ts")).sort().map(name => `tests/backup-pipeline/${name}`);
if (files.length === 0) { console.error("Required composed backup pipeline test suite is empty"); process.exit(1); }
const result = spawnSync(process.execPath, ["--test", ...files], { cwd: root, stdio: "inherit" });
if (result.error) console.error(result.error.message);
process.exit(result.status ?? 1);
