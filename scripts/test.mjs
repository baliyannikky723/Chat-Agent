// npm test: every automated check. Frontend types and unit tests, then the backend's JUnit
// suite (which runs real HTTP against fake model and search servers, and a real SQLite file).

import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { ROOT } from './lib/env.mjs';
import { c, fail, step } from './lib/term.mjs';
import { SetupError, ensureJdk, ensureMaven, runMaven } from './lib/toolchain.mjs';

const bin = (name) => path.join(ROOT, 'node_modules', '.bin', process.platform === 'win32' ? `${name}.cmd` : name);
const run = (label, command, args) => {
  step(label);
  const result = spawnSync(command, args, { cwd: ROOT, stdio: 'inherit', shell: process.platform === 'win32' });
  if (result.status !== 0) {
    fail(`${label} failed.`);
    process.exit(result.status ?? 1);
  }
};

run('Type-checking the UI', bin('tsc'), ['-p', 'web/tsconfig.json']);
run('UI unit tests', bin('vitest'), ['run', '--root', 'web']);

try {
  const jdk = await ensureJdk();
  const maven = await ensureMaven();
  const result = await runMaven(jdk, maven, ['test'], { label: 'Backend tests (JUnit)', logName: 'backend-test.log' });
  const summary = result.tail.filter((l) => /Tests run: \d+, Failures/.test(l) && !l.includes(' - in ')).pop();
  if (summary) console.log(`  ${c.muted(summary.replace(/^\[\w+\]\s*/, ''))}`);
  console.log(`\n${c.green('✓')} ${c.bold('All checks passed.')}\n`);
} catch (e) {
  fail(e.message, e instanceof SetupError ? e.detail : e.stack);
  process.exit(1);
}
