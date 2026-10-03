import React, { useEffect, useRef } from 'react';
import { CheckCircle, XCircle, Info, AlertTriangle, X } from 'lucide-react';
import type { Toast as ToastType, ToastVariant } from '../../hooks/useToast';
import './Toast.css';

// ── Single Toast ─────────────────────────────────────────────────

interface ToastProps {
  toast: ToastType;
  onRemove: (id: string) => void;
}

const ICONS: Record<ToastVariant, React.ReactNode> = {
  success: <CheckCircle size={18} />,
  error:   <XCircle size={18} />,
  info:    <Info size={18} />,
  warning: <AlertTriangle size={18} />,
};

export const ToastItem: React.FC<ToastProps> = ({ toast, onRemove }) => {
  const ref = useRef<HTMLDivElement>(null);

  // Trigger enter animation on mount
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    // Force reflow so the animation plays
    void el.offsetHeight;
    el.classList.add('toast-visible');
  }, []);

  return (
    <div
      ref={ref}
      className={`toast toast-${toast.variant}`}
      role="alert"
      aria-live="assertive"
    >
      <span className="toast-icon" aria-hidden="true">
        {ICONS[toast.variant]}
      </span>
      <div className="toast-content">
        <p className="toast-title">{toast.title}</p>
        {toast.message && <p className="toast-message">{toast.message}</p>}
      </div>
      <button
        className="toast-close"
        aria-label="Dismiss notification"
        onClick={() => onRemove(toast.id)}
      >
        <X size={14} />
      </button>
    </div>
  );
};

// ── Toast Viewport (portal target) ───────────────────────────────

interface ToastViewportProps {
  toasts: ToastType[];
  onRemove: (id: string) => void;
}

export const ToastViewport: React.FC<ToastViewportProps> = ({ toasts, onRemove }) => (
  <div className="toast-viewport" aria-label="Notifications" role="region">
    {toasts.map((t) => (
      <ToastItem key={t.id} toast={t} onRemove={onRemove} />
    ))}
  </div>
);
