import type { Element, ElementContent, Root } from 'hast';

/**
 * Status cells in comparison tables. A model writes verification tables like
 * "| Item | Then | Now | Status |"; the cells under a status heading are drawn as
 * coloured pills, and nothing else is.
 */

export type CellStatus = 'ok' | 'changed' | 'new' | 'unknown';

/** What a status cell says, reduced to one of four states. Long cells are never statuses. */
export function statusOf(text: string): CellStatus | null {
  const t = text
    .trim()
    .toLowerCase()
    .replace(/[*_✓✔✗✘⚠️]/g, '')
    .trim();
  if (!t || t.length > 34) return null;
  if (/^(unchanged|no change|same|still (true|correct|valid|current|accurate)|confirmed|current)\b/.test(t)) return 'ok';
  if (/^(changed|updated|price (increase|decrease|change|changed)|increased|decreased|raised|lowered|removed|discontinued|deprecated|no longer|retired|renamed|outdated|corrected)/.test(t))
    return 'changed';
  if (/^(new|launched|added|shipped|released|announced)\b/.test(t)) return 'new';
  if (/^(unverified|unknown|unclear|not (found|verified|checked)|could not (verify|check|confirm)|no (data|info|source)|n\/a)/.test(t)) return 'unknown';
  return null;
}

const STATUS_HEADER = /^(status|change|changes|changed\??|verdict|result|still (true|correct|valid|accurate)\??|what changed)$/i;

function textOf(node: Element | ElementContent): string {
  if (node.type === 'text') return node.value;
  if (node.type === 'element') return node.children.map(textOf).join('');
  return '';
}

function elements(node: Element, tag: string): Element[] {
  const out: Element[] = [];
  for (const child of node.children) {
    if (child.type !== 'element') continue;
    if (child.tagName === tag) out.push(child);
    else out.push(...elements(child, tag));
  }
  return out;
}

/**
 * Finds tables whose header has a status column ("Status", "Changed?", "Still true?") and
 * tags the cells in that column, so only they are drawn as pills. A "Now" column that says
 * "launched this week" stays plain text.
 */
export function rehypeStatusColumns() {
  return (tree: Root) => {
    const visit = (children: Root['children'] | Element['children']) => {
      for (const child of children) {
        if (child.type !== 'element') continue;
        if (child.tagName !== 'table') {
          visit(child.children);
          continue;
        }
        const rows = elements(child, 'tr');
        const header = rows.find((tr) => tr.children.some((c) => c.type === 'element' && c.tagName === 'th'));
        if (!header) continue;
        const headCells = header.children.filter((c): c is Element => c.type === 'element');
        const columns = headCells.flatMap((cell, i) => (STATUS_HEADER.test(textOf(cell).trim()) ? [i] : []));
        if (columns.length === 0) continue;
        for (const tr of rows) {
          if (tr === header) continue;
          const cells = tr.children.filter((c): c is Element => c.type === 'element');
          for (const i of columns) {
            const cell = cells[i];
            const status = cell && statusOf(textOf(cell));
            if (cell && status) cell.properties = { ...cell.properties, dataStatus: status };
          }
        }
      }
    };
    visit(tree.children);
  };
}
