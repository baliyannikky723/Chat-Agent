import { create } from 'zustand';

/** Layout-only state: whether the conversation list is open on narrow screens. */
export const useLayout = create<{ sidebarOpen: boolean; setSidebarOpen: (open: boolean) => void }>()((set) => ({
  sidebarOpen: false,
  setSidebarOpen: (sidebarOpen) => set({ sidebarOpen }),
}));
