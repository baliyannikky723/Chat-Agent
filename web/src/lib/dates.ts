/**
 * Calendar helpers. Dates from the server are local calendar days ("2026-11-10") in the
 * user's timezone, so they are formatted as plain dates (pinned to UTC noon) and never
 * shifted by the browser's own offset.
 */

const locale = () => (typeof navigator !== 'undefined' && navigator.language) || 'en-GB';

export function parseDay(day: string): Date {
  const [y, m, d] = day.split('-').map(Number);
  return new Date(Date.UTC(y, (m || 1) - 1, d || 1, 12));
}

/** Whole days from a to b (b - a), both "YYYY-MM-DD". */
export function daysBetween(a: string, b: string): number {
  return Math.round((parseDay(b).getTime() - parseDay(a).getTime()) / 86_400_000);
}

/** The calendar day of an ISO timestamp that already carries the user's offset ("2026-11-14T21:04:05+05:30"). */
export function dayOf(offsetIso: string): string {
  return offsetIso.slice(0, 10);
}

/** The calendar day of an instant in a given timezone. */
export function dayIn(instantIso: string, timeZone: string): string {
  try {
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(
      new Date(instantIso),
    );
    const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
    return `${get('year')}-${get('month')}-${get('day')}`;
  } catch {
    return instantIso.slice(0, 10);
  }
}

function fmt(day: string, options: Intl.DateTimeFormatOptions): string {
  return new Intl.DateTimeFormat(locale(), { ...options, timeZone: 'UTC' }).format(parseDay(day));
}

/** "Tuesday 10 November", with the year only when it is not the current one. */
export function longDay(day: string, today: string): string {
  const sameYear = day.slice(0, 4) === today.slice(0, 4);
  return fmt(day, { weekday: 'long', day: 'numeric', month: 'long', ...(sameYear ? {} : { year: 'numeric' }) });
}

/** "Tue 10 Nov" */
export function shortDay(day: string, today?: string): string {
  const sameYear = !today || day.slice(0, 4) === today.slice(0, 4);
  return fmt(day, { weekday: 'short', day: 'numeric', month: 'short', ...(sameYear ? {} : { year: 'numeric' }) });
}

/** "10 Nov" */
export function dayMonth(day: string): string {
  return fmt(day, { day: 'numeric', month: 'short' });
}

/** Sidebar and timeline headings: Today, Yesterday, then the weekday and date for the last week. */
export function relativeDayLabel(day: string, today: string): string {
  const diff = daysBetween(day, today);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Yesterday';
  if (diff === -1) return 'Tomorrow';
  return longDay(day, today);
}

/** Conversations grouped by the day they were last active: one group per day for two weeks, then per month. */
export function groupByDay<T>(items: T[], dayOfItem: (item: T) => string, today: string): { label: string; key: string; items: T[] }[] {
  const groups: { label: string; key: string; items: T[] }[] = [];
  const byKey = new Map<string, { label: string; key: string; items: T[] }>();
  for (const item of items) {
    const day = dayOfItem(item);
    const diff = daysBetween(day, today);
    let key: string;
    let label: string;
    if (diff < 14) {
      key = day;
      label = relativeDayLabel(day, today);
    } else {
      key = day.slice(0, 7);
      const sameYear = day.slice(0, 4) === today.slice(0, 4);
      label = fmt(day, { month: 'long', ...(sameYear ? {} : { year: 'numeric' }) });
    }
    let group = byKey.get(key);
    if (!group) {
      group = { key, label, items: [] };
      byKey.set(key, group);
      groups.push(group);
    }
    group.items.push(item);
  }
  return groups;
}

/** "21:04" (or "9:04 PM") in the given timezone. */
export function clockTime(instantIso: string, timeZone: string): string {
  try {
    return new Intl.DateTimeFormat(locale(), { timeZone, hour: 'numeric', minute: '2-digit' }).format(new Date(instantIso));
  } catch {
    return '';
  }
}

/** When a question was asked: "21:04" today, otherwise "Tue 10 Nov, 18:40". */
export function askedAtLabel(instantIso: string, timeZone: string, today: string): string {
  const day = dayIn(instantIso, timeZone);
  const time = clockTime(instantIso, timeZone);
  if (day === today) return time;
  if (daysBetween(day, today) === 1) return `Yesterday, ${time}`;
  return `${shortDay(day, today)}, ${time}`;
}

export function duration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '';
  if (ms < 950) return `${Math.max(1, Math.round(ms / 10) * 10)} ms`;
  const s = ms / 1000;
  if (s < 60) return `${s < 10 ? s.toFixed(1) : Math.round(s)} s`;
  const m = Math.floor(s / 60);
  return `${m} min ${Math.round(s - m * 60)} s`;
}

/** A value for <input type="datetime-local"> from an ISO time with offset: "2026-11-14T21:04". */
export function toLocalInput(offsetIso: string): string {
  return offsetIso.slice(0, 16);
}
