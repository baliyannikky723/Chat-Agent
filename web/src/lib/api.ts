import { readSse } from './sse';
import type {
  AgentEvent,
  ApiErrorBody,
  ConversationDetailDto,
  ConversationDto,
  LedgerEntryDto,
  StatusDto,
  TimelineDayDto,
} from './types';

/**
 * The REST and streaming API. Every request carries the browser's IANA timezone and
 * locale: "last Tuesday" and "3/11" are resolved on the server in the user's own calendar.
 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

function timezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

export function contextHeaders(): Record<string, string> {
  return { 'X-Timezone': timezone(), 'X-Locale': navigator.language || 'en-GB' };
}

async function failure(response: Response): Promise<ApiError> {
  let code = 'http_' + response.status;
  let message = response.status >= 500 ? 'The server had a problem.' : 'The request failed.';
  try {
    const body = (await response.json()) as Partial<ApiErrorBody>;
    if (body.error?.message) {
      code = body.error.code;
      message = body.error.message;
    }
  } catch {
    // not JSON: keep the generic message
  }
  if (response.status === 502 || response.status === 503 || response.status === 504) {
    message = 'The backend is not reachable. Is it still starting?';
    code = 'backend_down';
  }
  return new ApiError(response.status, code, message);
}

async function request<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: { ...contextHeaders(), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), Accept: 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError(0, 'network', 'Cannot reach the Orrery backend.');
  }
  if (!response.ok) throw await failure(response);
  return (await response.json()) as T;
}

export const api = {
  status: () => request<StatusDto>('GET', '/api/status'),
  conversations: () => request<ConversationDto[]>('GET', '/api/conversations'),
  conversation: (id: string, signal?: AbortSignal) => request<ConversationDetailDto>('GET', `/api/conversations/${id}`, undefined, signal),
  rename: (id: string, title: string) => request<{ ok: boolean }>('PATCH', `/api/conversations/${id}`, { title }),
  remove: (id: string) => request<{ ok: boolean }>('DELETE', `/api/conversations/${id}`),
  timeline: (days = 365) => request<TimelineDayDto[]>('GET', `/api/memory/timeline?days=${days}`),
  ledger: () => request<LedgerEntryDto[]>('GET', '/api/memory/ledger'),
  forget: () => request<{ ok: boolean }>('DELETE', '/api/memory'),
  cancel: (turnId: string) => request<{ ok: boolean }>('POST', `/api/chat/${turnId}/cancel`),
  setClock: (localDateTime: string | null) =>
    request<StatusDto['clock']>('POST', '/api/dev/clock', { localDateTime }),
};

export interface ChatBody {
  conversationId: string | null;
  message: string;
  recheckMessageId?: string | null;
}

/** Streams a POST endpoint that answers with Server-Sent Events of AgentEvent JSON. */
export async function streamEvents(path: string, body: unknown, onEvent: (event: AgentEvent) => void, signal: AbortSignal): Promise<void> {
  let response: Response;
  try {
    response = await fetch(path, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { ...contextHeaders(), 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(body ?? {}),
      signal,
    });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError(0, 'network', 'Cannot reach the Orrery backend.');
  }
  const type = response.headers.get('content-type') ?? '';
  if (!response.ok || !type.includes('text/event-stream')) throw await failure(response);
  await readSse(response, (message) => {
    try {
      onEvent(JSON.parse(message.data) as AgentEvent);
    } catch {
      // a malformed event is skipped rather than ending the turn
    }
  });
}

export const streamChat = (body: ChatBody, onEvent: (event: AgentEvent) => void, signal: AbortSignal) =>
  streamEvents('/api/chat', body, onEvent, signal);

export const streamSeed = (onEvent: (event: AgentEvent) => void, signal: AbortSignal) =>
  streamEvents('/api/dev/seed-demo', {}, onEvent, signal);
