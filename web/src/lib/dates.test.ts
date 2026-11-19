import { describe, expect, it } from 'vitest';
import { dayIn, daysBetween, duration, groupByDay, relativeDayLabel } from './dates';

describe('dates', () => {
  it('counts calendar days without timezone drift', () => {
    expect(daysBetween('2026-11-10', '2026-11-14')).toBe(4);
    expect(daysBetween('2026-03-28', '2026-03-30')).toBe(2); // across a DST change
  });

  it('finds the local day of an instant in the user’s timezone', () => {
    expect(dayIn('2026-11-14T20:00:00Z', 'Asia/Kolkata')).toBe('2026-11-15');
    expect(dayIn('2026-11-14T20:00:00Z', 'America/New_York')).toBe('2026-11-14');
  });

  it('labels the last two days in words', () => {
    expect(relativeDayLabel('2026-11-14', '2026-11-14')).toBe('Today');
    expect(relativeDayLabel('2026-11-13', '2026-11-14')).toBe('Yesterday');
  });

  it('groups by day for two weeks, then by month', () => {
    const items = ['2026-11-14', '2026-11-10', '2026-11-03', '2026-10-02'];
    const groups = groupByDay(items, (d) => d, '2026-11-14');
    expect(groups.map((g) => g.key)).toEqual(['2026-11-14', '2026-11-10', '2026-11-03', '2026-10']);
  });

  it('formats durations the way people read them', () => {
    expect(duration(420)).toBe('420 ms');
    expect(duration(4200)).toBe('4.2 s');
    expect(duration(64_000)).toBe('1 min 4 s');
  });
});
