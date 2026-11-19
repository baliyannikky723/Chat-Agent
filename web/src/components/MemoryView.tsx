import clsx from 'clsx';
import { Eraser, Menu as MenuIcon } from 'lucide-react';
import { Fragment, useEffect, useState, type MouseEvent } from 'react';
import { daysBetween, relativeDayLabel, shortDay } from '../lib/dates';
import { navigate, paths } from '../lib/router';
import { prettyDomain } from '../lib/sites';
import type { LedgerEntryDto, LedgerPointDto } from '../lib/types';
import { useLayout } from '../state/layout';
import { useStore, useToday } from '../state/store';
import { Dialog } from './ui';

/**
 * What Orrery remembers, two ways: every question filed under the day it was asked,
 * and the ledger of time-sensitive facts its answers stated, each with the day it was true.
 */
export function MemoryView({ tab }: { tab: 'timeline' | 'ledger' }) {
  const forget = useStore((s) => s.forgetEverything);
  const setSidebarOpen = useLayout((s) => s.setSidebarOpen);
  const [confirming, setConfirming] = useState(false);

  const tabLink = (name: 'timeline' | 'ledger', label: string) => (
    <a
      href={name === 'timeline' ? '/memory' : '/ledger'}
      onClick={(e: MouseEvent) => {
        e.preventDefault();
        navigate(name === 'timeline' ? '/memory' : '/ledger');
      }}
      aria-current={tab === name ? 'page' : undefined}
      className={clsx('border-b-2 px-1 pb-2.5 text-sm transition-colors', tab === name ? 'border-brass text-text' : 'border-transparent text-muted hover:text-text')}
    >
      {label}
    </a>
  );

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto w-full max-w-[54rem] px-5 pt-5 pb-16 sm:pt-10">
        <button type="button" className="icon-btn mb-2 lg:hidden" aria-label="Open conversations" onClick={() => setSidebarOpen(true)}>
          <MenuIcon size={18} />
        </button>
        <div className="flex flex-wrap items-end gap-4">
          <div>
            <h1 className="font-display text-[2.2rem] leading-none font-[460] tracking-[-0.02em]">Memory</h1>
            <p className="mt-3 max-w-[56ch] text-muted">
              Every question is filed under the day you asked it, so “last Tuesday” means something. Facts that can change are kept with the date
              they were true.
            </p>
          </div>
          <button type="button" className="btn btn-danger ml-auto" onClick={() => setConfirming(true)}>
            <Eraser size={15} />
            Forget everything
          </button>
        </div>
        <div className="mt-8 flex gap-6 border-b border-hairline/70">
          {tabLink('timeline', 'By day')}
          {tabLink('ledger', 'Ledger of facts')}
        </div>
        <div className="mt-6">{tab === 'timeline' ? <Timeline /> : <Ledger />}</div>
      </div>
      <Dialog
        open={confirming}
        onClose={() => setConfirming(false)}
        title="Forget everything?"
        description="All conversations, the facts noted from them, and the demo clock are removed for this browser. Orrery starts with an empty memory."
        footer={
          <>
            <button type="button" className="btn btn-quiet" onClick={() => setConfirming(false)} data-autofocus>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-danger"
              onClick={() => {
                setConfirming(false);
                void forget();
              }}
            >
              Forget everything
            </button>
          </>
        }
      />
    </div>
  );
}

function openTurn(conversationId: string, userMessageId: string) {
  navigate(`${paths.conversation(conversationId)}?m=${userMessageId}`);
}

function Timeline() {
  const timeline = useStore((s) => s.timeline);
  const refresh = useStore((s) => s.refreshMemory);
  const today = useToday();
  useEffect(() => {
    void refresh();
  }, [refresh]);

  if (timeline === null) return <p className="text-muted">Loading…</p>;
  if (timeline.length === 0) {
    return <p className="max-w-[52ch] text-muted">Nothing yet. Each question you ask is filed here under the day you asked it.</p>;
  }
  return (
    <ol className="flex flex-col gap-8">
      {timeline.map((day) => {
        const recent = daysBetween(day.date, today) <= 1; // "Today" and "Yesterday" headings need the date beside them
        const count = day.turns.length === 1 ? '1 question' : `${day.turns.length} questions`;
        return (
          <li key={day.date} className="grid gap-3 sm:grid-cols-[13.5rem_1fr] sm:gap-6">
            <div>
              <h2 className="font-display text-[1.08rem] leading-tight font-[480] text-brass italic">{relativeDayLabel(day.date, today)}</h2>
              <p className="mt-1 text-xs text-faint">{recent ? `${shortDay(day.date, today)}, ${count}` : count}</p>
            </div>
            <ul className="flex flex-col divide-y divide-hairline/50 rounded-xl border border-hairline/70">
              {day.turns
                .slice()
                .reverse()
                .map((t) => (
                  <li key={t.userMessageId}>
                    <button
                      type="button"
                      onClick={() => openTurn(t.conversationId, t.userMessageId)}
                      className="group flex w-full items-baseline gap-4 px-4 py-3 text-left transition-colors hover:bg-panel/50"
                    >
                      <span className="tnum w-11 shrink-0 text-xs text-faint">{t.time}</span>
                      <span className="min-w-0 flex-1">
                        <span className="line-clamp-2 text-[0.92rem] leading-snug text-text/90 group-hover:text-text">{t.question}</span>
                        {t.conversationTitle && <span className="mt-0.5 block truncate text-xs text-faint">{t.conversationTitle}</span>}
                      </span>
                      {t.claims > 0 && <span className="shrink-0 text-xs text-brass-soft">{t.claims === 1 ? '1 fact' : `${t.claims} facts`}</span>}
                    </button>
                  </li>
                ))}
            </ul>
          </li>
        );
      })}
    </ol>
  );
}

function latestFirst(history: LedgerPointDto[]): LedgerPointDto[] {
  return history.slice().sort((a, b) => (a.asOf < b.asOf ? 1 : a.asOf > b.asOf ? -1 : 0));
}

function safeHost(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return url;
  }
}

/** One table, like an almanac: each entity a heading row, each fact with its value, the day it was true, and where it came from. */
function Ledger() {
  const ledger = useStore((s) => s.ledger);
  const refresh = useStore((s) => s.refreshLedger);
  const today = useToday();
  useEffect(() => {
    void refresh();
  }, [refresh]);

  if (ledger === null) return <p className="text-muted">Loading…</p>;
  if (ledger.length === 0) {
    return (
      <p className="max-w-[56ch] text-muted">
        No facts yet. When an answer states something that can change, like a price, a version or a release date, it is written here with the day
        it was true. That is what lets Orrery say exactly what changed since.
      </p>
    );
  }

  const byEntity = new Map<string, LedgerEntryDto[]>();
  for (const entry of ledger) {
    const list = byEntity.get(entry.entity) ?? [];
    list.push(entry);
    byEntity.set(entry.entity, list);
  }

  return (
    <div className="overflow-x-auto rounded-xl border border-hairline/70">
      <table className="w-full min-w-[40rem] table-fixed text-sm">
        <colgroup>
          <col className="w-[32%]" />
          <col className="w-[30%]" />
          <col className="w-[16%]" />
          <col className="w-[22%]" />
        </colgroup>
        <thead>
          <tr className="border-b border-hairline/70 text-left text-xs text-muted">
            <th className="px-4 py-2.5 font-medium">Fact</th>
            <th className="px-4 py-2.5 font-medium">Value</th>
            <th className="px-4 py-2.5 font-medium">True as of</th>
            <th className="px-4 py-2.5 font-medium">Source</th>
          </tr>
        </thead>
        <tbody>
          {[...byEntity.entries()].map(([entity, entries]) => (
            <Fragment key={entity}>
              <tr>
                <th colSpan={4} scope="rowgroup" className="border-t border-hairline/70 bg-panel/40 px-4 pt-3.5 pb-2 text-left text-[0.95rem] font-semibold first:border-t-0">
                  {entity}
                </th>
              </tr>
              {entries.map((entry) => {
                const [latest, ...older] = latestFirst(entry.history);
                const previous = older.find((p) => p.value.trim().toLowerCase() !== latest.value.trim().toLowerCase());
                return (
                  <tr key={entry.attribute} className="align-top">
                    <td className="truncate px-4 py-2.5 text-muted" title={entry.attribute}>
                      {entry.attribute}
                    </td>
                    <td className="px-4 py-2.5">
                      <button
                        type="button"
                        onClick={() => navigate(paths.conversation(latest.conversationId))}
                        className="text-left font-medium text-text hover:text-holo"
                        title={latest.statement}
                      >
                        {latest.value}
                      </button>
                      {previous && (
                        <span className="mt-1 block text-xs text-changed">
                          was {previous.value} on {shortDay(previous.asOfLocal, today)}
                        </span>
                      )}
                    </td>
                    <td className="tnum px-4 py-2.5 whitespace-nowrap text-brass-soft">{shortDay(latest.asOfLocal, today)}</td>
                    <td className="truncate px-4 py-2.5">
                      {latest.sourceUrl ? (
                        <a href={latest.sourceUrl} target="_blank" rel="noreferrer noopener" className="text-muted underline decoration-hairline underline-offset-4 hover:text-holo">
                          {prettyDomain(safeHost(latest.sourceUrl))}
                        </a>
                      ) : (
                        <span className="text-faint">from the answer</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </Fragment>
          ))}
        </tbody>
      </table>
    </div>
  );
}
