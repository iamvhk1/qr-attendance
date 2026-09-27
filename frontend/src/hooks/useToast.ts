import { useCallback, useReducer } from 'react';

// ── Types ────────────────────────────────────────────────────────

export type ToastVariant = 'success' | 'error' | 'info' | 'warning';

export interface Toast {
  id: string;
  variant: ToastVariant;
  title: string;
  message?: string;
}

type Action =
  | { type: 'ADD'; toast: Toast }
  | { type: 'REMOVE'; id: string };

// ── Reducer ──────────────────────────────────────────────────────

function reducer(state: Toast[], action: Action): Toast[] {
  switch (action.type) {
    case 'ADD':
      return [...state, action.toast];
    case 'REMOVE':
      return state.filter((t) => t.id !== action.id);
    default:
      return state;
  }
}

// ── Hook ─────────────────────────────────────────────────────────

export function useToast() {
  const [toasts, dispatch] = useReducer(reducer, []);

  const addToast = useCallback(
    (variant: ToastVariant, title: string, message?: string, durationMs = 4000) => {
      const id = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      dispatch({ type: 'ADD', toast: { id, variant, title, message } });
      setTimeout(() => dispatch({ type: 'REMOVE', id }), durationMs);
      return id;
    },
    [],
  );

  const removeToast = useCallback((id: string) => {
    dispatch({ type: 'REMOVE', id });
  }, []);

  // Convenience methods
  const success = useCallback(
    (title: string, message?: string) => addToast('success', title, message),
    [addToast],
  );
  const error = useCallback(
    (title: string, message?: string) => addToast('error', title, message, 6000),
    [addToast],
  );
  const info = useCallback(
    (title: string, message?: string) => addToast('info', title, message),
    [addToast],
  );
  const warning = useCallback(
    (title: string, message?: string) => addToast('warning', title, message),
    [addToast],
  );

  return { toasts, addToast, removeToast, success, error, info, warning };
}
