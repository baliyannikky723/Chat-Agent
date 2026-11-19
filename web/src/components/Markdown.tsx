import clsx from 'clsx';
import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { citationNumber, linkCitations } from '../lib/citations';
import { rehypeStatusColumns, type CellStatus } from '../lib/statusColumns';
import { dayMonth } from '../lib/dates';
import { prettyDomain } from '../lib/sites';
import type { SourceRef } from '../lib/types';
import { Favicon } from './ui';

function publishedLabel(raw: string | null): string | null {
  if (!raw) return null;
  const iso = /^\d{4}-\d{2}-\d{2}/.exec(raw);
  return iso ? dayMonth(iso[0]) : raw.length < 24 ? raw : null;
}

const CARD_WIDTH = 288;

/** A citation number; hovering or focusing it shows the source in a card that floats above the page. */
function CitationChip({ n, source }: { n: number; source: SourceRef | undefined }) {
  const anchor = useRef<HTMLAnchorElement>(null);
  const [place, setPlace] = useState<{ left: number; top: number; below: boolean } | null>(null);

  useEffect(() => {
    if (!place) return;
    const hide = () => setPlace(null);
    window.addEventListener('scroll', hide, true);
    window.addEventListener('resize', hide);
    return () => {
      window.removeEventListener('scroll', hide, true);
      window.removeEventListener('resize', hide);
    };
  }, [place]);

  if (!source) return <span className="cite">{n}</span>;

  const show = () => {
    const r = anchor.current?.getBoundingClientRect();
    if (!r) return;
    const left = Math.min(Math.max(12, r.left + r.width / 2 - CARD_WIDTH / 2), window.innerWidth - CARD_WIDTH - 12);
    const below = r.top < 200;
    setPlace({ left, top: below ? r.bottom + 8 : r.top - 8, below });
  };
  const published = publishedLabel(source.publishedAt);

  return (
    <>
      <a
        ref={anchor}
        className="cite"
        href={source.url}
        target="_blank"
        rel="noreferrer noopener"
        onMouseEnter={show}
        onMouseLeave={() => setPlace(null)}
        onFocus={show}
        onBlur={() => setPlace(null)}
        aria-label={`Source ${n}: ${source.title}`}
      >
        {n}
      </a>
      {place &&
        createPortal(
          <span
            role="tooltip"
            className="animate-fade-in pointer-events-none fixed z-[60] block rounded-xl border border-hairline bg-raised p-3 text-left shadow-[0_18px_50px_-12px_rgba(0,0,0,0.75)]"
            style={{ left: place.left, top: place.top, width: CARD_WIDTH, transform: place.below ? undefined : 'translateY(-100%)' }}
          >
            <span className="flex items-center gap-2 text-xs text-muted">
              <Favicon domain={source.domain} size={14} />
              <span className="truncate">{prettyDomain(source.domain)}</span>
              {published && <span className="ml-auto shrink-0 text-brass-soft">{published}</span>}
            </span>
            <span className="mt-1.5 line-clamp-2 block text-[0.84rem] leading-snug font-semibold text-text">{source.title || source.url}</span>
            {source.snippet && <span className="mt-1 line-clamp-3 block text-xs leading-relaxed text-muted">{source.snippet}</span>}
          </span>,
          document.body,
        )}
    </>
  );
}

const remarkPlugins = [remarkGfm];
const rehypePlugins = [rehypeStatusColumns];

/** An answer: GitHub-flavoured Markdown with citation chips and status pills. */
export const Markdown = memo(function Markdown({ text, sources, streaming = false }: { text: string; sources: SourceRef[]; streaming?: boolean }) {
  const byN = useMemo(() => new Map(sources.map((s) => [s.n, s])), [sources]);
  const maxN = sources.length ? Math.max(...sources.map((s) => s.n)) : 0;
  const linked = useMemo(() => linkCitations(text, maxN), [text, maxN]);

  const components = useMemo<Components>(
    () => ({
      a({ href, children }) {
        const n = citationNumber(href);
        if (n !== null) return <CitationChip n={n} source={byN.get(n)} />;
        return (
          <a href={href} target="_blank" rel="noreferrer noopener">
            {children}
          </a>
        );
      },
      table({ children }) {
        return (
          <div className="table-wrap">
            <table>{children}</table>
          </div>
        );
      },
      td({ children, style, ...rest }) {
        const props = rest as Record<string, unknown>;
        const status = (props['data-status'] ?? props.dataStatus) as CellStatus | undefined;
        return <td style={style}>{status ? <span className={`pill pill-${status}`}>{children}</span> : children}</td>;
      },
    }),
    [byN],
  );

  return (
    <div className={clsx('answer', streaming && 'is-streaming')}>
      <ReactMarkdown remarkPlugins={remarkPlugins} rehypePlugins={rehypePlugins} components={components}>
        {linked}
      </ReactMarkdown>
    </div>
  );
});
