/** Small helpers for showing where a source comes from. */

export function faviconUrl(domain: string): string {
  return `https://icons.duckduckgo.com/ip3/${encodeURIComponent(domain)}.ico`;
}

/** A stable hue per domain, for the letter badge shown when a favicon does not load. */
export function domainHue(domain: string): number {
  let h = 0;
  for (let i = 0; i < domain.length; i++) h = (h * 31 + domain.charCodeAt(i)) >>> 0;
  return h % 360;
}

export function prettyDomain(domain: string): string {
  return domain.replace(/^www\./, '');
}
