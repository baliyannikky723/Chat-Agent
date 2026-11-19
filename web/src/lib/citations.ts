/**
 * The model cites sources as [1], [2] or [1, 3]. Before rendering, those markers become
 * links to "#cite-n" so the Markdown renderer can draw them as citation chips. Code spans
 * and fenced blocks are left alone, as are real links ("[1](...)") and reference
 * definitions ("[1]: ..."). Numbers outside the known sources stay plain text.
 */

const MARKER = /\[(\d{1,3}(?:\s*[,;]\s*\d{1,3})*)\](?![(:])/g;
const CODE = /(```[\s\S]*?(?:```|$)|`[^`\n]*`)/g;

export function linkCitations(markdown: string, sourceCount: number): string {
  if (sourceCount <= 0 || !markdown.includes('[')) return markdown;
  return markdown
    .split(CODE)
    .map((part, index) => {
      if (index % 2 === 1) return part; // a code span or block, captured by the split
      return part.replace(MARKER, (match, numbers: string) => {
        const ns = numbers.split(/\s*[,;]\s*/).map(Number);
        if (ns.some((n) => !Number.isInteger(n) || n < 1 || n > sourceCount)) return match;
        return ns.map((n) => `[${n}](#cite-${n})`).join('');
      });
    })
    .join('');
}

export function citationNumber(href: string | undefined): number | null {
  if (!href) return null;
  const match = /^#cite-(\d+)$/.exec(href);
  return match ? Number(match[1]) : null;
}

/** A run of adjacent markers, with the space before it: " [1][2]" or " [1, 3]". */
const RUN = /\s*((?:\[\d{1,3}(?:\s*[,;]\s*\d{1,3})*\](?![(:]))+)/g;

/** Plain text for copying: each run of markers becomes the sources' domains once, as in "(neon.com, supabase.com)". */
export function textForCopy(markdown: string, sources: { n: number; domain: string }[]): string {
  const byN = new Map(sources.map((s) => [s.n, s.domain]));
  return markdown.replace(RUN, (match, run: string) => {
    const domains = [...run.matchAll(/\d{1,3}/g)].map((m) => byN.get(Number(m[0]))).filter((d): d is string => Boolean(d));
    return domains.length ? ` (${[...new Set(domains)].join(', ')})` : match;
  });
}
