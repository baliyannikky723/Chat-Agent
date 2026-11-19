import clsx from 'clsx';

/**
 * The working indicator: a tilted orbit that turns while Orrery is busy.
 * Tone follows meaning: cyan for the web, brass for memory.
 */
export function MiniOrrery({
  size = 18,
  active = true,
  tone = 'holo',
  className,
}: {
  size?: number;
  active?: boolean;
  tone?: 'holo' | 'brass' | 'muted';
  className?: string;
}) {
  const color = tone === 'brass' ? 'var(--color-brass)' : tone === 'muted' ? 'var(--color-faint)' : 'var(--color-holo)';
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" className={clsx('shrink-0', className)} aria-hidden="true">
      <circle cx="12" cy="12" r="3.1" fill={color} opacity={active ? 1 : 0.7} />
      <g className={active ? 'animate-orbit' : undefined} style={{ transformOrigin: '12px 12px', transformBox: 'view-box' }}>
        <ellipse cx="12" cy="12" rx="10" ry="4" transform="rotate(-25 12 12)" fill="none" stroke={color} strokeOpacity=".5" strokeWidth="1.4" />
        <circle cx="19.1" cy="12.05" r="1.9" fill={tone === 'brass' ? 'var(--color-brass-soft)' : 'var(--color-brass)'} />
      </g>
    </svg>
  );
}
