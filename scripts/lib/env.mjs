// .env handling shared by the npm scripts. The parsing rules match the backend's DotEnv.java.

import { copyFileSync, existsSync, readFileSync, renameSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
export const ENV_FILE = path.join(ROOT, '.env');
export const ENV_EXAMPLE = path.join(ROOT, '.env.example');
export const BACKEND_DIR = path.join(ROOT, 'backend');
export const WEB_DIR = path.join(ROOT, 'web');
export const RUNTIME_DIR = path.join(ROOT, '.runtime');
export const DATA_DIR = path.join(ROOT, 'data');
export const JAR = path.join(BACKEND_DIR, 'target', 'orrery.jar');

export function parseEnv(text) {
  const out = {};
  const lines = text.replace(/^﻿/, '').split(/\r\n|\r|\n/);
  for (const raw of lines) {
    let line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    if (line.startsWith('export ')) line = line.slice(7).trim();
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) continue;
    let value = line.slice(eq + 1).trim();
    if (value.length >= 2 && (value[0] === '"' || value[0] === "'")) {
      const quote = value[0];
      const close = value.indexOf(quote, 1);
      value = close > 0 ? value.slice(1, close) : value.slice(1);
      if (quote === '"') value = value.replace(/\\n/g, '\n');
    } else {
      const hash = value.indexOf(' #');
      if (hash >= 0) value = value.slice(0, hash).trim();
    }
    out[key] = value;
  }
  return out;
}

/**
 * Makes sure .env exists. Windows Notepad sometimes saves it as ".env.txt"; that is fixed
 * here rather than leaving someone to wonder why their keys are not read.
 */
export function ensureEnvFile() {
  if (existsSync(ENV_FILE)) return { created: false, renamed: false };
  const txt = `${ENV_FILE}.txt`;
  if (existsSync(txt)) {
    renameSync(txt, ENV_FILE);
    return { created: false, renamed: true };
  }
  if (existsSync(ENV_EXAMPLE)) {
    copyFileSync(ENV_EXAMPLE, ENV_FILE);
    return { created: true, renamed: false };
  }
  return { created: false, renamed: false };
}

/** The file merged under the real environment: real environment variables win, as in the backend. */
export function loadEnv() {
  const file = existsSync(ENV_FILE) ? parseEnv(readFileSync(ENV_FILE, 'utf8')) : {};
  const merged = { ...file };
  for (const [k, v] of Object.entries(process.env)) if (v !== undefined && v !== '') merged[k] = v;
  return merged;
}

/** Vite 8 needs Node 20.19+ or 22.12+. */
export function nodeVersionOk() {
  const [major, minor] = process.versions.node.split('.').map(Number);
  if (major === 20) return minor >= 19;
  if (major === 22) return minor >= 12;
  return major >= 23;
}
