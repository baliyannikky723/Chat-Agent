import { useState } from 'react';
import { prettyDomain } from '../lib/sites';
import type { SourceRef } from '../lib/types';
import { Favicon } from './ui';

/** The pages an answer cites, in citation order; the rest of what was read is one click away. */
export function Sources({ sources, cited }: { sources: SourceRef[]; cited: number[] }) {
  const [all, setAll] = useState(false);
  if (sources.length === 0) return null;
  const citedSet = new Set(cited);
  const primary = sources.filter((s) => citedSet.has(s.n));
  const rest = sources.filter((s) => !citedSet.has(s.n));
  const shown = all ? [...primary, ...rest] : primary.length ? primary : sources.slice(0, 4);
  const hidden = sources.length - shown.length;

  return (
    <div className="mt-5">
      <p className="mb-2 text-xs text-faint">{primary.length ? (primary.length === 1 ? 'Cited source' : `${primary.length} cited sources`) : 'Sources read'}</p>
      <div className="flex flex-wrap gap-1.5">
        {shown.map((s) => (
          <a
            key={s.n}
            href={s.url}
            target="_blank"
            rel="noreferrer noopener"
            title={s.title}
            className="group inline-flex max-w-[17rem] items-center gap-2 rounded-lg border border-hairline bg-panel/50 py-1.5 pr-2.5 pl-2 text-xs transition-colors hover:border-line-strong hover:bg-panel"
          >
            <span className="tnum font-bold text-holo">{s.n}</span>
            <Favicon domain={s.domain} size={14} />
            <span className="truncate text-muted transition-colors group-hover:text-text">{s.title || prettyDomain(s.domain)}</span>
          </a>
        ))}
        {hidden > 0 && (
          <button type="button" onClick={() => setAll(true)} className="rounded-lg px-2 py-1.5 text-xs text-faint transition-colors hover:text-text">
            {hidden === 1 ? '1 more read' : `${hidden} more read`}
          </button>
        )}
      </div>
    </div>
  );
}
