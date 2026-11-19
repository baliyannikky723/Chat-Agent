import { CalendarClock, Globe } from 'lucide-react';
import { useState } from 'react';
import { HologramSlot } from '../hologram/HologramSlot';
import { useStore } from '../state/store';
import { Composer } from './Composer';
import { SeedDialog } from './DemoTools';

/** The three questions from the brief: each needs a date resolved, a memory found, and a fresh check. */
const MEMORY_PROMPTS = [
  'On Monday we shortlisted 5 CRM tools for a 20-person sales team. Has any of them changed pricing or shipped something big since?',
  'What did I ask you on 3 November? Is your answer still correct today?',
  'Last Tuesday I asked you about Postgres hosting. Has any pricing changed since then?',
];

const FRESH_PROMPTS = ['What changed in the latest Node.js release, and is it LTS yet?', 'Which managed Postgres hosts have a free tier right now?'];

export function EmptyState() {
  const typing = useStore((s) => s.typing);
  const timeline = useStore((s) => s.timeline);
  const demoTools = useStore((s) => s.status?.clock.demoTools ?? false);
  const send = useStore((s) => s.send);
  const [seeding, setSeeding] = useState(false);
  const [working, setWorking] = useState(false);
  const days = timeline?.length ?? 0;
  const hasHistory = days > 0;
  const prompts = hasHistory
    ? MEMORY_PROMPTS.map((text) => ({ text, memory: true }))
    : [...FRESH_PROMPTS.map((text) => ({ text, memory: false })), { text: MEMORY_PROMPTS[2], memory: true }];

  return (
    <div className="mx-auto flex min-h-full w-full max-w-[46rem] flex-col items-center px-5 pt-6 pb-12 sm:pt-10">
      <div className="relative h-[248px] w-full max-w-[540px] sm:h-[318px]">
        <div className="star-plate absolute inset-0" aria-hidden="true" />
        <HologramSlot className="absolute inset-0" mood={working ? 'busy' : typing ? 'listening' : 'idle'} planets={days} />
      </div>

      <h1 className="font-display mt-1 text-center text-[2.05rem] leading-[1.08] font-[460] tracking-[-0.025em] text-balance sm:text-[2.7rem]">
        Answers that know what day it is.
      </h1>
      <p className="mt-3.5 max-w-[48ch] text-center text-[0.98rem] leading-relaxed text-muted text-pretty">
        Orrery searches the web for anything current and remembers what you asked, and when, so it can tell you what has changed since.
        {hasHistory && <span className="text-brass-soft">{` It remembers ${days === 1 ? 'one day' : `${days} days`} of your questions.`}</span>}
      </p>

      <div className="mt-8 w-full">
        <Composer variant="hero" />
      </div>

      <ul className="mt-5 flex w-full flex-col gap-1">
        {prompts.map((p) => (
          <li key={p.text}>
            <button
              type="button"
              onClick={() => send(p.text)}
              className="group flex w-full items-start gap-3 rounded-xl px-3 py-2.5 text-left text-[0.9rem] text-muted transition-colors hover:bg-panel/70 hover:text-text"
            >
              {p.memory ? (
                <CalendarClock size={16} className="mt-0.5 shrink-0 text-brass" aria-label="About the past" />
              ) : (
                <Globe size={16} className="mt-0.5 shrink-0 text-holo" aria-label="About now" />
              )}
              <span className="leading-snug">{p.text}</span>
            </button>
          </li>
        ))}
      </ul>

      {demoTools && !hasHistory && (
        <div className="mt-6 flex w-full flex-col items-start gap-3 rounded-xl border border-dashed border-brass/35 px-4 py-3.5 sm:flex-row sm:items-center">
          <p className="text-sm leading-relaxed text-muted">
            <span className="text-brass-soft">No history yet.</span> Load three sample conversations, from 3 November, Monday and last Tuesday,
            to try the memory questions.
          </p>
          <button type="button" className="btn btn-quiet shrink-0 sm:ml-auto" onClick={() => setSeeding(true)}>
            Load sample history
          </button>
        </div>
      )}
      <SeedDialog open={seeding} onClose={() => setSeeding(false)} onRunning={setWorking} />
    </div>
  );
}
