// Starting, watching and stopping the Spring Boot process.

import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import net from 'node:net';
import { DATA_DIR, ENV_FILE, JAR, ROOT } from './env.mjs';
import { c } from './term.mjs';

/** The first free port at or above `start` on the IPv4 loopback. */
export async function freePort(start, host = '127.0.0.1') {
  for (let port = start; port < start + 40; port++) {
    const free = await new Promise((resolve) => {
      const server = net.createServer();
      server.once('error', () => resolve(false));
      server.listen(port, host, () => server.close(() => resolve(true)));
    });
    if (free) return port;
  }
  throw new Error(`No free port between ${start} and ${start + 39}.`);
}

/**
 * Runs `java -jar backend/target/orrery.jar`. Its log lines are shown with an "api" prefix;
 * warnings and errors keep their colour so they stand out.
 */
export function startBackend(jdk, { port, host = '127.0.0.1', quiet = false }) {
  mkdirSync(DATA_DIR, { recursive: true });
  const child = spawn(jdk.java, ['-XX:+UseSerialGC', '-Xshare:auto', '-jar', JAR], {
    cwd: ROOT,
    env: { ...process.env, ORRERY_ENV_FILE: ENV_FILE, ORRERY_DATA_DIR: DATA_DIR, BACKEND_PORT: String(port), BACKEND_HOST: host },
    windowsHide: true,
  });
  const recent = [];
  const print = (chunk) => {
    for (const line of chunk.toString().split(/\r?\n/)) {
      if (!line.trim() || line.startsWith('Picked up JAVA_TOOL_OPTIONS')) continue;
      recent.push(line);
      if (recent.length > 60) recent.shift();
      if (quiet) continue;
      const tinted = / (ERROR|WARN) /.test(line) ? (line.includes(' ERROR ') ? c.red(line) : c.brass(line)) : c.muted(line);
      console.log(`${c.dim('api │')} ${tinted}`);
    }
  };
  child.stdout.on('data', print);
  child.stderr.on('data', print);
  return { child, recent };
}

/** Resolves when /api/health answers, rejects if the process exits first or it takes too long. */
export async function waitForHealth(url, child, timeoutMs = 180_000) {
  const deadline = Date.now() + timeoutMs;
  let exited = null;
  child.once('exit', (code) => (exited = code ?? 1));
  while (Date.now() < deadline) {
    if (exited !== null) throw new Error(`The backend stopped while starting (exit code ${exited}).`);
    try {
      const response = await fetch(`${url}/api/health`, { signal: AbortSignal.timeout(1500) });
      if (response.ok) return;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 350));
  }
  throw new Error('The backend did not answer within three minutes.');
}

/** Stops a process and everything it started. Windows needs taskkill for the whole tree. */
export function stopProcess(child, graceMs = 6000) {
  return new Promise((resolve) => {
    if (!child || child.exitCode !== null || child.signalCode !== null) return resolve();
    const timer = setTimeout(() => {
      if (process.platform === 'win32') spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true });
      else child.kill('SIGKILL');
    }, graceMs);
    child.once('exit', () => {
      clearTimeout(timer);
      resolve();
    });
    if (process.platform === 'win32') spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true });
    else child.kill('SIGTERM');
  });
}

export async function fetchHealth(url) {
  try {
    const response = await fetch(`${url}/api/health`, { signal: AbortSignal.timeout(3000) });
    return response.ok ? await response.json() : null;
  } catch {
    return null;
  }
}
