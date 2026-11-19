/**
 * A still drawing of the hologram, shown while three.js loads, when WebGL is not
 * available, and to people who prefer reduced motion. Same composition as the 3D scene.
 */
export function OrreryFallback({ planets = 0, dim = false }: { planets?: number; dim?: boolean }) {
  const brass = Array.from({ length: Math.min(planets, 7) }, (_, i) => {
    const t = (i / Math.max(1, Math.min(planets, 7))) * Math.PI * 2 + 0.6;
    return { x: 160 + Math.cos(t) * 118, y: 128 + Math.sin(t) * 30 };
  });
  return (
    <svg viewBox="0 0 320 260" className="h-full w-full" style={{ opacity: dim ? 0.55 : 1 }} aria-hidden="true">
      <defs>
        <radialGradient id="fb-core" cx="42%" cy="38%" r="70%">
          <stop offset="0" stopColor="#F4FDFF" />
          <stop offset=".5" stopColor="#7FE3F0" />
          <stop offset="1" stopColor="#B7A6FF" stopOpacity=".9" />
        </radialGradient>
        <radialGradient id="fb-glow">
          <stop offset="0" stopColor="#7FE3F0" stopOpacity=".55" />
          <stop offset="1" stopColor="#7FE3F0" stopOpacity="0" />
        </radialGradient>
        <linearGradient id="fb-beam" x1="0" y1="1" x2="0" y2="0">
          <stop offset="0" stopColor="#7FE3F0" stopOpacity=".28" />
          <stop offset="1" stopColor="#7FE3F0" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d="M118 222 L60 40 L260 40 L202 222 Z" fill="url(#fb-beam)" />
      <ellipse cx="160" cy="224" rx="112" ry="18" fill="none" stroke="#7FE3F0" strokeOpacity=".45" />
      <ellipse cx="160" cy="224" rx="44" ry="7" fill="#7FE3F0" fillOpacity=".25" />
      <ellipse cx="160" cy="128" rx="118" ry="30" fill="none" stroke="#D9B36C" strokeOpacity=".55" strokeDasharray="7 5" />
      <ellipse cx="160" cy="128" rx="90" ry="38" transform="rotate(-14 160 128)" fill="none" stroke="#B7A6FF" strokeOpacity=".55" strokeDasharray="6 6" />
      <ellipse cx="160" cy="128" rx="62" ry="18" transform="rotate(10 160 128)" fill="none" stroke="#7FE3F0" strokeOpacity=".6" strokeDasharray="5 5" />
      <circle cx="160" cy="128" r="58" fill="url(#fb-glow)" />
      <circle cx="160" cy="128" r="27" fill="url(#fb-core)" />
      <circle cx="221" cy="118" r="4" fill="#7FE3F0" />
      <circle cx="84" cy="146" r="4.5" fill="#B7A6FF" />
      {brass.map((p, i) => (
        <circle key={i} cx={p.x} cy={p.y} r="4.2" fill="#D9B36C" />
      ))}
    </svg>
  );
}
