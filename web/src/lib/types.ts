/**
 * Wire types. These mirror the Java side exactly:
 *   events   → backend/.../core/events/AgentEvent.java
 *   REST     → backend/.../web/ApiDtos.java
 * Nullable fields are always present (the backend writes null, never omits).
 */

export interface SourceRef {
  n: number;
  url: string;
  title: string;
  domain: string;
  publishedAt: string | null;
  snippet: string | null;
}

export interface Reference {
  phrase: string;
  resolved: string;
}

export interface RecallHit {
  conversationId: string;
  userMessageId: string;
  title: string | null;
  date: string;
  label: string;
  question: string;
  claims: number;
}

export type RecallStatus = 'found' | 'none' | 'mismatch';

export interface RecallSummary {
  status: RecallStatus;
  references: Reference[];
  hits: RecallHit[];
  note: string | null;
}

export interface TurnMetrics {
  ttftMs: number | null;
  totalMs: number;
  searches: number;
  sources: number;
  steps: number;
  provider: string;
  model: string;
  inputTokens: number | null;
  outputTokens: number | null;
}

export interface TurnError {
  /** llm_auth | llm_rate_limited | llm_model_not_found | llm_unavailable | llm_bad_request | not_configured | busy | internal | network */
  code: string;
  message: string;
  hint: string | null;
  retryable: boolean;
}

export type Phase = 'recalling' | 'thinking' | 'searching' | 'reading' | 'writing' | 'seeding' | 'done';

export type AgentEvent =
  | { type: 'turn.started'; turnId: string; conversationId: string; userMessageId: string; now: string; isNewConversation: boolean }
  | { type: 'memory.recalled'; recall: RecallSummary }
  | { type: 'status'; phase: Phase; label: string }
  | { type: 'search.started'; id: string; query: string; recency: string | null; since: string | null }
  | { type: 'search.completed'; id: string; query: string; sources: SourceRef[]; cached: boolean; ms: number; error: string | null }
  | { type: 'text.delta'; text: string }
  | { type: 'text.reset' }
  | { type: 'warning'; message: string }
  | { type: 'conversation.titled'; conversationId: string; title: string }
  | { type: 'turn.completed'; assistantMessageId: string; text: string; sources: SourceRef[]; cited: number[]; metrics: TurnMetrics }
  | { type: 'turn.failed'; error: TurnError; partialText: string | null };

export type AgentEventType = AgentEvent['type'];

// ── REST ──────────────────────────────────────────────────────────────────

export interface ConversationDto {
  id: string;
  title: string | null;
  createdAt: string;
  updatedAt: string;
  lastLocalDate: string;
  turns: number;
}

export interface SearchDto {
  query: string;
  since: string | null;
  recency: string | null;
  results: number;
  cached: boolean;
  ms: number;
  error: string | null;
}

export interface MessageDto {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  /** complete | stopped | error */
  status: string;
  createdAt: string;
  localDate: string;
  sources: SourceRef[];
  cited: number[];
  searches: SearchDto[];
  metrics: TurnMetrics | null;
  recall: RecallSummary | null;
}

export interface ConversationDetailDto {
  id: string;
  title: string;
  createdAt: string;
  messages: MessageDto[];
}

export interface TimelineTurnDto {
  conversationId: string;
  conversationTitle: string | null;
  userMessageId: string;
  assistantMessageId: string | null;
  question: string;
  time: string;
  claims: number;
}

export interface TimelineDayDto {
  date: string;
  label: string;
  turns: TimelineTurnDto[];
}

export interface LedgerPointDto {
  value: string;
  statement: string;
  asOf: string;
  asOfLocal: string;
  sourceUrl: string | null;
  conversationId: string;
  messageId: string;
}

export interface LedgerEntryDto {
  entity: string;
  attribute: string;
  history: LedgerPointDto[];
}

export interface StatusDto {
  ready: boolean;
  llm: { configured: boolean; provider: string | null; model: string | null; fastModel: string | null; missing: string | null };
  search: { configured: boolean; providers: string[]; missing: string | null };
  clock: { demoTools: boolean; offsetMs: number; now: string; timezone: string };
  version: string;
}

export interface ApiErrorBody {
  error: { code: string; message: string };
}
