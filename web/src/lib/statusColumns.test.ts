import type { Element, Root } from 'hast';
import { describe, expect, it } from 'vitest';
import { rehypeStatusColumns, statusOf } from './statusColumns';

const cell = (tag: 'th' | 'td', text: string): Element => ({ type: 'element', tagName: tag, properties: {}, children: [{ type: 'text', value: text }] });
const row = (tag: 'th' | 'td', ...texts: string[]): Element => ({ type: 'element', tagName: 'tr', properties: {}, children: texts.map((t) => cell(tag, t)) });

describe('status columns', () => {
  it('reads status words', () => {
    expect(statusOf('Unchanged')).toBe('ok');
    expect(statusOf('**Changed**')).toBe('changed');
    expect(statusOf('New')).toBe('new');
    expect(statusOf('Could not verify')).toBe('unknown');
    expect(statusOf('A long sentence that happens to start with new things')).toBeNull();
  });

  it('marks only the cells under a status heading', () => {
    const table: Element = {
      type: 'element',
      tagName: 'table',
      properties: {},
      children: [
        { type: 'element', tagName: 'thead', properties: {}, children: [row('th', 'Item', 'Now', 'Status')] },
        { type: 'element', tagName: 'tbody', properties: {}, children: [row('td', 'Neon', 'launched this week', 'New'), row('td', 'Supabase', '$25', 'Unchanged')] },
      ],
    };
    const tree: Root = { type: 'root', children: [table] };
    rehypeStatusColumns()(tree);
    const body = table.children[1] as Element;
    const statuses = (body.children as Element[]).map((tr) => (tr.children as Element[]).map((td) => td.properties.dataStatus ?? null));
    expect(statuses).toEqual([
      [null, null, 'new'],
      [null, null, 'ok'],
    ]);
  });
});
