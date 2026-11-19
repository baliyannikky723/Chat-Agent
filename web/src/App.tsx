import clsx from 'clsx';
import { useEffect } from 'react';
import { ChatView } from './components/ChatView';
import { LogoMark } from './components/Logo';
import { MemoryView } from './components/MemoryView';
import { SetupScreen } from './components/SetupScreen';
import { Sidebar } from './components/Sidebar';
import { Toasts } from './components/Toasts';
import { MiniOrrery } from './hologram/MiniOrrery';
import { useRoute } from './lib/router';
import { useLayout } from './state/layout';
import { useStore } from './state/store';

export default function App() {
  const route = useRoute();
  const connection = useStore((s) => s.connection);
  const status = useStore((s) => s.status);
  const boot = useStore((s) => s.boot);
  const refreshStatus = useStore((s) => s.refreshStatus);
  const openConversation = useStore((s) => s.openConversation);
  const openDraft = useStore((s) => s.openDraft);
  const focusComposer = useStore((s) => s.focusComposer);
  const stop = useStore((s) => s.stop);
  const sidebarOpen = useLayout((s) => s.sidebarOpen);
  const setSidebarOpen = useLayout((s) => s.setSidebarOpen);

  useEffect(() => {
    void boot();
  }, [boot]);

  // While the backend is unreachable (still starting, or restarting after .env changed), keep trying.
  useEffect(() => {
    if (connection !== 'offline') return;
    const timer = setInterval(async () => {
      const next = await refreshStatus();
      if (next) void boot();
    }, 2000);
    return () => clearInterval(timer);
  }, [connection, refreshStatus, boot]);

  // Returning to the tab refreshes "now", so Today and Yesterday stay right past midnight.
  useEffect(() => {
    const onFocus = () => void refreshStatus();
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [refreshStatus]);

  // The address bar drives which conversation is open. Waits for the first status call,
  // which is the one that establishes who this browser is.
  const routeKey = route.name === 'conversation' ? route.id : route.name;
  const online = status !== null;
  useEffect(() => {
    if (!online) return;
    if (route.name === 'conversation') void openConversation(route.id);
    else if (route.name === 'new') openDraft();
  }, [routeKey, online]);

  // "/" focuses the message box; Escape stops an answer.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable);
      if (e.key === '/' && !typing && !e.metaKey && !e.ctrlKey) {
        e.preventDefault();
        focusComposer();
      } else if (e.key === 'Escape' && !typing) {
        stop();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [focusComposer, stop]);

  if (!status) return <Connecting offline={connection === 'offline'} />;
  if (!status.ready) return <SetupScreen status={status} />;

  return (
    <div className="flex h-dvh overflow-hidden">
      <aside
        className={clsx(
          'fixed inset-y-0 left-0 z-40 w-[17.5rem] border-r border-hairline/60 bg-ink-deep transition-transform duration-300 ease-out lg:static lg:translate-x-0',
          sidebarOpen ? 'translate-x-0 shadow-2xl' : '-translate-x-full',
        )}
      >
        <Sidebar />
      </aside>
      {sidebarOpen && <div className="fixed inset-0 z-30 bg-black/50 lg:hidden" onClick={() => setSidebarOpen(false)} aria-hidden="true" />}
      <main className="relative flex min-w-0 flex-1 flex-col">
        {route.name === 'memory' ? <MemoryView tab="timeline" /> : route.name === 'ledger' ? <MemoryView tab="ledger" /> : <ChatView />}
      </main>
      {connection === 'offline' && (
        <div className="fixed top-3 left-1/2 z-50 -translate-x-1/2 rounded-full border border-brass/40 bg-raised px-4 py-1.5 text-xs text-brass-soft shadow-xl" role="status">
          Reconnecting to the backend
        </div>
      )}
      <Toasts />
    </div>
  );
}

function Connecting({ offline }: { offline: boolean }) {
  return (
    <div className="grid min-h-dvh place-items-center px-6">
      <div className="flex flex-col items-center text-center">
        <LogoMark size={44} />
        <p className="font-display mt-5 text-xl">{offline ? 'Waiting for the backend' : 'Starting Orrery'}</p>
        <p className="mt-2 flex max-w-sm items-center gap-2 text-sm text-muted" aria-live="polite">
          <MiniOrrery size={16} />
          {offline ? 'It may still be starting. This page connects by itself.' : 'Connecting'}
        </p>
      </div>
    </div>
  );
}
