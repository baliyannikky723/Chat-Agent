import { describe, expect, it } from 'vitest';
import type { MessageDto } from '../lib/types';
import { turnsFromMessages } from './store';

const message = (partial: Partial<MessageDto> & Pick<MessageDto, 'id' | 'role' | 'content'>): MessageDto => ({
  status: 'complete',
  createdAt: '2026-11-10T13:10:00.000Z',
  localDate: '2026-11-10',
  sources: [],
  cited: [],
  searches: [],
  metrics: null,
  recall: null,
  ...partial,
});

describe('turnsFromMessages', () => {
  it('pairs each question with its answer and its searches', () => {
    const turns = turnsFromMessages([
      message({ id: 'u1', role: 'user', content: 'What does Neon cost?' }),
      message({
        id: 'a1',
        role: 'assistant',
        content: 'Neon is $19 [1].',
        cited: [1],
        searches: [{ query: 'Neon pricing', since: null, recency: 'month', results: 6, cached: false, ms: 420, error: null }],
      }),
      message({ id: 'u2', role: 'user', content: 'And Supabase?' }),
      message({ id: 'a2', role: 'assistant', content: 'Partial', status: 'stopped' }),
    ]);
    expect(turns.map((t) => [t.question, t.state, t.answerId])).toEqual([
      ['What does Neon cost?', 'done', 'a1'],
      ['And Supabase?', 'stopped', 'a2'],
    ]);
    expect(turns[0].searches[0]).toMatchObject({ query: 'Neon pricing', state: 'done', results: 6 });
  });

  it('marks a question that never got an answer as retryable', () => {
    const [turn] = turnsFromMessages([message({ id: 'u1', role: 'user', content: 'Hello?' })]);
    expect(turn.state).toBe('failed');
    expect(turn.error?.retryable).toBe(true);
  });
});
