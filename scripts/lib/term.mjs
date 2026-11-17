// Terminal output for the launcher: a few colours, a status line that ticks, no dependencies.

const color = process.stdout.isTTY && !process.env.NO_COLOR && process.env.TERM !== 'dumb';
const wrap = (open, close) => (s) => (color ? `\x1b[${open}m${s}\x1b[${close}m` : String(s));

export const c = {
  bold: wrap(1, 22),
  dim: wrap(2, 22),
  cyan: wrap('38;2;127;227;240', 39),
  brass: wrap('38;2;217;179;108', 39),
  green: wrap('38;2;95;211;166', 39),
  red: wrap('38;2;255;143;126', 39),
  muted: wrap('38;2;154;166;199', 39),
};

export function step(message) {
  console.log(`${c.cyan('◉')} ${message}`);
}

export function info(message) {
  console.log(`  ${c.muted(message)}`);
}

export function warn(message) {
  console.log(`${c.brass('!')} ${message}`);
}

export function fail(message, detail) {
  console.error(`\n${c.red('✗')} ${c.bold(message)}`);
  if (detail) console.error(detail.split('\n').map((l) => `  ${l}`).join('\n'));
  console.error('');
}

/** A single status line that shows elapsed seconds while a long step runs. */
export function ticker(label) {
  const started = Date.now();
  const frames = ['◜', '◠', '◝', '◞', '◡', '◟'];
  let i = 0;
  let text = label;
  const live = process.stdout.isTTY;
  const draw = () => {
    const secs = Math.round((Date.now() - started) / 1000);
    process.stdout.write(`\r${c.cyan(frames[i++ % frames.length])} ${text} ${c.dim(`${secs} s`)}\x1b[K`);
  };
  const timer = live ? setInterval(draw, 120) : null;
  if (!live) console.log(`${c.cyan('◉')} ${label}`);
  return {
    update(next) {
      text = next;
    },
    done(message) {
      if (timer) clearInterval(timer);
      const secs = ((Date.now() - started) / 1000).toFixed(1);
      if (live) process.stdout.write('\r\x1b[K');
      console.log(`${c.green('✓')} ${message ?? text} ${c.dim(`${secs} s`)}`);
    },
    stop() {
      if (timer) clearInterval(timer);
      if (live) process.stdout.write('\r\x1b[K');
    },
  };
}

export function formatBytes(n) {
  if (!Number.isFinite(n) || n <= 0) return '';
  return n > 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(0)} MB` : `${Math.round(n / 1024)} KB`;
}
