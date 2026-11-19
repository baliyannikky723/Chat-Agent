import clsx from 'clsx';
import { useStore } from '../state/store';

export function Toasts() {
  const toasts = useStore((s) => s.toasts);
  const dismiss = useStore((s) => s.dismissToast);
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-24 z-50 flex flex-col items-center gap-2 px-4" aria-live="polite">
      {toasts.map((t) => (
        <button
          key={t.id}
          type="button"
          onClick={() => dismiss(t.id)}
          className={clsx(
            'animate-rise-in pointer-events-auto max-w-md rounded-xl border px-4 py-2.5 text-sm shadow-2xl backdrop-blur',
            t.tone === 'error' ? 'border-changed/40 bg-[#2a1623]/95 text-changed' : 'border-hairline bg-raised/95 text-text',
          )}
        >
          {t.message}
        </button>
      ))}
    </div>
  );
}
