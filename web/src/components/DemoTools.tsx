import clsx from 'clsx';
import { CalendarClock } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { askedAtLabel, toLocalInput } from '../lib/dates';
import { MiniOrrery } from '../hologram/MiniOrrery';
import { useStore, useToday } from '../state/store';
import { Dialog } from './ui';

/**
 * Demo tools (DEMO_TOOLS=true in .env). Memory questions need a past, and a reviewer has
 * one afternoon: the demo clock moves "now" for this browser, and sample history creates
 * real past conversations through the real agent, dated 3 November, Monday and last Tuesday.
 */

export function DemoClock() {
  const clock = useStore((s) => s.status?.clock);
  const setClock = useStore((s) => s.setClock);
  const today = useToday();
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState('');
  const [seeding, setSeeding] = useState(false);
  if (!clock) return null;
  const shifted = Math.abs(clock.offsetMs) > 60_000;

  return (
    <div className="flex items-center gap-1">
      <button
        type="button"
        onClick={() => {
          setValue(toLocalInput(clock.now));
          setOpen(true);
        }}
        className={clsx('inline-flex min-w-0 items-center gap-2 rounded-md py-1 pr-1.5 transition-colors hover:text-text', shifted && 'text-brass-soft')}
        title="Demo clock"
      >
        <CalendarClock size={14} className={shifted ? 'text-brass' : undefined} />
        <span className="truncate">{shifted ? `Demo clock: ${askedAtLabel(clock.now, clock.timezone, '')}` : 'Clock: real time'}</span>
      </button>
      <button type="button" onClick={() => setSeeding(true)} className="ml-auto shrink-0 rounded-md px-1.5 py-1 transition-colors hover:text-text">
        Sample history
      </button>

      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        title="Demo clock"
        description="Pretend it is another day and time, to try questions like “last Tuesday” or “since Monday”. It only changes this browser’s memory, and new questions are filed under the demo date."
        footer={
          <>
            {shifted && (
              <button
                type="button"
                className="btn btn-quiet mr-auto"
                onClick={() => {
                  void setClock(null);
                  setOpen(false);
                }}
              >
                Back to real time
              </button>
            )}
            <button type="button" className="btn btn-quiet" onClick={() => setOpen(false)}>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-primary"
              disabled={!value}
              onClick={() => {
                void setClock(value);
                setOpen(false);
              }}
            >
              Set clock
            </button>
          </>
        }
      >
        <label className="block text-sm text-muted" htmlFor="demo-clock">
          Now is
        </label>
        <input id="demo-clock" type="datetime-local" className="field mt-1.5" value={value} onChange={(e) => setValue(e.target.value)} />
        <p className="mt-2 text-xs text-faint">Today is {today} in {clock.timezone}.</p>
      </Dialog>
      <SeedDialog open={seeding} onClose={() => setSeeding(false)} />
    </div>
  );
}

export function SeedDialog({ open, onClose, onRunning }: { open: boolean; onClose: () => void; onRunning?: (running: boolean) => void }) {
  const seedDemo = useStore((s) => s.seedDemo);
  const [lines, setLines] = useState<{ tone: 'info' | 'warn' | 'done'; text: string }[]>([]);
  const [state, setState] = useState<'idle' | 'running' | 'done' | 'failed'>('idle');
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    onRunning?.(state === 'running');
  }, [state, onRunning]);

  useEffect(() => {
    if (open) {
      setLines([]);
      setState('idle');
    }
  }, [open]);

  const start = async () => {
    controller.current = new AbortController();
    setState('running');
    setLines([]);
    try {
      await seedDemo((line) => setLines((l) => [...l, line]), controller.current.signal);
      setState('done');
    } catch (e) {
      if ((e as Error).name === 'AbortError') {
        setState('idle');
        return;
      }
      setLines((l) => [...l, { tone: 'warn', text: (e as Error).message }]);
      setState('failed');
    }
  };

  const close = () => {
    if (state === 'running') controller.current?.abort();
    onClose();
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      wide
      title="Load sample history"
      description="Creates three past conversations through the real agent, dated 3 November, this week’s Monday and last Tuesday: a Node.js release question, a CRM shortlist and Postgres hosting. Then the questions from the brief have something to find. Takes about a minute and uses a few searches."
      footer={
        state === 'done' ? (
          <button type="button" className="btn btn-primary" onClick={onClose} data-autofocus>
            Done
          </button>
        ) : (
          <>
            <button type="button" className="btn btn-quiet" onClick={close}>
              {state === 'running' ? 'Stop' : 'Cancel'}
            </button>
            <button type="button" className="btn btn-primary" disabled={state === 'running'} onClick={() => void start()}>
              {state === 'failed' ? 'Try again' : 'Load history'}
            </button>
          </>
        )
      }
    >
      {lines.length > 0 && (
        <ol className="flex max-h-60 flex-col gap-2 overflow-y-auto text-sm" aria-live="polite">
          {lines.map((line, i) => {
            const last = i === lines.length - 1 && state === 'running';
            return (
              <li key={i} className={clsx('flex items-start gap-2.5', line.tone === 'warn' ? 'text-changed' : line.tone === 'done' ? 'text-ok' : 'text-muted')}>
                {last ? <MiniOrrery size={16} tone="brass" /> : <span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-current opacity-70" />}
                <span className="leading-snug">{line.text}</span>
              </li>
            );
          })}
        </ol>
      )}
      {state === 'running' && lines.length === 0 && (
        <p className="flex items-center gap-2.5 text-sm text-muted">
          <MiniOrrery size={16} tone="brass" /> Starting
        </p>
      )}
    </Dialog>
  );
}
