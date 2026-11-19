import { useSyncExternalStore } from 'react';

/**
 * Four routes do not need a routing library:
 *   /            a new conversation
 *   /c/:id       a conversation
 *   /memory      the timeline of past questions
 *   /ledger      the facts Orrery has stated, with dates
 * The Spring backend forwards the same paths to index.html in the production jar.
 */

export type Route = { name: 'new' } | { name: 'conversation'; id: string } | { name: 'memory' } | { name: 'ledger' };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function parseRoute(path: string): Route {
  const clean = path.replace(/\/+$/, '') || '/';
  const conversation = /^\/c\/([^/]+)$/.exec(clean);
  if (conversation && UUID.test(conversation[1])) return { name: 'conversation', id: conversation[1].toLowerCase() };
  if (clean === '/memory') return { name: 'memory' };
  if (clean === '/ledger') return { name: 'ledger' };
  return { name: 'new' };
}

const listeners = new Set<() => void>();

function subscribe(listener: () => void) {
  listeners.add(listener);
  window.addEventListener('popstate', listener);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('popstate', listener);
  };
}

export function navigate(path: string, options: { replace?: boolean } = {}) {
  if (path === location.pathname) return;
  if (options.replace) history.replaceState(null, '', path);
  else history.pushState(null, '', path);
  listeners.forEach((l) => l());
}

export function usePath(): string {
  return useSyncExternalStore(subscribe, () => location.pathname, () => '/');
}

export function useRoute(): Route {
  return parseRoute(usePath());
}

export const paths = {
  conversation: (id: string) => `/c/${id}`,
};
