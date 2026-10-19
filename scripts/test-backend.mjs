import { readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const suites = ['tests/contracts', 'tests/faults'];
const files = [];
for (const suite of suites) {
  let entries;
  try {
    entries = readdirSync(new URL(`../${suite}/`, import.meta.url));
  } catch {
    console.error(`Required test suite is missing: ${suite}`);
    process.exit(1);
  }
  const tests = entries.filter((entry) => entry.endsWith('.test.ts')).sort();
  if (tests.length === 0) {
    console.error(`Required test suite is empty: ${suite}`);
    process.exit(1);
  }
  files.push(...tests.map((entry) => `${suite}/${entry}`));
}
const result = spawnSync(process.execPath, ['--test', ...files], {
  cwd: root,
  stdio: 'inherit',
});
if (result.error) console.error(result.error.message);
process.exit(result.status ?? 1);
