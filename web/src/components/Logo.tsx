import { useId } from 'react';

/**
 * The mark: a glowing core (now), one tilted orbit drawn in front of and behind it,
 * and a brass planet (the past). The same drawing is the favicon.
 */
export function LogoMark({ size = 28, className }: { size?: number; className?: string }) {
  const id = useId().replace(/:/g, '');
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" className={className} aria-hidden="true">
      <defs>
        <radialGradient id={`${id}-core`} cx="40%" cy="36%" r="72%">
          <stop offset="0" stopColor="#F4FDFF" />
          <stop offset=".45" stopColor="#7FE3F0" />
          <stop offset="1" stopColor="#B7A6FF" />
        </radialGradient>
        <filter id={`${id}-glow`} x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="1.6" />
        </filter>
      </defs>
      <ellipse cx="16" cy="16" rx="13" ry="5.2" transform="rotate(-24 16 16)" fill="none" stroke="#7FE3F0" strokeOpacity=".35" strokeWidth="1.5" />
      <circle cx="16" cy="16" r="5.4" fill="#7FE3F0" opacity=".55" filter={`url(#${id}-glow)`} />
      <circle cx="16" cy="16" r="5.4" fill={`url(#${id}-core)`} />
      <path d="M27.88 10.71A13 5.2 -24 0 1 4.12 21.29" fill="none" stroke="#7FE3F0" strokeWidth="1.6" strokeLinecap="round" />
      <circle cx="23.8" cy="17.5" r="2.4" fill="#D9B36C" />
    </svg>
  );
}

export function Wordmark({ className }: { className?: string }) {
  return (
    <span className={`inline-flex items-center gap-2.5 ${className ?? ''}`}>
      <LogoMark size={28} />
      <span className="font-display text-[1.32rem] font-[620] leading-none tracking-[-0.02em] text-text">Orrery</span>
    </span>
  );
}
