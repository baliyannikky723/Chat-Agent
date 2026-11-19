import clsx from 'clsx';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { domainHue, faviconUrl, prettyDomain } from '../lib/sites';

/** A site's favicon, or a lettered badge in a stable colour when it does not load. */
export function Favicon({ domain, size = 16, className }: { domain: string; size?: number; className?: string }) {
  const [failed, setFailed] = useState(false);
  const clean = prettyDomain(domain);
  if (failed || !clean) {
    return (
      <span
        aria-hidden="true"
        className={clsx('inline-grid shrink-0 place-items-center rounded-[4px] font-bold text-white/90', className)}
        style={{ width: size, height: size, fontSize: Math.max(8, size * 0.55), background: `hsl(${domainHue(clean)} 42% 34%)` }}
      >
        {clean.charAt(0).toUpperCase()}
      </span>
    );
  }
  return (
    <img
      src={faviconUrl(clean)}
      width={size}
      height={size}
      alt=""
      loading="lazy"
      referrerPolicy="no-referrer"
      onError={() => setFailed(true)}
      className={clsx('shrink-0 rounded-[4px] bg-white/5', className)}
    />
  );
}

export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  wide = false,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
}) {
  const id = useId();
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    requestAnimationFrame(() => panel.current?.querySelector<HTMLElement>('[data-autofocus], input, button')?.focus());
    return () => {
      window.removeEventListener('keydown', onKey);
      previous?.focus?.();
    };
  }, [open, onClose]);

  if (!open) return null;
  return createPortal(
    <div className="fixed inset-0 z-50 grid place-items-center p-4">
      <div className="animate-fade-in absolute inset-0 bg-ink-deep/75 backdrop-blur-[3px]" onClick={onClose} />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={id}
        className={clsx(
          'animate-rise-in relative w-full rounded-2xl border border-hairline bg-panel p-6 shadow-[0_30px_80px_-20px_rgba(0,0,0,0.7)]',
          wide ? 'max-w-xl' : 'max-w-md',
        )}
      >
        <h2 id={id} className="font-display text-[1.35rem] font-[560] leading-tight">
          {title}
        </h2>
        {description && <div className="mt-2 text-sm leading-relaxed text-muted">{description}</div>}
        {children && <div className="mt-5">{children}</div>}
        {footer && <div className="mt-6 flex flex-wrap justify-end gap-2">{footer}</div>}
      </div>
    </div>,
    document.body,
  );
}

export interface MenuItem {
  label: string;
  icon?: ReactNode;
  danger?: boolean;
  onSelect: () => void;
}

/** A small dropdown for row actions. Closes on outside click and Escape. */
export function Menu({ items, label, children, align = 'right' }: { items: MenuItem[]; label: string; children: ReactNode; align?: 'left' | 'right' }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);
  return (
    <div ref={root} className="relative">
      <button type="button" className="icon-btn" aria-label={label} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        {children}
      </button>
      {open && (
        <div role="menu" className={clsx('absolute top-full z-40 mt-1 min-w-44 rounded-xl border border-hairline bg-raised p-1 shadow-2xl', align === 'right' ? 'right-0' : 'left-0')}>
          {items.map((item) => (
            <button
              key={item.label}
              role="menuitem"
              type="button"
              className={clsx(
                'flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-sm transition-colors hover:bg-hairline/60',
                item.danger ? 'text-changed' : 'text-text',
              )}
              onClick={() => {
                setOpen(false);
                item.onSelect();
              }}
            >
              {item.icon}
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** Seconds since a start time, ticking while active. */
export function useElapsed(startedAt: number, active: boolean): number {
  const [now, setNow] = useState(() => performance.now());
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => setNow(performance.now()), 100);
    return () => clearInterval(timer);
  }, [active]);
  return Math.max(0, (now - startedAt) / 1000);
}
