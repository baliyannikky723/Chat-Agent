// npm start: runs the production jar (built by npm run build), which serves the UI and the API
// on one port. Builds it first if it is missing.

import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fetchHealth, freePort, startBackend, stopProcess, waitForHealth } from './lib/backend.mjs';
import { JAR, ROOT, ensureEnvFile, loadEnv } from './lib/env.mjs';
import { c, fail, step, ticker } from './lib/term.mjs';
import { SetupError, ensureJdk } from './lib/toolchain.mjs';

try {
  ensureEnvFile();
  const env = loadEnv();
  if (!existsSync(JAR)) {
    step('No build yet: running npm run build first');
    const build = spawnSync(process.execPath, ['scripts/build.mjs'], { cwd: ROOT, stdio: 'inherit' });
    if (build.status !== 0) process.exit(build.status ?? 1);
  }
  const jdk = await ensureJdk();
  const host = env.BACKEND_HOST || '127.0.0.1';
  const port = await freePort(Number(env.BACKEND_PORT) || 8080, host === '0.0.0.0' ? '0.0.0.0' : '127.0.0.1');
  const local = `http://127.0.0.1:${port}`;
  const boot = ticker('Starting Orrery');
  const backend = startBackend(jdk, { port, host, quiet: false });
  const stop = async () => {
    await stopProcess(backend.child);
    process.exit(0);
  };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
  try {
    await waitForHealth(local, backend.child);
  } catch (e) {
    boot.stop();
    throw new SetupError(e.message, backend.recent.slice(-30).join('\n'));
  }
  const health = await fetchHealth(local);
  boot.done(`Orrery is running at ${c.cyan(c.bold(`http://localhost:${port}`))}`);
  if (health && !health.ready) step(c.brass('Add your API keys to .env, then restart npm start.'));
  backend.child.once('exit', (code) => process.exit(code ?? 0));
} catch (e) {
  fail(e.message, e instanceof SetupError ? e.detail : e.stack);
  process.exit(1);
}
