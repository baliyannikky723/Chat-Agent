// Finds, or fetches into .runtime/, the Java 21 JDK and Maven that build and run the backend,
// so `npm install && npm run dev` works on a machine that only has Node.
//
// Downloads come from the projects' own servers (Eclipse Adoptium for the JDK, Maven
// Central for Maven) and are checked against their published SHA-256 / SHA-512 sums.
// Maven is started through its own launcher class on the chosen JDK instead of mvn/mvn.cmd,
// which avoids shell quoting problems with spaces in Windows paths.

import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createWriteStream, existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, statSync } from 'node:fs';
import path from 'node:path';
import { BACKEND_DIR, JAR, RUNTIME_DIR } from './env.mjs';
import { c, formatBytes, info, ticker } from './term.mjs';

const WINDOWS = process.platform === 'win32';
const EXE = WINDOWS ? '.exe' : '';
const MIN_JAVA = 21;
const MAVEN_VERSIONS = ['3.9.11', '3.9.9'];

export class SetupError extends Error {
  constructor(message, detail) {
    super(message);
    this.detail = detail;
  }
}

// ── Java ────────────────────────────────────────────────────────────────

/** Asks a java binary for its home and major version. */
function probeJava(javaBin) {
  if (!javaBin) return null;
  const run = spawnSync(javaBin, ['-XshowSettings:properties', '-version'], { encoding: 'utf8', timeout: 30_000, windowsHide: true });
  if (run.error || run.status !== 0) return null;
  const out = `${run.stderr ?? ''}\n${run.stdout ?? ''}`;
  const home = /java\.home = (.+)/.exec(out)?.[1]?.trim();
  const spec = /java\.specification\.version = (.+)/.exec(out)?.[1]?.trim();
  if (!home || !spec) return null;
  const major = spec.startsWith('1.') ? Number(spec.split('.')[1]) : Number(spec.split('.')[0]);
  const vendor = /java\.vendor = (.+)/.exec(out)?.[1]?.trim() ?? '';
  const version = /java\.version = (.+)/.exec(out)?.[1]?.trim() ?? spec;
  return { home, major, vendor, version };
}

function localJdkHome() {
  const base = path.join(RUNTIME_DIR, 'jdk');
  const mac = path.join(base, 'Contents', 'Home');
  return existsSync(path.join(mac, 'bin', `java${EXE}`)) ? mac : base;
}

/** The first JDK 21+ found: JAVA_HOME, then one downloaded earlier, then java on PATH. */
export function findJdk() {
  const candidates = [];
  if (process.env.JAVA_HOME) candidates.push({ bin: path.join(process.env.JAVA_HOME, 'bin', `java${EXE}`), source: 'JAVA_HOME' });
  candidates.push({ bin: path.join(localJdkHome(), 'bin', `java${EXE}`), source: '.runtime' });
  candidates.push({ bin: 'java', source: 'PATH' });
  const tooOld = [];
  for (const candidate of candidates) {
    if (candidate.bin !== 'java' && !existsSync(candidate.bin)) continue;
    const probe = probeJava(candidate.bin);
    if (!probe) continue;
    const javac = path.join(probe.home, 'bin', `javac${EXE}`);
    if (probe.major < MIN_JAVA) {
      tooOld.push(`${probe.version} (${candidate.source})`);
      continue;
    }
    if (!existsSync(javac)) {
      tooOld.push(`${probe.version} without a compiler, a JRE rather than a JDK (${candidate.source})`);
      continue;
    }
    return { home: probe.home, java: path.join(probe.home, 'bin', `java${EXE}`), version: probe.version, vendor: probe.vendor, source: candidate.source };
  }
  return { missing: true, tooOld };
}

function adoptiumPlatform() {
  const os = { win32: 'windows', darwin: 'mac', linux: 'linux' }[process.platform];
  const arch = { x64: 'x64', arm64: 'aarch64' }[process.arch];
  if (!os || !arch) return null;
  return { os, arch };
}

export async function ensureJdk() {
  const found = findJdk();
  if (!found.missing) return found;
  const platform = adoptiumPlatform();
  const why = found.tooOld.length ? `Found ${found.tooOld.join(', ')}, but Orrery needs a Java ${MIN_JAVA}+ JDK.` : `No Java ${MIN_JAVA}+ JDK found.`;
  if (!platform) {
    throw new SetupError(why, `Install a JDK ${MIN_JAVA} (for example Eclipse Temurin from https://adoptium.net) and run npm run dev again.`);
  }
  info(`${why} Downloading Eclipse Temurin ${MIN_JAVA} into .runtime/ (once, about 190 MB).`);

  const api = `https://api.adoptium.net/v3/assets/latest/${MIN_JAVA}/hotspot?architecture=${platform.arch}&image_type=jdk&os=${platform.os}&vendor=eclipse`;
  let asset;
  try {
    const response = await fetch(api, { headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    asset = (await response.json())[0]?.binary?.package;
  } catch (e) {
    throw new SetupError(why, `Could not reach api.adoptium.net (${e.message}). Install a JDK ${MIN_JAVA} from https://adoptium.net, then run npm run dev again.`);
  }
  if (!asset?.link) throw new SetupError(why, 'Adoptium has no JDK build for this platform. Install a JDK 21 yourself.');

  const archive = await download(asset.link, asset.name, { sha256: asset.checksum, size: asset.size, label: `Downloading JDK ${MIN_JAVA}` });
  const target = path.join(RUNTIME_DIR, 'jdk');
  extractInto(archive, target);
  rmSync(archive, { force: true });
  const jdk = findJdk();
  if (jdk.missing) throw new SetupError('The downloaded JDK did not start.', `Delete the .runtime folder and try again, or install a JDK ${MIN_JAVA} yourself.`);
  return jdk;
}

// ── Maven ───────────────────────────────────────────────────────────────

function classworldsJar(home) {
  try {
    const name = readdirSync(path.join(home, 'boot')).find((f) => /^plexus-classworlds-.*\.jar$/.test(f));
    return name ? path.join(home, 'boot', name) : null;
  } catch {
    return null;
  }
}

function mavenAt(home, source) {
  if (!home || !existsSync(path.join(home, 'bin', 'm2.conf')) || !classworldsJar(home)) return null;
  return { home, source };
}

/** A Maven installation: one downloaded earlier, MAVEN_HOME, or mvn on PATH; else download one. */
export async function ensureMaven() {
  for (const v of MAVEN_VERSIONS) {
    const local = mavenAt(path.join(RUNTIME_DIR, `apache-maven-${v}`), '.runtime');
    if (local) return { ...local, version: v };
  }
  for (const key of ['MAVEN_HOME', 'M2_HOME']) {
    const fromEnv = mavenAt(process.env[key], key);
    if (fromEnv) return { ...fromEnv, version: 'installed' };
  }
  const probe = spawnSync('mvn', ['-v'], { encoding: 'utf8', timeout: 30_000, shell: WINDOWS, windowsHide: true });
  const home = /Maven home: (.+)/.exec(`${probe.stdout ?? ''}`)?.[1]?.trim();
  const version = /Apache Maven (\d+\.\d+\.\d+)/.exec(`${probe.stdout ?? ''}`)?.[1];
  if (home && version && compareVersions(version, '3.6.3') >= 0) {
    const onPath = mavenAt(home, 'PATH');
    if (onPath) return { ...onPath, version };
  }

  const v = MAVEN_VERSIONS[0];
  info(`Maven not found. Downloading Apache Maven ${v} into .runtime/ (once, about 9 MB).`);
  const base = `https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${v}/apache-maven-${v}-bin.tar.gz`;
  let sha512;
  try {
    const response = await fetch(`${base}.sha512`);
    if (response.ok) sha512 = (await response.text()).trim().split(/\s+/)[0];
  } catch {
    // checked below
  }
  if (!sha512) throw new SetupError('Could not reach Maven Central.', 'Check your internet connection (repo.maven.apache.org), or install Maven 3.9 and run npm run dev again.');
  const archive = await download(base, `apache-maven-${v}-bin.tar.gz`, { sha512, label: `Downloading Maven ${v}` });
  extractInto(archive, path.join(RUNTIME_DIR, `apache-maven-${v}`));
  rmSync(archive, { force: true });
  const installed = mavenAt(path.join(RUNTIME_DIR, `apache-maven-${v}`), '.runtime');
  if (!installed) throw new SetupError('The downloaded Maven is incomplete.', 'Delete the .runtime folder and try again.');
  return { ...installed, version: v };
}

function compareVersions(a, b) {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) if ((pa[i] ?? 0) !== (pb[i] ?? 0)) return (pa[i] ?? 0) - (pb[i] ?? 0);
  return 0;
}

/** The command line that runs Maven on the given JDK, with the backend as project directory. */
export function mavenCommand(jdk, maven, args) {
  return {
    command: jdk.java,
    args: [
      '-classpath',
      classworldsJar(maven.home),
      `-Dclassworlds.conf=${path.join(maven.home, 'bin', 'm2.conf')}`,
      `-Dmaven.home=${maven.home}`,
      `-Dlibrary.jansi.path=${path.join(maven.home, 'lib', 'jansi-native')}`,
      `-Dmaven.multiModuleProjectDirectory=${BACKEND_DIR}`,
      'org.codehaus.plexus.classworlds.launcher.Launcher',
      ...args,
    ],
    env: { ...process.env, JAVA_HOME: jdk.home, MAVEN_HOME: maven.home },
  };
}

// ── building the backend ────────────────────────────────────────────────

function newest(dir) {
  let latest = 0;
  if (!existsSync(dir)) return latest;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    latest = Math.max(latest, entry.isDirectory() ? newest(full) : statSync(full).mtimeMs);
  }
  return latest;
}

/** True when the jar is missing or older than any backend source file. */
export function backendIsStale() {
  if (!existsSync(JAR)) return true;
  const jar = statSync(JAR).mtimeMs;
  return Math.max(statSync(path.join(BACKEND_DIR, 'pom.xml')).mtimeMs, newest(path.join(BACKEND_DIR, 'src', 'main'))) > jar;
}

/**
 * Runs Maven with its output going to a log file and a ticking status line on screen.
 * On failure the errors are printed, so nobody has to open the log to see what broke.
 */
export async function runMaven(jdk, maven, goals, { label, logName }) {
  const logDir = path.join(RUNTIME_DIR, 'logs');
  mkdirSync(logDir, { recursive: true });
  const logFile = path.join(logDir, logName);
  const log = createWriteStream(logFile);
  const firstRun = !existsSync(path.join(BACKEND_DIR, 'target'));
  const tick = ticker(firstRun ? `${label} (the first build downloads Spring Boot, a minute or two)` : label);
  const { command, args, env } = mavenCommand(jdk, maven, ['-B', '-ntp', ...goals]);
  const tail = [];
  const code = await new Promise((resolve) => {
    const child = spawn(command, args, { cwd: BACKEND_DIR, env, windowsHide: true });
    const onData = (chunk) => {
      log.write(chunk);
      for (const line of chunk.toString().split(/\r?\n/)) {
        if (!line.trim()) continue;
        tail.push(line);
        if (tail.length > 80) tail.shift();
        const test = /Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/.exec(line);
        if (test && !line.includes(' - in ')) tick.update(`${label}: ${test[1]} tests so far`);
      }
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.on('error', (e) => {
      tail.push(String(e));
      resolve(1);
    });
    child.on('close', resolve);
  });
  log.end();
  if (code === 0) {
    tick.done(label);
    return { ok: true, tail, logFile };
  }
  tick.stop();
  const errors = tail.filter((l) => /\[ERROR]|FAILURE|Exception|error:/i.test(l)).slice(0, 30);
  const offline = tail.some((l) => /Could not transfer artifact|Non-resolvable parent POM|Could not resolve dependencies|UnknownHost/i.test(l));
  const hint = offline
    ? '\n\nMaven could not download libraries from Maven Central (repo.maven.apache.org). Check the internet connection;' +
      ' behind a company proxy, set it in ~/.m2/settings.xml.'
    : '';
  throw new SetupError(`${label} failed.`, `${(errors.length ? errors : tail.slice(-25)).join('\n')}${hint}\n\nFull log: ${path.relative(process.cwd(), logFile)}`);
}

// ── downloads ───────────────────────────────────────────────────────────

async function download(url, name, { sha256, sha512, size, label }) {
  const dir = path.join(RUNTIME_DIR, 'downloads');
  mkdirSync(dir, { recursive: true });
  const file = path.join(dir, name);
  const response = await fetch(url, { redirect: 'follow' });
  if (!response.ok || !response.body) throw new SetupError(`Download failed: ${url}`, `HTTP ${response.status}`);
  const total = Number(response.headers.get('content-length')) || size || 0;
  const hash = createHash(sha512 ? 'sha512' : 'sha256');
  const out = createWriteStream(file);
  const tick = ticker(label);
  let received = 0;
  for await (const chunk of response.body) {
    hash.update(chunk);
    received += chunk.length;
    if (!out.write(chunk)) await new Promise((r) => out.once('drain', r));
    tick.update(total ? `${label} ${Math.floor((received / total) * 100)}% of ${formatBytes(total)}` : `${label} ${formatBytes(received)}`);
  }
  await new Promise((resolve, reject) => out.end((e) => (e ? reject(e) : resolve())));
  const expected = (sha512 ?? sha256 ?? '').toLowerCase();
  const actual = hash.digest('hex');
  if (expected && actual !== expected) {
    tick.stop();
    rmSync(file, { force: true });
    throw new SetupError(`${name} did not match its published checksum.`, 'The download was corrupted or tampered with. Run npm run dev again to retry.');
  }
  tick.done(`${label.replace('Downloading', 'Downloaded')} ${c.dim('checksum verified')}`);
  return file;
}

/** Extracts a .zip or .tar.gz whose content is one top folder, and moves that folder to `target`. */
function extractInto(archive, target) {
  const scratch = `${target}.partial`;
  rmSync(scratch, { recursive: true, force: true });
  mkdirSync(scratch, { recursive: true });
  // Windows 10+ ships bsdtar as System32\tar.exe, which reads .zip as well as .tar.gz.
  const tar = WINDOWS ? path.join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'tar.exe') : 'tar';
  let run = spawnSync(existsSync(tar) || !WINDOWS ? tar : 'tar', ['-xf', archive, '-C', scratch], { encoding: 'utf8', windowsHide: true });
  if (run.status !== 0 && WINDOWS && archive.endsWith('.zip')) {
    run = spawnSync('powershell.exe', ['-NoProfile', '-Command', `Expand-Archive -LiteralPath '${archive.replace(/'/g, "''")}' -DestinationPath '${scratch.replace(/'/g, "''")}' -Force`], {
      encoding: 'utf8',
      windowsHide: true,
    });
  }
  if (run.status !== 0) throw new SetupError(`Could not extract ${path.basename(archive)}.`, run.stderr || run.stdout || '');
  const entries = readdirSync(scratch);
  const top = entries.length === 1 ? path.join(scratch, entries[0]) : scratch;
  rmSync(target, { recursive: true, force: true });
  renameSync(top, target);
  rmSync(scratch, { recursive: true, force: true });
}

export function readText(file) {
  try {
    return readFileSync(file, 'utf8');
  } catch {
    return '';
  }
}
