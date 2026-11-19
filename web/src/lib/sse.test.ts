import { describe, expect, it } from 'vitest';
import { SseParser } from './sse';

describe('SseParser', () => {
  it('reads named events, as Spring Boot writes them', () => {
    const p = new SseParser();
    const out = p.push('event:turn.started\ndata:{"type":"turn.started"}\n\nevent:text.delta\ndata:{"text":"Hi"}\n\n');
    expect(out).toEqual([
      { event: 'turn.started', data: '{"type":"turn.started"}' },
      { event: 'text.delta', data: '{"text":"Hi"}' },
    ]);
  });

  it('joins events split across network chunks, including a CRLF cut in half', () => {
    const p = new SseParser();
    expect(p.push('event: status\r')).toEqual([]);
    expect(p.push('\ndata: {"a"')).toEqual([]);
    expect(p.push(':1}\r\n\r\n')).toEqual([{ event: 'status', data: '{"a":1}' }]);
  });

  it('skips keep-alive comments and joins multi-line data', () => {
    const p = new SseParser();
    expect(p.push(':ping\n\ndata: one\ndata: two\n\n')).toEqual([{ event: 'message', data: 'one\ntwo' }]);
  });

  it('delivers a last event that was not followed by a blank line', () => {
    const p = new SseParser();
    expect(p.push('event: done\ndata: {}')).toEqual([]);
    expect(p.end()).toEqual({ event: 'done', data: '{}' });
  });
});
