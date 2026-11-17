// npm run dev: one command for the whole app.
//
//   1. .env      created from .env.example the first time
//   2. Java 21   found, or downloaded into .runtime/
//   3. Maven     found, or downloaded into .runtime/
//   4. backend   built with Maven when its sources changed, then `java -jar` (Spring Boot)
//   5. frontend  Vite dev server with hot reload, proxying /api to the backend
//
// Saving .env restarts the backend so new keys take effect. Ctrl+C stops everything.
// To run the backend yourself (from an IDE), set ORRERY_BACKEND_URL and only the UI starts.

import { existsSync, readFileSync, watch } from 'node:fs';
import path from 'node:path';
import { fetchHealth, freePort, startBackend, stopProcess, waitForHealth } from './lib/backend.mjs';
import { DATA_DIR, ENV_FILE, ROOT, WEB_DIR, ensureEnvFile, loadEnv, nodeVersionOk } from './lib/env.mjs';
import { c, fail, info, step, ticker, warn } from './lib/term.mjs';
import { SetupError, backendIsStale, ensureJdk, ensureMaven, runMaven } from './lib/toolchain.mjs';

if (!nodeVersionOk()) {
  fail(`Node.js ${process.versions.node} is too old.`, 'Orrery needs Node.js 20.19+ or 22.12+. Install the LTS version from https://nodejs.org and run npm run dev again.');
  process.exit(1);
}
if (!existsSync(path.join(ROOT, 'node_modules', 'vite'))) {
  fail('The frontend packages are not installed.', 'Run npm install first, then npm run dev.');
  process.exit(1);
}

console.log(`\n${c.bold('Orrery')} ${c.dim('answers that know what day it is')}\n`);

let backend = null;
let vite = null;
let shuttingDown = false;
let restarting = false;

async function shutdown(code = 0) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`\n${c.dim('Stopping…')}`);
  await Promise.allSettled([vite?.close(), stopProcess(backend?.child)]);
  process.exit(code);
}
process.on('SIGINT', () => void shutdown(0));
process.on('SIGTERM', () => void shutdown(0));

try {
  const envFile = ensureEnvFile();
  if (envFile.created) step(`Created ${c.bold('.env')} from .env.example. Add your API keys there; the app will show you how.`);
  if (envFile.renamed) step(`Renamed .env.txt to ${c.bold('.env')} (Notepad adds .txt).`);
  const env = loadEnv();

  let backendUrl = env.ORRERY_BACKEND_URL?.replace(/\/+$/, '');
  let jdk = null;
  let port = null;

  if (backendUrl) {
    step(`Using the backend at ${backendUrl} (ORRERY_BACKEND_URL)`);
  } else {
    jdk = await ensureJdk();
    step(`Java ${jdk.version} ${c.dim(`${jdk.vendor ? `${jdk.vendor}, ` : ''}from ${jdk.source}`)}`);
    if (backendIsStale()) {
      const maven = await ensureMaven();
      await runMaven(jdk, maven, ['-DskipTests', 'package'], { label: 'Building the Spring Boot backend', logName: 'backend-build.log' });
    } else {
      step(`Backend is up to date ${c.dim('(backend/target/orrery.jar)')}`);
    }
    port = await freePort(Number(env.BACKEND_PORT) || 8080);
    backendUrl = `http://127.0.0.1:${port}`;
    const boot = ticker('Starting the backend');
    backend = startBackend(jdk, { port, quiet: true });
    try {
      await waitForHealth(backendUrl, backend.child);
      boot.done(`Backend ready on ${backendUrl}`);
    } catch (e) {
      boot.stop();
      throw new SetupError(e.message, backend.recent.slice(-30).join('\n'));
    }
    // From here on its log is shown live, so problems during use are visible.
    backend.child.stdout.removeAllListeners('data');
    backend.child.stderr.removeAllListeners('data');
    backend = { ...backend, ...startLogging(backend.child) };
  }

  process.env.ORRERY_BACKEND_URL = backendUrl;
  const { createServer } = await import('vite');
  vite = await createServer({ configFile: path.join(WEB_DIR, 'vite.config.ts'), root: WEB_DIR, logLevel: 'warn', clearScreen: false });
  await vite.listen();
  const uiUrl = vite.resolvedUrls?.local?.[0]?.replace(/\/$/, '') ?? 'http://localhost:5173';

  const health = await fetchHealth(backendUrl);
  banner(uiUrl, backendUrl, health, Boolean(jdk));

  if (jdk) watchEnv(jdk, port, backendUrl);
} catch (e) {
  if (e instanceof SetupError) fail(e.message, e.detail);
  else fail(e.message ?? String(e), e.stack);
  await shutdown(1);
}

function startLogging(child) {
  const recent = [];
  const print = (chunk) => {
    for (const line of chunk.toString().split(/\r?\n/)) {
      if (!line.trim() || line.startsWith('Picked up JAVA_TOOL_OPTIONS')) continue;
      recent.push(line);
      if (recent.length > 60) recent.shift();
      const tinted = line.includes(' ERROR ') ? c.red(line) : line.includes(' WARN ') ? c.brass(line) : c.muted(line);
      console.log(`${c.dim('api │')} ${tinted}`);
    }
  };
  child.stdout.on('data', print);
  child.stderr.on('data', print);
  child.once('exit', (code) => {
    if (!shuttingDown && !restarting) warn(`The backend stopped (exit code ${code}). Save .env or restart npm run dev to start it again.`);
  });
  return { recent };
}

function banner(uiUrl, backendUrl, health, ownBackend) {
  const row = (label, value) => console.log(`    ${c.muted(label.padEnd(9))} ${value}`);
  console.log(`\n  ${c.cyan('◉')} ${c.bold('Orrery is running')}\n`);
  row('Open', c.cyan(c.bold(uiUrl)));
  row('Backend', `${backendUrl} ${c.dim('Spring Boot')}`);
  if (health?.ready) {
    row('Model', `${health.provider} ${health.model}`);
    row('Search', health.search.join(', '));
  } else if (health) {
    row('Setup', c.brass('add your API keys to .env (the page shows how); the backend restarts when you save it'));
  }
  if (ownBackend) row('Memory', c.dim(path.relative(ROOT, path.join(DATA_DIR, 'orrery.db'))));
  console.log(`\n  ${c.dim('Press Ctrl+C to stop.')}\n`);
}

/** Restarts the backend when .env is saved, so new keys apply without a manual restart. */
function watchEnv(jdk, port, backendUrl) {
  let last = existsSync(ENV_FILE) ? readFileSync(ENV_FILE, 'utf8') : '';
  let timer = null;
  const onChange = () => {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      const now = existsSync(ENV_FILE) ? readFileSync(ENV_FILE, 'utf8') : '';
      if (now === last || restarting || shuttingDown) return;
      last = now;
      restarting = true;
      step('.env changed, restarting the backend');
      await stopProcess(backend?.child);
      const next = startBackend(jdk, { port, quiet: true });
      try {
        await waitForHealth(backendUrl, next.child, 120_000);
        next.child.stdout.removeAllListeners('data');
        next.child.stderr.removeAllListeners('data');
        backend = { ...next, ...startLogging(next.child) };
        const health = await fetchHealth(backendUrl);
        if (health?.ready) step(`Backend restarted: ${health.provider} ${health.model}, search by ${health.search.join(', ')}`);
        else warn('Backend restarted, but a key is still missing. The page lists what to add.');
      } catch (e) {
        fail(`The backend did not restart: ${e.message}`, next.recent.slice(-20).join('\n'));
        backend = next;
      } finally {
        restarting = false;
      }
    }, 400);
  };
  try {
    // Watch the folder: editors often replace the file instead of writing into it.
    watch(ROOT, (_, file) => {
      if (file === '.env') onChange();
    });
  } catch {
    info('Could not watch .env; restart npm run dev after editing it.');
  }
}
