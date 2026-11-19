import { describe, expect, it } from 'vitest';
import { citationNumber, linkCitations, textForCopy } from './citations';

describe('linkCitations', () => {
  it('turns markers into citation links', () => {
    expect(linkCitations('Neon costs $19 [1], Supabase $25 [2].', 2)).toBe('Neon costs $19 [1](#cite-1), Supabase $25 [2](#cite-2).');
  });

  it('splits grouped markers and keeps adjacent ones', () => {
    expect(linkCitations('Both [1, 3] and [2][3].', 3)).toBe('Both [1](#cite-1)[3](#cite-3) and [2](#cite-2)[3](#cite-3).');
  });

  it('leaves code, real links, definitions and unknown numbers alone', () => {
    const text = 'Use `arr[1]` here.\n\n```\nx = a[2]\n```\n\nSee [1](https://x.dev) and [7].\n\n[2]: https://y.dev';
    expect(linkCitations(text, 3)).toBe(text);
  });

  it('reads the number back from a link', () => {
    expect(citationNumber('#cite-12')).toBe(12);
    expect(citationNumber('https://neon.com')).toBeNull();
  });

  it('copies with each source domain once instead of numbers', () => {
    const sources = [
      { n: 1, domain: 'neon.com' },
      { n: 2, domain: 'neon.com' },
      { n: 3, domain: 'supabase.com' },
    ];
    expect(textForCopy('Neon is $19 [1][2].', sources)).toBe('Neon is $19 (neon.com).');
    expect(textForCopy('Both moved [1, 3], see [9].', sources)).toBe('Both moved (neon.com, supabase.com), see [9].');
  });
});
