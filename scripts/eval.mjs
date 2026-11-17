// npm run eval: checks the behaviour that matters, end to end, against a running Orrery with
// real keys (start it with npm run dev or npm start first, and keep DEMO_TOOLS=true).
//
// It acts as a brand-new browser: loads the sample history (3 November, Monday, last Tuesday),
// asks the three questions from the brief plus honesty, freshness and small-talk probes, and
// checks the agent's event stream: was the right day recalled, did it search with that date,
// did it cite, did it refuse to invent a conversation that never happened. Then it deletes
// the test memory. Exit code 1 when a required check fails.
//
//   npm run eval                      uses http://127.0.0.1:8080
//   npm run eval -- --url http://127.0.0.1:8081 --keep

const args = process.argv.slice(2);
const argValue = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);
const BASE = (argValue('--url') ?? process.env.ORRERY_URL ?? 'http://127.0.0.1:8080').replace(/\/+$/, '');
const KEEP = args.includes('--keep');
const TZ = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

const color = process.stdout.isTTY && !process.env.NO_COLOR;
const paint = (code) => (s) => (color ? `\x1b[${code}m${s}\x1b[0m` : String(s));
const green = paint('38;2;95;211;166');
const red = paint('38;2;255;143;126');
const brass = paint('38;2;217;179;108');
const dim = paint(2);
const bold = paint(1);

let cookie = '';
const headers = (extra = {}) => ({ 'X-Timezone': TZ, 'X-Locale': 'en-GB', ...(cookie ? { Cookie: cookie } : {}), ...extra });

function keepCookie(response) {
  const set = response.headers.get('set-cookie');
  const match = set && /orrery_uid=([^;]+)/.exec(set);
  if (match) cookie = `orrery_uid=${match[1]}`;
}

async function json(method, path, body) {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: headers(body ? { 'Content-Type': 'application/json' } : {}),
    body: body ? JSON.stringify(body) : undefined,
  });
  keepCookie(response);
  if (!response.ok) throw new Error(`${method} ${path}: HTTP ${response.status} ${await response.text()}`);
  return response.json();
}

/** POSTs and collects the Server-Sent Events of one turn. */
async function stream(path, body) {
  const started = Date.now();
  const response = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: headers({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }),
    body: JSON.stringify(body),
  });
  keepCookie(response);
  if (!response.ok) throw new Error(`POST ${path}: HTTP ${response.status}`);
  const events = [];
  let firstText = null;
  const decoder = new TextDecoder();
  let buffer = '';
  for await (const chunk of response.body) {
    buffer += decoder.decode(chunk, { stream: true });
    let cut;
    while ((cut = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, cut);
      buffer = buffer.slice(cut + 2);
      const data = block
        .split('\n')
        .filter((l) => l.startsWith('data:'))
        .map((l) => l.slice(5).replace(/^ /, ''))
        .join('\n');
      if (!data) continue;
      const event = JSON.parse(data);
      if (event.type === 'text.delta' && firstText === null) firstText = Date.now() - started;
      events.push(event);
    }
  }
  return { events, ms: Date.now() - started, ttft: firstText };
}

// Dates the sample history uses, computed the same way as the backend's DemoSeeder.
function sampleDates(today) {
  const d = new Date(`${today}T12:00:00Z`);
  const dow = d.getUTCDay();
  const shift = (days) => new Date(d.getTime() - days * 86_400_000).toISOString().slice(0, 10);
  const monday = shift(dow === 1 ? 7 : (dow + 6) % 7 || 7);
  const tuesday = shift(dow === 2 ? 7 : (dow + 5) % 7 || 7);
  let year = Number(today.slice(0, 4));
  if (`${year}-11-03` >= today) year -= 1;
  return { monday, tuesday, nov3: `${year}-11-03` };
}

const results = [];
function check(scenario, name, ok, { required = true, detail = '' } = {}) {
  results.push({ scenario, name, ok, required, detail });
}

function summarise(run) {
  const ev = (type) => run.events.filter((e) => e.type === type);
  const done = ev('turn.completed')[0];
  const failed = ev('turn.failed')[0];
  return {
    recall: ev('memory.recalled')[0]?.recall ?? null,
    searches: ev('search.started'),
    text: done?.text ?? failed?.partialText ?? '',
    cited: done?.cited ?? [],
    done,
    failed,
  };
}

const CRMS = ['hubspot', 'salesforce', 'pipedrive', 'zoho', 'freshsales', 'freshworks', 'close', 'copper', 'attio', 'insightly', 'nimble', 'monday', 'keap', 'nutshell'];
const POSTGRES = ['neon', 'supabase', 'aws', 'rds', 'aurora', 'render', 'railway', 'digitalocean', 'crunchy', 'timescale', 'aiven', 'heroku', 'google cloud', 'azure', 'xata', 'tembo', 'fly'];

async function main() {
  console.log(`\n${bold('Orrery eval')} ${dim(BASE)}\n`);
  let status;
  try {
    status = await json('GET', '/api/status');
  } catch {
    console.log(red(`Cannot reach ${BASE}. Start Orrery first (npm run dev), or pass --url.`));
    process.exit(2);
  }
  if (!status.ready) {
    console.log(red('Orrery is not configured: add a model key and a search key to .env.'));
    process.exit(2);
  }
  if (!status.clock.demoTools) {
    console.log(red('Set DEMO_TOOLS=true in .env: the eval loads sample history through the demo tools.'));
    process.exit(2);
  }
  console.log(`${dim('model')} ${status.llm.provider} ${status.llm.model}   ${dim('search')} ${status.search.providers.join(', ')}\n`);

  const today = status.clock.now.slice(0, 10);
  const dates = sampleDates(today);
  process.stdout.write(`${brass('◉')} Loading sample history (3 Nov, ${dates.monday}, ${dates.tuesday}) through the real agent… `);
  const seed = await stream('/api/dev/seed-demo', {});
  const created = seed.events.find((e) => e.type === 'status' && e.phase === 'done');
  console.log(created ? green(created.label) : red('failed'));
  check('setup', 'sample history created', Boolean(created) && !seed.events.some((e) => e.type === 'warning'));

  const scenarios = [
    {
      id: 'Monday CRMs',
      message: 'On Monday we shortlisted 5 CRM tools for a 20-person sales team. Has any of them changed pricing or shipped something big since?',
      date: dates.monday,
      names: CRMS,
    },
    { id: '3 November', message: 'What did I ask you on 3 November? Is your answer still correct today?', date: dates.nov3, names: ['node'] },
    {
      id: 'Last Tuesday',
      message: 'Last Tuesday I asked you about Postgres hosting. Has any pricing changed since then?',
      date: dates.tuesday,
      names: POSTGRES,
    },
  ];

  for (const s of scenarios) {
    const run = await stream('/api/chat', { conversationId: null, message: s.message });
    const r = summarise(run);
    const hitDates = (r.recall?.hits ?? []).map((h) => h.date);
    const lower = r.text.toLowerCase();
    check(s.id, 'answered without error', Boolean(r.done), { detail: r.failed?.error?.message ?? '' });
    check(s.id, `recalled the conversation from ${s.date}`, r.recall?.status === 'found' && hitDates.includes(s.date), {
      detail: r.recall ? `${r.recall.status} ${hitDates.join(', ')}` : 'no recall',
    });
    check(s.id, 'searched the web to verify', r.searches.length > 0, { detail: `${r.searches.length} searches` });
    check(s.id, `filtered a search to news since ${s.date}`, r.searches.some((e) => e.since === s.date), {
      required: false,
      detail: r.searches.map((e) => e.since ?? e.recency ?? 'any').join(', '),
    });
    check(s.id, 'cited sources', r.cited.length > 0, { detail: `${r.cited.length} cited` });
    check(s.id, 'talked about the recalled items', s.names.some((n) => lower.includes(n)), { required: false });
    console.log(`${r.done ? green('✓') : red('✗')} ${s.id.padEnd(13)} ${dim(`${(run.ms / 1000).toFixed(1)} s total, first words ${run.ttft ? `${(run.ttft / 1000).toFixed(1)} s` : 'never'}`)}`);
  }

  // Honesty: a day with no conversation must not be invented.
  {
    const empty = new Date(`${dates.nov3}T12:00:00Z`);
    empty.setUTCDate(empty.getUTCDate() + 2);
    const day = empty.toISOString().slice(0, 10);
    const label = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'long', timeZone: 'UTC' }).format(empty);
    const skip = [dates.monday, dates.tuesday].includes(day);
    if (!skip) {
      const run = await stream('/api/chat', { conversationId: null, message: `What did I ask you on ${label}?` });
      const r = summarise(run);
      check('Honesty', `admits there is nothing from ${label}`, r.recall?.status === 'none' || r.recall?.status === 'mismatch', { detail: r.recall?.status ?? 'no recall' });
      check('Honesty', 'does not invent a conversation', /(no record|don't have|do not have|didn't|did not|no conversation|nothing|can't find|cannot find|couldn't find|no questions)/i.test(r.text), {
        required: false,
      });
      console.log(`${r.done ? green('✓') : red('✗')} ${'Honesty'.padEnd(13)} ${dim(`${(run.ms / 1000).toFixed(1)} s`)}`);
    }
  }

  // Freshness: a question about now must be searched and cited.
  {
    const run = await stream('/api/chat', { conversationId: null, message: 'What is the latest version of Node.js right now?' });
    const r = summarise(run);
    check('Fresh', 'searched before answering', r.searches.length > 0);
    check('Fresh', 'cited sources', r.cited.length > 0);
    console.log(`${r.done ? green('✓') : red('✗')} ${'Fresh'.padEnd(13)} ${dim(`${(run.ms / 1000).toFixed(1)} s total, first words ${run.ttft ? `${(run.ttft / 1000).toFixed(1)} s` : 'never'}`)}`);
  }

  // Small talk: no searching, quick.
  {
    const run = await stream('/api/chat', { conversationId: null, message: 'Thanks, that was helpful!' });
    const r = summarise(run);
    check('Small talk', 'replied without searching', r.searches.length === 0 && Boolean(r.done));
    check('Small talk', 'replied in under 6 s', run.ms < 6000, { required: false, detail: `${(run.ms / 1000).toFixed(1)} s` });
    console.log(`${r.done ? green('✓') : red('✗')} ${'Small talk'.padEnd(13)} ${dim(`${(run.ms / 1000).toFixed(1)} s`)}`);
  }

  if (!KEEP) await json('DELETE', '/api/memory');

  console.log('');
  let currentScenario = '';
  for (const r of results) {
    if (r.scenario !== currentScenario) {
      currentScenario = r.scenario;
      console.log(bold(r.scenario));
    }
    const mark = r.ok ? green('✓') : r.required ? red('✗') : brass('○');
    console.log(`  ${mark} ${r.name}${r.detail ? dim(`  ${r.detail}`) : ''}${!r.required ? dim(' (advisory)') : ''}`);
  }
  const required = results.filter((r) => r.required);
  const passed = required.filter((r) => r.ok).length;
  const advisory = results.filter((r) => !r.required);
  console.log(
    `\n${passed === required.length ? green('All required checks passed') : red(`${required.length - passed} required checks failed`)} ` +
      dim(`(${passed}/${required.length} required, ${advisory.filter((r) => r.ok).length}/${advisory.length} advisory)`) +
      (KEEP ? dim('. Test memory kept (--keep).') : dim('. Test memory deleted.')) +
      '\n',
  );
  process.exit(passed === required.length ? 0 : 1);
}

main().catch((e) => {
  console.error(red(e.stack ?? String(e)));
  process.exit(1);
});
