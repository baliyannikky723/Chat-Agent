import clsx from 'clsx';
import { ArrowUp, Square } from 'lucide-react';
import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react';
import { useStore } from '../state/store';

const LIMIT = 8000;

/**
 * The message box. Enter sends, Shift+Enter adds a line, Escape stops an answer.
 * While an answer streams, the send button becomes Stop.
 */
export function Composer({ variant }: { variant: 'hero' | 'dock' }) {
  const [value, setValue] = useState('');
  const box = useRef<HTMLTextAreaElement>(null);
  const send = useStore((s) => s.send);
  const stop = useStore((s) => s.stop);
  const setTyping = useStore((s) => s.setTyping);
  const focusTick = useStore((s) => s.focusTick);
  const streaming = useStore((s) => s.threads[s.activeKey]?.turns.some((t) => t.state === 'streaming') ?? false);
  const stopping = useStore((s) => s.threads[s.activeKey]?.turns.some((t) => t.stopping) ?? false);

  useEffect(() => {
    box.current?.focus();
  }, [focusTick]);

  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    el.style.height = '0px';
    el.style.height = `${Math.min(el.scrollHeight, variant === 'hero' ? 220 : 260)}px`;
  }, [value, variant]);

  useEffect(() => () => setTyping(false), [setTyping]);

  const tooLong = value.length > LIMIT;
  const canSend = value.trim().length > 0 && !tooLong && !streaming;

  const submit = () => {
    if (!canSend) return;
    send(value);
    setValue('');
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      submit();
    } else if (e.key === 'Escape' && streaming) {
      e.preventDefault();
      stop();
    }
  };

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
      className={clsx(
        'group relative rounded-2xl border bg-panel transition-[border-color,box-shadow] duration-200',
        'border-hairline focus-within:border-holo/60 focus-within:shadow-[0_0_0_4px_color-mix(in_oklab,var(--color-holo)_12%,transparent),0_18px_60px_-30px_var(--color-holo)]',
        variant === 'hero' ? 'p-3.5' : 'p-2.5 shadow-[0_-12px_40px_-24px_rgba(0,0,0,0.8)]',
      )}
    >
      <label htmlFor={`composer-${variant}`} className="sr-only">
        Message
      </label>
      <textarea
        id={`composer-${variant}`}
        ref={box}
        rows={1}
        value={value}
        onChange={(e) => {
          setValue(e.target.value);
          if (variant === 'hero') setTyping(e.target.value.trim().length > 0);
        }}
        onKeyDown={onKeyDown}
        placeholder={variant === 'hero' ? 'Ask about anything current, or about something you asked before' : 'Ask a follow-up'}
        className={clsx(
          'block w-full resize-none bg-transparent px-1.5 text-text placeholder:text-faint focus:outline-none',
          variant === 'hero' ? 'min-h-[3.25rem] py-1 text-[1.02rem]' : 'min-h-[2.4rem] py-2 text-[0.97rem]',
        )}
      />
      <div className="mt-1.5 flex items-center gap-3 pl-1.5">
        <span className={clsx('text-xs', tooLong ? 'text-changed' : 'text-faint')}>
          {tooLong
            ? `${value.length.toLocaleString()} of ${LIMIT.toLocaleString()} characters`
            : streaming
              ? 'Esc stops the answer'
              : variant === 'hero'
                ? 'Enter to send, Shift+Enter for a new line'
                : ''}
        </span>
        <span className="ml-auto" />
        {streaming ? (
          <button
            type="button"
            onClick={stop}
            disabled={stopping}
            className="grid h-9 w-9 place-items-center rounded-full border border-line-strong bg-raised text-text transition-colors hover:border-holo/60"
            aria-label="Stop the answer"
            title="Stop"
          >
            <Square size={13} fill="currentColor" />
          </button>
        ) : (
          <button type="submit" disabled={!canSend} className="btn btn-primary h-9 w-9 rounded-full p-0" aria-label="Send">
            <ArrowUp size={18} strokeWidth={2.4} />
          </button>
        )}
      </div>
    </form>
  );
}
