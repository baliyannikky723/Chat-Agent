// npm run build: the production build. The UI is compiled into web/dist, then Maven packages
// it inside the Spring Boot jar, so one file (backend/target/orrery.jar) serves everything.

import path from 'node:path';
import { JAR, ROOT, WEB_DIR, nodeVersionOk } from './lib/env.mjs';
import { c, fail, step, ticker } from './lib/term.mjs';
import { SetupError, ensureJdk, ensureMaven, runMaven } from './lib/toolchain.mjs';

if (!nodeVersionOk()) {
  fail(`Node.js ${process.versions.node} is too old.`, 'Orrery needs Node.js 20.19+ or 22.12+.');
  process.exit(1);
}

try {
  const ui = ticker('Building the UI');
  const { build } = await import('vite');
  await build({ configFile: path.join(WEB_DIR, 'vite.config.ts'), root: WEB_DIR, logLevel: 'warn' });
  ui.done('UI built into web/dist');

  const jdk = await ensureJdk();
  step(`Java ${jdk.version}`);
  const maven = await ensureMaven();
  const withTests = process.argv.includes('--with-tests');
  await runMaven(jdk, maven, withTests ? ['clean', 'package'] : ['clean', '-DskipTests', 'package'], {
    label: withTests ? 'Testing and packaging the backend' : 'Packaging the backend',
    logName: 'backend-package.log',
  });
  console.log(`\n${c.green('✓')} ${c.bold(path.relative(ROOT, JAR))} serves the UI and the API. Start it with ${c.cyan('npm start')}.\n`);
} catch (e) {
  fail(e.message, e instanceof SetupError ? e.detail : e.stack);
  process.exit(1);
}
