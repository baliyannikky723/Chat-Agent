import clsx from 'clsx';
import { ChevronDown, Search } from 'lucide-react';
import { useState } from 'react';
import { dayMonth, duration, shortDay } from '../lib/dates';
import { navigate, paths } from '../lib/router';
import type { RecallHit, RecallSummary } from '../lib/types';
import { MiniOrrery } from '../hologram/MiniOrrery';
import { useToday, type SearchStep, type Turn } from '../state/store';
import { Favicon, useElapsed } from './ui';

/**
 * What Orrery did for a turn, in order: what it found in memory, what it searched for
 * (with the date filter it used), and what it is doing now. Memory stays visible after
 * the answer arrives; the searches fold into a one-line summary.
 */
export function Steps({ turn }: { turn: Turn }) {
  const live = turn.state === 'streaming';
  const [open, setOpen] = useState(false);
  const showSearches = live || open;
  const failedSearches = turn.searches.filter((s) => s.state === 'failed').length;

  if (!live && !turn.recall && turn.searches.length === 0) return null;
  return (
    <div className="mb-4 flex flex-col gap-2 text-[0.84rem]">
      {turn.recall && <RecallLine recall={turn.recall} />}
      {turn.searches.length > 0 &&
        (showSearches ? (
          <div className="flex flex-col gap-1.5">
            {turn.searches.map((s) => (
              <SearchLine key={s.id} step={s} />
            ))}
            {!live && (
              <button type="button" className="self-start text-xs text-faint hover:text-muted" onClick={() => setOpen(false)}>
                Hide searches
              </button>
            )}
          </div>
        ) : (
          <button
            type="button"
            onClick={() => setOpen(true)}
            className="group inline-flex items-center gap-2 self-start rounded-lg py-0.5 text-muted transition-colors hover:text-text"
            aria-expanded={false}
          >
            <Search size={14} className="text-holo" />
            <span>
              {turn.searches.length === 1 ? `Searched “${turn.searches[0].query}”` : `${turn.searches.length} searches`}
              {turn.sources.length > 0 && `, ${turn.sources.length} ${turn.sources.length === 1 ? 'source' : 'sources'}`}
              {failedSearches > 0 && <span className="text-changed">{`, ${failedSearches} failed`}</span>}
            </span>
            <ChevronDown size={14} className="text-faint transition-transform group-hover:translate-y-px" />
          </button>
        ))}
      {live && <PhaseLine turn={turn} />}
    </div>
  );
}

function hitWhen(hit: RecallHit, today: string): string {
  const time = hit.label.includes(', ') ? hit.label.split(', ').pop() : '';
  return `${shortDay(hit.date, today)}${time ? `, ${time}` : ''}`;
}

function RecallLine({ recall }: { recall: RecallSummary }) {
  const today = useToday();
  const found = recall.status === 'found';
  const asked = recall.references[0]?.resolved;
  const open = (hit: RecallHit) => navigate(`${paths.conversation(hit.conversationId)}?m=${hit.userMessageId}`);

  return (
    <div className="flex items-start gap-2.5">
      <MiniOrrery tone={recall.status === 'none' ? 'muted' : 'brass'} active={false} className="mt-0.5" />
      <div className="min-w-0 flex-1">
        <p className={clsx('leading-snug', recall.status === 'none' ? 'text-muted' : 'text-brass-soft')}>
          {found
            ? recall.hits.length === 1
              ? `From memory${asked ? `: ${asked}` : ''}`
              : `From memory: ${recall.hits.length} past questions`
            : recall.note ?? 'Nothing in memory matches this.'}
        </p>
        {recall.hits.length > 0 && (
          <ul className="mt-1.5 flex flex-col gap-1">
            {recall.hits.slice(0, 4).map((hit) => (
              <li key={hit.userMessageId}>
                <button type="button" onClick={() => open(hit)} className="group flex max-w-full items-baseline gap-2 text-left" title="Open that conversation">
                  <span className="date-chip shrink-0">{hitWhen(hit, today)}</span>
                  <span className="truncate text-muted transition-colors group-hover:text-text">“{hit.question}”</span>
                  {hit.claims > 0 && <span className="shrink-0 text-xs text-faint">{hit.claims === 1 ? '1 fact' : `${hit.claims} facts`}</span>}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function SearchLine({ step }: { step: SearchStep }) {
  const icons = step.sources.slice(0, 4);
  return (
    <div className="flex min-w-0 items-center gap-2.5">
      {step.state === 'running' ? <MiniOrrery size={16} /> : <Search size={14} className={step.state === 'failed' ? 'text-changed' : 'text-holo'} />}
      <span className="min-w-0 truncate text-text/90">“{step.query}”</span>
      {step.since && <span className="date-chip shrink-0">since {dayMonth(step.since)}</span>}
      {!step.since && step.recency && step.recency !== 'any' && <span className="shrink-0 text-xs text-faint">past {step.recency}</span>}
      <span className="ml-auto flex shrink-0 items-center gap-2 pl-2 text-xs text-faint">
        {step.state === 'failed' ? (
          <span className="max-w-60 truncate text-changed" title={step.error ?? undefined}>
            {step.error ?? 'Search failed'}
          </span>
        ) : step.state === 'done' ? (
          <>
            {icons.length > 0 && (
              <span className="hidden -space-x-1 sm:flex">
                {icons.map((s) => (
                  <Favicon key={s.n} domain={s.domain} size={14} className="ring-2 ring-ink" />
                ))}
              </span>
            )}
            <span className="tnum">{step.results === 1 ? '1 result' : `${step.results} results`}</span>
            {step.ms !== null && <span className="tnum">{step.cached ? 'cached' : duration(step.ms)}</span>}
          </>
        ) : (
          <span>searching</span>
        )}
      </span>
    </div>
  );
}

function PhaseLine({ turn }: { turn: Turn }) {
  const seconds = useElapsed(turn.startedAt, true);
  const label = turn.stopping ? 'Stopping' : turn.phaseLabel ?? 'Thinking';
  const searching = turn.searches.some((s) => s.state === 'running');
  if (searching && turn.phase === 'searching') return null;
  return (
    <div className="flex items-center gap-2.5 text-muted" aria-live="polite">
      <MiniOrrery size={16} tone={turn.phase === 'recalling' ? 'brass' : 'holo'} />
      <span>{label}</span>
      <span className="tnum text-xs text-faint">{seconds.toFixed(1)} s</span>
    </div>
  );
}
