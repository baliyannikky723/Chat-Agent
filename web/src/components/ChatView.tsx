import { ArrowDown, Ellipsis, Menu as MenuIcon, Pencil, Trash } from 'lucide-react';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { navigate } from '../lib/router';
import { MiniOrrery } from '../hologram/MiniOrrery';
import { useLayout } from '../state/layout';
import { useStore, type Thread } from '../state/store';
import { Composer } from './Composer';
import { EmptyState } from './EmptyState';
import { TurnView } from './TurnView';
import { Dialog, Menu } from './ui';

function titleOf(thread: Thread): string {
  if (thread.title) return thread.title;
  const first = thread.turns[0]?.question;
  if (!first) return 'New conversation';
  const words = first.split(/\s+/).slice(0, 8).join(' ');
  return words.length < first.length ? `${words.replace(/[.,;:!?]+$/, '')}…` : words;
}

export function ChatView() {
  const thread = useStore((s) => s.threads[s.activeKey]);
  const scroller = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const pinned = useRef(true);
  const [showJump, setShowJump] = useState(false);
  const [highlight, setHighlight] = useState<string | null>(null);
  const turnCount = thread?.turns.length ?? 0;
  const streaming = thread?.turns.some((t) => t.state === 'streaming') ?? false;

  // Keep the newest text in view while it streams, unless the reader has scrolled up.
  useEffect(() => {
    const el = content.current;
    if (!el) return;
    const observer = new ResizeObserver(() => {
      if (pinned.current && scroller.current) scroller.current.scrollTop = scroller.current.scrollHeight;
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [thread?.key, turnCount > 0]);

  // A new question always scrolls to the bottom.
  useLayoutEffect(() => {
    pinned.current = true;
    setShowJump(false);
    if (scroller.current) scroller.current.scrollTop = scroller.current.scrollHeight;
  }, [turnCount]);

  // Arriving from a memory link (?m=<message id>): bring that question into view and mark it.
  useEffect(() => {
    if (!thread?.loaded) return;
    const target = new URLSearchParams(location.search).get('m');
    if (!target) return;
    const el = content.current?.querySelector(`[data-message="${CSS.escape(target)}"]`);
    if (!el) return;
    pinned.current = false;
    el.scrollIntoView({ block: 'center' });
    setHighlight(target);
    history.replaceState(null, '', location.pathname);
    const timer = setTimeout(() => setHighlight(null), 2600);
    return () => clearTimeout(timer);
  }, [thread?.loaded, thread?.key]);

  if (!thread) return null;
  if (thread.loaded && !thread.error && thread.turns.length === 0) {
    return (
      <div className="flex h-full flex-col">
        <MobileBar />
        <div className="flex-1 overflow-y-auto">
          <EmptyState />
        </div>
      </div>
    );
  }

  const onScroll = () => {
    const el = scroller.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 90;
    pinned.current = atBottom;
    setShowJump(!atBottom && streaming);
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <ChatHeader thread={thread} />
      <div ref={scroller} onScroll={onScroll} className="min-h-0 flex-1 overflow-y-auto">
        <div ref={content} className="mx-auto w-full max-w-[46rem] px-5 pb-10">
          {thread.loading && (
            <div className="flex items-center gap-2.5 py-16 text-muted">
              <MiniOrrery /> Opening conversation
            </div>
          )}
          {thread.error && (
            <div className="py-16">
              <p className="font-display text-xl">{thread.error}</p>
              <button type="button" className="btn btn-quiet mt-4" onClick={() => navigate('/')}>
                Start a new conversation
              </button>
            </div>
          )}
          {thread.turns.map((turn) => (
            <TurnView key={turn.key} turn={turn} highlighted={highlight !== null && highlight === turn.userMessageId} />
          ))}
        </div>
      </div>
      <div className="relative">
        {showJump && (
          <button
            type="button"
            onClick={() => {
              pinned.current = true;
              setShowJump(false);
              scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: 'smooth' });
            }}
            className="absolute -top-12 left-1/2 inline-flex -translate-x-1/2 items-center gap-1.5 rounded-full border border-hairline bg-raised px-3 py-1.5 text-xs text-text shadow-lg"
          >
            <ArrowDown size={14} /> Latest
          </button>
        )}
        <div className="mx-auto w-full max-w-[46rem] px-4 pb-4 sm:px-5">{!thread.error && <Composer variant="dock" />}</div>
      </div>
    </div>
  );
}

function MobileBar() {
  const setSidebarOpen = useLayout((s) => s.setSidebarOpen);
  return (
    <div className="flex h-12 items-center px-3 lg:hidden">
      <button type="button" className="icon-btn" aria-label="Open conversations" onClick={() => setSidebarOpen(true)}>
        <MenuIcon size={18} />
      </button>
    </div>
  );
}

function ChatHeader({ thread }: { thread: Thread }) {
  const rename = useStore((s) => s.rename);
  const remove = useStore((s) => s.remove);
  const setSidebarOpen = useLayout((s) => s.setSidebarOpen);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const [confirming, setConfirming] = useState(false);
  const title = titleOf(thread);

  const commit = () => {
    setEditing(false);
    if (thread.id && draft.trim() && draft.trim() !== title) void rename(thread.id, draft);
  };

  return (
    <header className="flex h-14 shrink-0 items-center gap-2 border-b border-hairline/60 px-3 sm:px-5">
      <button type="button" className="icon-btn lg:hidden" aria-label="Open conversations" onClick={() => setSidebarOpen(true)}>
        <MenuIcon size={18} />
      </button>
      {editing ? (
        <input
          autoFocus
          value={draft}
          maxLength={120}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => {
            if (e.key === 'Enter') commit();
            if (e.key === 'Escape') setEditing(false);
          }}
          className="field font-display h-9 max-w-xl text-[1.05rem]"
          aria-label="Conversation title"
        />
      ) : (
        <h1
          className="font-display min-w-0 truncate text-[1.1rem] font-[520] tracking-[-0.01em]"
          onDoubleClick={() => {
            if (!thread.id) return;
            setDraft(title);
            setEditing(true);
          }}
          title={title}
        >
          {title}
        </h1>
      )}
      <span className="ml-auto" />
      {thread.id && (
        <Menu
          label="Conversation actions"
          items={[
            {
              label: 'Rename',
              icon: <Pencil size={15} />,
              onSelect: () => {
                setDraft(title);
                setEditing(true);
              },
            },
            { label: 'Delete', icon: <Trash size={15} />, danger: true, onSelect: () => setConfirming(true) },
          ]}
        >
          <Ellipsis size={18} />
        </Menu>
      )}
      <Dialog
        open={confirming}
        onClose={() => setConfirming(false)}
        title="Delete this conversation?"
        description="Its questions, answers and the facts Orrery noted from them are removed from memory. This cannot be undone."
        footer={
          <>
            <button type="button" className="btn btn-quiet" onClick={() => setConfirming(false)} data-autofocus>
              Keep it
            </button>
            <button
              type="button"
              className="btn btn-danger"
              onClick={() => {
                setConfirming(false);
                if (thread.id) void remove(thread.id);
              }}
            >
              Delete
            </button>
          </>
        }
      />
    </header>
  );
}
