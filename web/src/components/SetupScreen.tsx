import clsx from 'clsx';
import { Check, ExternalLink, KeyRound } from 'lucide-react';
import { useEffect } from 'react';
import { HologramSlot } from '../hologram/HologramSlot';
import { MiniOrrery } from '../hologram/MiniOrrery';
import type { StatusDto } from '../lib/types';
import { useStore } from '../state/store';
import { Wordmark } from './Logo';

/**
 * Shown until both keys are in .env. There is no pretend mode: without a model and a
 * search API Orrery cannot do its job, so this screen says exactly what to add, and the
 * launcher restarts the backend the moment .env is saved.
 */
export function SetupScreen({ status }: { status: StatusDto }) {
  const refreshStatus = useStore((s) => s.refreshStatus);
  const boot = useStore((s) => s.boot);

  useEffect(() => {
    const timer = setInterval(async () => {
      const next = await refreshStatus();
      if (next?.ready) void boot();
    }, 2500);
    return () => clearInterval(timer);
  }, [refreshStatus, boot]);

  const lines = [
    !status.llm.configured && 'GROQ_API_KEY=gsk_your_key_here',
    !status.search.configured && 'TAVILY_API_KEY=tvly-your_key_here',
  ].filter(Boolean) as string[];

  return (
    <div className="min-h-dvh overflow-y-auto">
      <div className="mx-auto grid min-h-dvh max-w-5xl items-center gap-6 px-6 py-10 lg:grid-cols-[1fr_1.1fr] lg:gap-12">
        <div className="relative mx-auto h-[220px] w-full max-w-[420px] lg:h-[380px]">
          <div className="star-plate absolute inset-0" aria-hidden="true" />
          <HologramSlot className="absolute inset-0" dim planets={0} />
        </div>
        <div>
          <Wordmark />
          <h1 className="font-display mt-6 text-[2.1rem] leading-[1.1] font-[460] tracking-[-0.02em] text-balance">Two free keys and Orrery is ready.</h1>
          <p className="mt-3 max-w-[50ch] text-muted">
            Orrery needs a language model to write answers and a search API to read the web. Both have free tiers that need no card.
          </p>

          <ol className="mt-7 flex flex-col gap-4">
            <Step
              done={status.llm.configured}
              title="A model"
              detail={status.llm.configured ? `${status.llm.provider} ${status.llm.model}` : status.llm.missing ?? ''}
              links={[
                { href: 'https://console.groq.com/keys', label: 'Free Groq key' },
                { href: 'https://aistudio.google.com/apikey', label: 'Free Gemini key' },
              ]}
            />
            <Step
              done={status.search.configured}
              title="Web search"
              detail={status.search.configured ? status.search.providers.join(', ') : status.search.missing ?? ''}
              links={[{ href: 'https://app.tavily.com/home', label: 'Free Tavily key' }]}
            />
          </ol>

          {lines.length > 0 && (
            <div className="mt-7">
              <p className="text-sm text-muted">
                Open <code className="rounded bg-panel px-1.5 py-0.5 text-text">.env</code> in the project folder and add:
              </p>
              <pre className="mt-2 overflow-x-auto rounded-xl border border-hairline bg-ink-deep px-4 py-3 font-mono text-[0.82rem] leading-relaxed text-holo">
                {lines.join('\n')}
              </pre>
              <p className="mt-3 text-sm text-muted">Save the file. When started with npm run dev, Orrery restarts by itself and this page moves on.</p>
            </div>
          )}

          <p className="mt-6 flex items-center gap-2.5 text-xs text-faint" aria-live="polite">
            <MiniOrrery size={16} /> Checking every few seconds
          </p>
        </div>
      </div>
    </div>
  );
}

function Step({ done, title, detail, links }: { done: boolean; title: string; detail: string; links: { href: string; label: string }[] }) {
  return (
    <li className={clsx('flex gap-3.5 rounded-xl border px-4 py-3.5', done ? 'border-ok/30 bg-ok/[0.05]' : 'border-hairline bg-panel/60')}>
      <span className={clsx('mt-0.5 grid h-6 w-6 shrink-0 place-items-center rounded-full', done ? 'bg-ok/15 text-ok' : 'bg-brass/15 text-brass')}>
        {done ? <Check size={14} strokeWidth={3} /> : <KeyRound size={13} />}
      </span>
      <div className="min-w-0">
        <p className="font-semibold">{title}</p>
        <p className="mt-0.5 text-sm break-words text-muted">{detail}</p>
        {!done && (
          <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
            {links.map((l) => (
              <a key={l.href} href={l.href} target="_blank" rel="noreferrer noopener" className="inline-flex items-center gap-1 text-sm text-holo hover:underline">
                {l.label}
                <ExternalLink size={12} />
              </a>
            ))}
          </div>
        )}
      </div>
    </li>
  );
}
