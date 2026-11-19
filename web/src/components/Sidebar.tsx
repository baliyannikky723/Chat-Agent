import clsx from 'clsx';
import { CalendarDays, ScrollText, SquarePen, X } from 'lucide-react';
import type { MouseEvent, ReactNode } from 'react';
import { groupByDay } from '../lib/dates';
import { navigate, paths, useRoute } from '../lib/router';
import { useLayout } from '../state/layout';
import { useStore, useToday } from '../state/store';
import { DemoClock } from './DemoTools';
import { Wordmark } from './Logo';

/** Conversations listed under the day they happened: the shape of Orrery's memory. */
export function Sidebar() {
  const conversations = useStore((s) => s.conversations);
  const loaded = useStore((s) => s.conversationsLoaded);
  const activeKey = useStore((s) => s.activeKey);
  const openDraft = useStore((s) => s.openDraft);
  const status = useStore((s) => s.status);
  const setSidebarOpen = useLayout((s) => s.setSidebarOpen);
  const route = useRoute();
  const today = useToday();
  const groups = groupByDay(conversations, (c) => c.lastLocalDate || c.updatedAt.slice(0, 10), today);

  const go = (e: MouseEvent, path: string) => {
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    navigate(path);
    setSidebarOpen(false);
  };

  const newConversation = () => {
    openDraft();
    navigate('/');
    setSidebarOpen(false);
  };

  return (
    <nav className="flex h-full flex-col" aria-label="Conversations">
      <div className="flex items-center justify-between px-4 pt-4 pb-4">
        <a href="/" onClick={(e) => (e.preventDefault(), newConversation())} aria-label="Orrery, new conversation">
          <Wordmark />
        </a>
        <button type="button" className="icon-btn lg:hidden" aria-label="Close" onClick={() => setSidebarOpen(false)}>
          <X size={18} />
        </button>
      </div>

      <div className="px-3">
        <button type="button" onClick={newConversation} className="btn btn-quiet w-full justify-start">
          <SquarePen size={16} className="text-holo" />
          New conversation
          <span className="kbd ml-auto" aria-label="Shortcut: slash">
            /
          </span>
        </button>
      </div>

      <div className="mt-3 flex flex-col gap-0.5 px-3">
        <NavItem active={route.name === 'memory'} href="/memory" onClick={(e) => go(e, '/memory')} icon={<CalendarDays size={16} />}>
          Memory
        </NavItem>
        <NavItem active={route.name === 'ledger'} href="/ledger" onClick={(e) => go(e, '/ledger')} icon={<ScrollText size={16} />}>
          Ledger of facts
        </NavItem>
      </div>

      <div className="mt-5 min-h-0 flex-1 overflow-y-auto px-3 pb-4">
        {groups.map((group) => (
          <section key={group.key} className="mb-4">
            <h2 className="font-display px-2 pb-1 text-[0.95rem] font-[480] text-brass italic">{group.label}</h2>
            <ul>
              {group.items.map((c) => {
                const active = route.name === 'conversation' && activeKey === c.id;
                return (
                  <li key={c.id}>
                    <a
                      href={paths.conversation(c.id)}
                      onClick={(e) => go(e, paths.conversation(c.id))}
                      aria-current={active ? 'page' : undefined}
                      className={clsx(
                        'block truncate rounded-lg px-2 py-1.5 text-[0.875rem] transition-colors',
                        active ? 'bg-panel text-text' : 'text-muted hover:bg-panel/60 hover:text-text',
                      )}
                      title={c.title ?? undefined}
                    >
                      {c.title || 'Untitled conversation'}
                    </a>
                  </li>
                );
              })}
            </ul>
          </section>
        ))}
        {loaded && conversations.length === 0 && (
          <p className="px-2 text-sm leading-relaxed text-faint">Conversations appear here under the day you had them.</p>
        )}
      </div>

      <div className="border-t border-hairline/60 px-4 py-3 text-xs text-faint">
        {status?.clock.demoTools && <DemoClock />}
        {status?.llm.configured && (
          <p className="mt-2 flex items-center gap-2 truncate" title={`Background tasks: ${status.llm.fastModel ?? 'same model'}`}>
            <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-ok" aria-hidden="true" />
            <span className="truncate">
              {status.llm.provider} {status.llm.model}
            </span>
          </p>
        )}
        {status?.search.configured && <p className="mt-1 truncate pl-3.5">Search by {status.search.providers.join(', then ')}</p>}
      </div>
    </nav>
  );
}

function NavItem({ active, href, onClick, icon, children }: { active: boolean; href: string; onClick: (e: MouseEvent) => void; icon: ReactNode; children: ReactNode }) {
  return (
    <a
      href={href}
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      className={clsx(
        'flex items-center gap-2.5 rounded-lg px-2 py-1.5 text-[0.875rem] transition-colors',
        active ? 'bg-panel text-text' : 'text-muted hover:bg-panel/60 hover:text-text',
      )}
    >
      <span className={active ? 'text-brass' : 'text-faint'}>{icon}</span>
      {children}
    </a>
  );
}
