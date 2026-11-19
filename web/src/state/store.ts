import { create } from 'zustand';
import { api, ApiError, streamChat, streamSeed } from '../lib/api';
import { dayOf } from '../lib/dates';
import { navigate, paths } from '../lib/router';
import type {
  AgentEvent,
  ConversationDto,
  LedgerEntryDto,
  MessageDto,
  Phase,
  RecallSummary,
  SourceRef,
  StatusDto,
  TimelineDayDto,
  TurnError,
  TurnMetrics,
} from '../lib/types';

/**
 * Client state. A conversation's turns live in a "thread"; threads are kept per
 * conversation so an answer keeps streaming (and is not lost) when you switch away
 * and come back. A new conversation starts as a draft thread and is re-keyed to its
 * server id when the first event arrives.
 */

export interface SearchStep {
  id: string;
  query: string;
  since: string | null;
  recency: string | null;
  state: 'running' | 'done' | 'failed';
  results: number;
  sources: SourceRef[];
  cached: boolean;
  ms: number | null;
  error: string | null;
}

export type TurnState = 'streaming' | 'done' | 'failed' | 'stopped';

export interface Turn {
  key: string;
  question: string;
  askedAt: string | null;
  userMessageId: string | null;
  turnId: string | null;
  /** Set when this turn re-checks an earlier answer. */
  recheckOf: string | null;
  state: TurnState;
  stopping: boolean;
  phase: Phase | null;
  phaseLabel: string | null;
  recall: RecallSummary | null;
  searches: SearchStep[];
  text: string;
  sources: SourceRef[];
  cited: number[];
  metrics: TurnMetrics | null;
  error: TurnError | null;
  warnings: string[];
  answerId: string | null;
  startedAt: number;
}

export interface Thread {
  key: string;
  id: string | null;
  title: string | null;
  turns: Turn[];
  loaded: boolean;
  loading: boolean;
  error: string | null;
}

export interface Toast {
  id: number;
  message: string;
  tone: 'info' | 'error';
}

export type Connection = 'connecting' | 'online' | 'offline';

interface State {
  connection: Connection;
  status: StatusDto | null;
  conversations: ConversationDto[];
  conversationsLoaded: boolean;
  threads: Record<string, Thread>;
  activeKey: string;
  timeline: TimelineDayDto[] | null;
  ledger: LedgerEntryDto[] | null;
  toasts: Toast[];
  /** Bumped to ask the composer to take focus. */
  focusTick: number;
  /** True while the user is typing in an empty conversation; the hologram listens. */
  typing: boolean;

  boot(): Promise<void>;
  refreshStatus(): Promise<StatusDto | null>;
  refreshConversations(): Promise<void>;
  refreshMemory(): Promise<void>;
  refreshLedger(): Promise<void>;
  openConversation(id: string): Promise<void>;
  openDraft(): void;
  send(text: string, options?: { recheckOf?: string | null }): void;
  recheck(turnKey: string): void;
  retry(turnKey: string): void;
  stop(): void;
  rename(id: string, title: string): Promise<void>;
  remove(id: string): Promise<void>;
  forgetEverything(): Promise<void>;
  setClock(localDateTime: string | null): Promise<void>;
  seedDemo(onLine: (line: { tone: 'info' | 'warn' | 'done'; text: string }) => void, signal: AbortSignal): Promise<void>;
  toast(message: string, tone?: Toast['tone']): void;
  dismissToast(id: number): void;
  setTyping(typing: boolean): void;
  focusComposer(): void;
}

const RECHECK_QUESTION = 'Is this still correct today?';

let draftCounter = 0;
let toastCounter = 0;
const newDraftKey = () => `draft:${++draftCounter}`;
const controllers = new Map<string, AbortController>();

function blankTurn(partial: Partial<Turn> & Pick<Turn, 'key' | 'question'>): Turn {
  return {
    askedAt: null,
    userMessageId: null,
    turnId: null,
    recheckOf: null,
    state: 'streaming',
    stopping: false,
    phase: null,
    phaseLabel: null,
    recall: null,
    searches: [],
    text: '',
    sources: [],
    cited: [],
    metrics: null,
    error: null,
    warnings: [],
    answerId: null,
    startedAt: performance.now(),
    ...partial,
  };
}

function firstWords(text: string, count = 7): string {
  const words = text.trim().split(/\s+/);
  const head = words.slice(0, count).join(' ');
  return words.length > count ? `${head.replace(/[.,;:!?]+$/, '')}…` : head;
}

function emptyThread(key: string, id: string | null = null): Thread {
  return { key, id, title: null, turns: [], loaded: id === null, loading: false, error: null };
}

/** Rebuilds turns from stored messages: each question followed by its answer. */
export function turnsFromMessages(messages: MessageDto[]): Turn[] {
  const turns: Turn[] = [];
  for (const m of messages) {
    if (m.role === 'user') {
      turns.push(
        blankTurn({
          key: m.id,
          question: m.content,
          askedAt: m.createdAt,
          userMessageId: m.id,
          turnId: m.id,
          state: 'failed',
          startedAt: 0,
        }),
      );
      continue;
    }
    const turn = turns[turns.length - 1];
    if (!turn || turn.answerId) continue;
    turn.answerId = m.id;
    turn.text = m.content;
    turn.sources = m.sources;
    turn.cited = m.cited;
    turn.metrics = m.metrics;
    turn.recall = m.recall;
    turn.searches = (m.searches ?? []).map((s, i) => ({
      id: `${m.id}:${i}`,
      query: s.query,
      since: s.since,
      recency: s.recency,
      state: s.error ? 'failed' : 'done',
      results: s.results,
      sources: [],
      cached: s.cached,
      ms: s.ms,
      error: s.error,
    }));
    turn.state = m.status === 'complete' ? 'done' : m.status === 'stopped' ? 'stopped' : 'failed';
    if (turn.state === 'failed') {
      turn.error = { code: 'internal', message: 'This answer was interrupted.', hint: null, retryable: true };
    }
    if (turn.question === RECHECK_QUESTION) turn.recheckOf = 'earlier';
  }
  for (const turn of turns) {
    if (!turn.answerId) turn.error = { code: 'internal', message: 'This question did not get an answer.', hint: null, retryable: true };
  }
  return turns;
}

export const useStore = create<State>()((set, get) => {
  // ── small immutable helpers ────────────────────────────────────────────
  const patchThread = (key: string, fn: (t: Thread) => Thread) =>
    set((s) => (s.threads[key] ? { threads: { ...s.threads, [key]: fn(s.threads[key]) } } : {}));

  const locateTurn = (turnKey: string): [string, number] | null => {
    for (const [key, thread] of Object.entries(get().threads)) {
      const index = thread.turns.findIndex((t) => t.key === turnKey);
      if (index >= 0) return [key, index];
    }
    return null;
  };

  const patchTurn = (turnKey: string, fn: (t: Turn) => Turn) => {
    const found = locateTurn(turnKey);
    if (!found) return;
    patchThread(found[0], (thread) => {
      const turns = thread.turns.slice();
      turns[found[1]] = fn(turns[found[1]]);
      return { ...thread, turns };
    });
  };

  /** A draft thread gets its server id: move it, and follow it in the address bar if it is on screen. */
  const adoptConversation = (draftKey: string, id: string) => {
    set((s) => {
      const draft = s.threads[draftKey];
      if (!draft || draft.id) return {};
      const threads = { ...s.threads };
      delete threads[draftKey];
      threads[id] = { ...draft, key: id, id, loaded: true };
      return { threads, activeKey: s.activeKey === draftKey ? id : s.activeKey };
    });
    if (get().activeKey === id) navigate(paths.conversation(id), { replace: true });
  };

  const today = () => (get().status ? dayOf(get().status!.clock.now) : new Date().toISOString().slice(0, 10));

  /** Moves a conversation to the top of the list; a new one shows its first words until the title arrives. */
  const upsertConversation = (id: string, title: string | null, provisional?: string) => {
    set((s) => {
      const existing = s.conversations.find((c) => c.id === id);
      const now = new Date().toISOString();
      const row: ConversationDto = existing
        ? { ...existing, title: title ?? existing.title, updatedAt: now, lastLocalDate: today() }
        : { id, title: title ?? provisional ?? null, createdAt: now, updatedAt: now, lastLocalDate: today(), turns: 0 };
      return { conversations: [row, ...s.conversations.filter((c) => c.id !== id)] };
    });
  };

  // ── one streamed turn ──────────────────────────────────────────────────
  const runTurn = async (threadKey: string, turnKey: string, message: string, recheckOf: string | null) => {
    const controller = new AbortController();
    controllers.set(turnKey, controller);
    const thread = get().threads[threadKey];
    let pending = '';
    let frame = 0;
    let finished = false;

    // Tokens arrive faster than the screen refreshes: render at most once per frame.
    const flush = () => {
      frame = 0;
      if (!pending) return;
      const chunk = pending;
      pending = '';
      patchTurn(turnKey, (t) => ({ ...t, text: t.text + chunk, phase: 'writing', phaseLabel: 'Writing' }));
    };
    const flushNow = () => {
      if (frame) cancelAnimationFrame(frame);
      flush();
    };

    const onEvent = (event: AgentEvent) => {
      if (event.type === 'text.delta') {
        pending += event.text;
        if (!frame) frame = requestAnimationFrame(flush);
        return;
      }
      flushNow();
      switch (event.type) {
        case 'turn.started': {
          patchTurn(turnKey, (t) => ({ ...t, turnId: event.turnId, userMessageId: event.userMessageId, askedAt: event.now }));
          const located = locateTurn(turnKey);
          if (located && !get().threads[located[0]].id) adoptConversation(located[0], event.conversationId);
          upsertConversation(event.conversationId, null, firstWords(message));
          return;
        }
        case 'memory.recalled':
          patchTurn(turnKey, (t) => ({ ...t, recall: event.recall }));
          return;
        case 'status':
          patchTurn(turnKey, (t) => ({ ...t, phase: event.phase, phaseLabel: event.label }));
          return;
        case 'search.started':
          patchTurn(turnKey, (t) => ({
            ...t,
            searches: [
              ...t.searches,
              { id: event.id, query: event.query, since: event.since, recency: event.recency, state: 'running', results: 0, sources: [], cached: false, ms: null, error: null },
            ],
          }));
          return;
        case 'search.completed':
          patchTurn(turnKey, (t) => {
            const known = new Set(t.sources.map((s) => s.n));
            return {
              ...t,
              sources: [...t.sources, ...event.sources.filter((s) => !known.has(s.n))].sort((a, b) => a.n - b.n),
              searches: t.searches.map((s) =>
                s.id === event.id
                  ? { ...s, state: event.error ? 'failed' : 'done', results: event.sources.length, sources: event.sources, cached: event.cached, ms: event.ms, error: event.error }
                  : s,
              ),
            };
          });
          return;
        case 'text.reset':
          pending = '';
          patchTurn(turnKey, (t) => ({ ...t, text: '' }));
          return;
        case 'warning':
          patchTurn(turnKey, (t) => ({ ...t, warnings: [...t.warnings, event.message] }));
          return;
        case 'conversation.titled': {
          const located = locateTurn(turnKey);
          if (located) patchThread(located[0], (th) => ({ ...th, title: event.title }));
          upsertConversation(event.conversationId, event.title);
          return;
        }
        case 'turn.completed':
          finished = true;
          patchTurn(turnKey, (t) => ({
            ...t,
            state: 'done',
            stopping: false,
            phase: 'done',
            text: event.text,
            sources: event.sources,
            cited: event.cited,
            metrics: event.metrics,
            answerId: event.assistantMessageId,
          }));
          return;
        case 'turn.failed':
          finished = true;
          patchTurn(turnKey, (t) => ({
            ...t,
            state: 'failed',
            stopping: false,
            error: event.error,
            text: event.partialText ?? t.text,
          }));
          if (event.error.code === 'not_configured') void get().refreshStatus();
          return;
      }
    };

    try {
      await streamChat({ conversationId: thread?.id ?? null, message, recheckMessageId: recheckOf }, onEvent, controller.signal);
    } catch (e) {
      flushNow();
      if ((e as Error).name !== 'AbortError') {
        finished = true;
        const error = e instanceof ApiError ? e : new ApiError(0, 'network', 'Lost the connection to the backend.');
        patchTurn(turnKey, (t) => ({
          ...t,
          state: 'failed',
          stopping: false,
          error: { code: error.code, message: error.message, hint: 'Check that npm run dev is still running, then try again.', retryable: true },
        }));
      }
    } finally {
      flushNow();
      controllers.delete(turnKey);
      if (!finished) {
        // The stream closed without a final event: either the user stopped it, or the connection dropped.
        patchTurn(turnKey, (t) =>
          t.stopping || controller.signal.aborted
            ? { ...t, state: 'stopped', stopping: false }
            : { ...t, state: 'failed', error: { code: 'network', message: 'The connection closed before the answer finished.', hint: null, retryable: true } },
        );
      }
      void get().refreshConversations();
      void get().refreshMemory();
    }
  };

  return {
    connection: 'connecting',
    status: null,
    conversations: [],
    conversationsLoaded: false,
    threads: { 'draft:0': emptyThread('draft:0') },
    activeKey: 'draft:0',
    timeline: null,
    ledger: null,
    toasts: [],
    focusTick: 0,
    typing: false,

    async boot() {
      // The status call comes first and alone: it sets the identity cookie, so the
      // requests after it all belong to the same user.
      const status = await get().refreshStatus();
      if (!status) return;
      await Promise.all([get().refreshConversations(), get().refreshMemory()]);
    },

    async refreshStatus() {
      try {
        const status = await api.status();
        set({ status, connection: 'online' });
        return status;
      } catch {
        set({ connection: 'offline' });
        return null;
      }
    },

    async refreshConversations() {
      try {
        const conversations = await api.conversations();
        set({ conversations, conversationsLoaded: true });
      } catch {
        // keep what we have; the connection banner explains outages
      }
    },

    async refreshMemory() {
      try {
        set({ timeline: await api.timeline(365) });
      } catch {
        // optional data
      }
    },

    async refreshLedger() {
      try {
        set({ ledger: await api.ledger() });
      } catch (e) {
        get().toast((e as Error).message, 'error');
      }
    },

    async openConversation(id) {
      const existing = get().threads[id];
      set({ activeKey: id });
      if (existing && (existing.loaded || existing.loading)) return;
      set((s) => ({ threads: { ...s.threads, [id]: { ...emptyThread(id, id), loading: true } } }));
      try {
        const detail = await api.conversation(id);
        patchThread(id, (t) => ({ ...t, title: detail.title, turns: turnsFromMessages(detail.messages), loaded: true, loading: false }));
      } catch (e) {
        const message = e instanceof ApiError && e.status === 404 ? 'This conversation no longer exists.' : (e as Error).message;
        patchThread(id, (t) => ({ ...t, loading: false, error: message }));
      }
    },

    openDraft() {
      const s = get();
      const reusable = Object.values(s.threads).find((t) => t.id === null && t.turns.length === 0);
      if (reusable) {
        set({ activeKey: reusable.key, focusTick: s.focusTick + 1 });
        return;
      }
      const key = newDraftKey();
      set({ threads: { ...s.threads, [key]: emptyThread(key) }, activeKey: key, focusTick: s.focusTick + 1 });
    },

    send(text, options = {}) {
      const message = text.trim();
      const s = get();
      const thread = s.threads[s.activeKey];
      if (!thread || (!message && !options.recheckOf)) return;
      if (thread.turns.some((t) => t.state === 'streaming')) return;
      const turn = blankTurn({
        key: `local:${Date.now().toString(36)}:${Math.random().toString(36).slice(2, 7)}`,
        question: message || RECHECK_QUESTION,
        recheckOf: options.recheckOf ?? null,
        phase: 'thinking',
        phaseLabel: 'Starting',
      });
      patchThread(thread.key, (t) => ({ ...t, turns: [...t.turns, turn] }));
      set({ typing: false });
      void runTurn(thread.key, turn.key, turn.question, turn.recheckOf);
    },

    recheck(turnKey) {
      const found = locateTurn(turnKey);
      if (!found) return;
      const turn = get().threads[found[0]].turns[found[1]];
      if (!turn.answerId) return;
      get().send(RECHECK_QUESTION, { recheckOf: turn.answerId });
    },

    retry(turnKey) {
      const found = locateTurn(turnKey);
      if (!found) return;
      const turn = get().threads[found[0]].turns[found[1]];
      patchThread(found[0], (t) => ({ ...t, turns: t.turns.filter((x) => x.key !== turnKey) }));
      get().send(turn.question, { recheckOf: turn.recheckOf && turn.recheckOf !== 'earlier' ? turn.recheckOf : null });
    },

    stop() {
      const thread = get().threads[get().activeKey];
      const turn = thread?.turns.find((t) => t.state === 'streaming');
      if (!turn) return;
      patchTurn(turn.key, (t) => ({ ...t, stopping: true }));
      const controller = controllers.get(turn.key);
      if (turn.turnId) {
        // Ask the server to stop (it closes the model and search connections at once),
        // and drop the stream ourselves if it has not ended shortly after.
        api.cancel(turn.turnId).catch(() => controller?.abort());
        setTimeout(() => controller?.abort(), 2500);
      } else {
        controller?.abort();
      }
    },

    async rename(id, title) {
      const clean = title.trim();
      if (!clean) return;
      try {
        await api.rename(id, clean);
        patchThread(id, (t) => ({ ...t, title: clean }));
        set((s) => ({ conversations: s.conversations.map((c) => (c.id === id ? { ...c, title: clean } : c)) }));
      } catch (e) {
        get().toast((e as Error).message, 'error');
      }
    },

    async remove(id) {
      try {
        await api.remove(id);
        set((s) => {
          const threads = { ...s.threads };
          delete threads[id];
          return { threads, conversations: s.conversations.filter((c) => c.id !== id) };
        });
        if (get().activeKey === id) {
          get().openDraft();
          navigate('/', { replace: true });
        }
        void get().refreshMemory();
        get().toast('Conversation deleted');
      } catch (e) {
        get().toast((e as Error).message, 'error');
      }
    },

    async forgetEverything() {
      try {
        await api.forget();
        const key = newDraftKey();
        set({ threads: { [key]: emptyThread(key) }, activeKey: key, conversations: [], timeline: [], ledger: [] });
        get().toast('Memory cleared');
      } catch (e) {
        get().toast((e as Error).message, 'error');
      }
    },

    async setClock(localDateTime) {
      try {
        const clock = await api.setClock(localDateTime);
        set((s) => (s.status ? { status: { ...s.status, clock } } : {}));
        void get().refreshMemory();
      } catch (e) {
        get().toast((e as Error).message, 'error');
      }
    },

    async seedDemo(onLine, signal) {
      await streamSeed((event) => {
        if (event.type === 'status') onLine({ tone: event.phase === 'done' ? 'done' : 'info', text: event.label });
        else if (event.type === 'warning') onLine({ tone: 'warn', text: event.message });
        else if (event.type === 'turn.failed') onLine({ tone: 'warn', text: event.error.message });
      }, signal);
      await Promise.all([get().refreshConversations(), get().refreshMemory()]);
    },

    toast(message, tone = 'info') {
      const id = ++toastCounter;
      set((s) => ({ toasts: [...s.toasts.slice(-3), { id, message, tone }] }));
      setTimeout(() => get().dismissToast(id), tone === 'error' ? 6000 : 3200);
    },

    dismissToast(id) {
      set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }));
    },

    setTyping(typing) {
      if (get().typing !== typing) set({ typing });
    },

    focusComposer() {
      set((s) => ({ focusTick: s.focusTick + 1 }));
    },
  };
});

/** The local calendar day the server considers "today" (it follows the demo clock). */
export function useToday(): string {
  return useStore((s) => (s.status ? dayOf(s.status.clock.now) : new Date().toISOString().slice(0, 10)));
}

export function useTimezone(): string {
  return useStore((s) => s.status?.clock.timezone ?? Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'UTC');
}

export const RECHECK_TEXT = RECHECK_QUESTION;
