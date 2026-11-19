/**
 * Server-Sent Events over fetch. EventSource can only GET, and a chat turn is a POST
 * with a body, so this reads the stream by hand. The parser follows the SSE spec:
 * lines end in LF, CR or CRLF (even when split across chunks), comment lines start
 * with ":" (the server's keep-alive pings), and a blank line dispatches the event.
 */

export interface SseMessage {
  event: string;
  data: string;
}

export class SseParser {
  private buffer = '';
  private event = '';
  private data: string[] = [];

  /** Feeds a chunk of text and returns the events it completed. */
  push(chunk: string): SseMessage[] {
    this.buffer += chunk;
    const out: SseMessage[] = [];
    for (;;) {
      const lf = this.buffer.indexOf('\n');
      const cr = this.buffer.indexOf('\r');
      let end = -1;
      let width = 1;
      if (cr >= 0 && (lf < 0 || cr < lf)) {
        // A CR at the very end may be the first half of a CRLF: wait for the next chunk.
        if (cr === this.buffer.length - 1) break;
        end = cr;
        width = this.buffer[cr + 1] === '\n' ? 2 : 1;
      } else if (lf >= 0) {
        end = lf;
      }
      if (end < 0) break;
      const line = this.buffer.slice(0, end);
      this.buffer = this.buffer.slice(end + width);
      const message = this.line(line);
      if (message) out.push(message);
    }
    return out;
  }

  /** Ends the stream; returns a last event if the server did not finish it with a blank line. */
  end(): SseMessage | null {
    if (this.buffer) {
      const message = this.line(this.buffer);
      this.buffer = '';
      if (message) return message;
    }
    return this.dispatch();
  }

  private line(line: string): SseMessage | null {
    if (line === '') return this.dispatch();
    if (line.startsWith(':')) return null;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') this.event = value;
    else if (field === 'data') this.data.push(value);
    return null;
  }

  private dispatch(): SseMessage | null {
    if (this.data.length === 0) {
      this.event = '';
      return null;
    }
    const message = { event: this.event || 'message', data: this.data.join('\n') };
    this.event = '';
    this.data = [];
    return message;
  }
}

/** Reads a fetch Response body as SSE, calling onMessage for each event until the stream ends. */
export async function readSse(response: Response, onMessage: (message: SseMessage) => void): Promise<void> {
  if (!response.body) throw new Error('The server sent no stream.');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      for (const message of parser.push(decoder.decode(value, { stream: true }))) onMessage(message);
    }
    const tail = parser.push(decoder.decode());
    for (const message of tail) onMessage(message);
    const last = parser.end();
    if (last) onMessage(last);
  } finally {
    reader.releaseLock();
  }
}
