import clsx from 'clsx';
import { Check, Copy, RefreshCw, RotateCcw } from 'lucide-react';
import { memo, useState } from 'react';
import { textForCopy } from '../lib/citations';
import { askedAtLabel, duration } from '../lib/dates';
import type { TurnError } from '../lib/types';
import { useStore, useTimezone, useToday, type Turn } from '../state/store';
import { Markdown } from './Markdown';
import { Sources } from './Sources';
import { Steps } from './Steps';

export const TurnView = memo(function TurnView({ turn, highlighted = false }: { turn: Turn; highlighted?: boolean }) {
  const live = turn.state === 'streaming';
  return (
    <article className="py-7 first:pt-4" data-message={turn.userMessageId ?? undefined}>
      <Question turn={turn} highlighted={highlighted} />
      <div className="mt-5">
        <Steps turn={turn} />
        {turn.text && <Markdown text={turn.text} sources={turn.sources} streaming={live} />}
        {turn.warnings.map((w, i) => (
          <p key={i} className="mt-3 text-xs text-brass-soft">
            {w}
          </p>
        ))}
        {turn.state === 'failed' && <ErrorNote turnKey={turn.key} error={turn.error} />}
        {turn.state === 'stopped' && <p className="mt-3 text-xs text-faint">{turn.text ? 'Stopped before the end.' : 'Stopped.'}</p>}
        {turn.state !== 'streaming' && turn.sources.length > 0 && <Sources sources={turn.sources} cited={turn.cited} />}
        {turn.state === 'done' && <AnswerFooter turn={turn} />}
      </div>
    </article>
  );
});

function Question({ turn, highlighted }: { turn: Turn; highlighted: boolean }) {
  const today = useToday();
  const tz = useTimezone();
  return (
    <div className="flex flex-col items-end gap-1.5">
      {turn.recheckOf && <span className="text-xs text-faint">Re-checking an earlier answer</span>}
      <div
        className={clsx(
          'max-w-[min(85%,36rem)] rounded-2xl rounded-br-md border px-4 py-2.5 whitespace-pre-wrap transition-colors duration-700',
          highlighted ? 'border-brass/60 bg-brass/10' : 'border-hairline bg-raised',
        )}
      >
        {turn.question}
      </div>
      {turn.askedAt && <span className="tnum text-[0.72rem] text-faint">{askedAtLabel(turn.askedAt, tz, today)}</span>}
    </div>
  );
}

function ErrorNote({ turnKey, error }: { turnKey: string; error: TurnError | null }) {
  const retry = useStore((s) => s.retry);
  const refreshStatus = useStore((s) => s.refreshStatus);
  const message = error?.message ?? 'Something went wrong.';
  return (
    <div className="mt-3 rounded-xl border border-changed/30 bg-changed/[0.07] px-4 py-3" role="alert">
      <p className="text-sm font-semibold text-changed">{message}</p>
      {error?.hint && <p className="mt-1 text-sm text-muted">{error.hint}</p>}
      <div className="mt-3 flex gap-2">
        {(error?.retryable ?? true) && (
          <button type="button" className="btn btn-quiet h-8" onClick={() => retry(turnKey)}>
            <RotateCcw size={14} />
            Try again
          </button>
        )}
        {error?.code === 'not_configured' && (
          <button type="button" className="btn btn-quiet h-8" onClick={() => void refreshStatus()}>
            Check setup
          </button>
        )}
      </div>
    </div>
  );
}

function AnswerFooter({ turn }: { turn: Turn }) {
  const recheck = useStore((s) => s.recheck);
  const toast = useStore((s) => s.toast);
  const busy = useStore((s) => s.threads[s.activeKey]?.turns.some((t) => t.state === 'streaming') ?? false);
  const [copied, setCopied] = useState(false);
  const m = turn.metrics;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(textForCopy(turn.text, turn.sources));
      setCopied(true);
      setTimeout(() => setCopied(false), 1600);
    } catch {
      toast('Copy is blocked by the browser here.', 'error');
    }
  };

  return (
    <div className="mt-5 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-faint">
      {m && (
        <span className="tnum" title={m.ttftMs ? `First words after ${duration(m.ttftMs)}` : undefined}>
          Answered in {duration(m.totalMs)}
        </span>
      )}
      {m?.model && <span className="truncate">{m.model}</span>}
      <span className="ml-auto flex items-center gap-1">
        <button type="button" className="text-btn" onClick={copy} aria-label="Copy answer">
          {copied ? <Check size={14} className="text-ok" /> : <Copy size={14} />}
          {copied ? 'Copied' : 'Copy'}
        </button>
        {turn.answerId && (
          <button
            type="button"
            className="text-btn"
            disabled={busy}
            onClick={() => recheck(turn.key)}
            title="Search again and say what has changed since this answer"
          >
            <RefreshCw size={14} />
            Re-check today
          </button>
        )}
      </span>
    </div>
  );
}
