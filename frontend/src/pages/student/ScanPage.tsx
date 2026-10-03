import React, {
  useState,
  useRef,
  useEffect,
  useCallback,
} from 'react';
import { useSearchParams } from 'react-router-dom';
import { QrCode, ShieldCheck, ShieldX, Loader2, Wifi, WifiOff, AlertTriangle, MessageCircle, X } from 'lucide-react';
import type { ScanResponse, HeartbeatRequest, HeartbeatResponse } from '../../types/scan';
import './ScanPage.css';

// ── Constants ────────────────────────────────────────────────────────────
const HEARTBEAT_INTERVAL_MS = 5_000;
const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api';

// ── Page states ──────────────────────────────────────────────────────────
type ScanState =
  | 'ENTER_ROLL'     // student enters roll number — waiting to submit
  | 'SCANNING'       // POST /api/student/scan in-flight
  | 'PRESENT'        // scan succeeded; heartbeat loop running
  | 'CONFIRMED'      // session closed; student was confirmed present
  | 'INVALIDATED'    // heartbeat failed or coverage < 80%
  | 'ERROR';         // unrecoverable error (expired QR, wrong roll number, etc.)

// ── Typed fetch helpers (separate from professor apiFetch — no localStorage) ──
async function scanFetch<T>(path: string, token: string, body: object): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    body: JSON.stringify(body),
    keepalive: true, // ensure last heartbeat fires even on page unload
  });

  if (!res.ok) {
    const payload = await res.json().catch(() => ({ message: res.statusText }));
    throw Object.assign(new Error(payload.message ?? res.statusText), {
      status: res.status,
    });
  }

  const text = await res.text();
  return (text ? JSON.parse(text) : {}) as T;
}

// ── Heartbeat ring UI helper ─────────────────────────────────────────────
const HeartbeatRing: React.FC<{ active: boolean; count: number }> = ({ active, count }) => (
  <div className={`hb-ring ${active ? 'hb-ring--active' : ''}`} aria-hidden="true">
    <div className="hb-ring__inner">
      <span className="hb-ring__count">{count}</span>
      <span className="hb-ring__label">pings</span>
    </div>
  </div>
);

// ── Coverage bar ─────────────────────────────────────────────────────────
const CoverageBar: React.FC<{ sent: number; needed: number }> = ({ sent, needed }) => {
  const pct = needed === 0 ? 0 : Math.min(100, Math.round((sent / needed) * 100));
  const color = pct >= 80 ? 'var(--accent-400)' : pct >= 50 ? 'var(--warning-400)' : 'var(--danger-400)';
  return (
    <div className="coverage-bar-wrap">
      <div className="coverage-bar-track">
        <div
          className="coverage-bar-fill"
          style={{ width: `${pct}%`, background: color }}
        />
      </div>
      <span className="coverage-bar-label" style={{ color }}>
        {pct}% coverage
      </span>
    </div>
  );
};

// ── Main component ───────────────────────────────────────────────────────
const ScanPage: React.FC = () => {
  const [searchParams] = useSearchParams();

  // The 15-second ROLE_SCAN JWT embedded in the QR code URL (?token=...)
  const scanToken = searchParams.get('token') ?? '';

  // ── Form state ───────────────────────────────────────────────────────
  const [rollNumber, setRollNumber] = useState('');
  const [rollError, setRollError]   = useState('');

  // ── Page FSM ─────────────────────────────────────────────────────────
  const [state, setState]       = useState<ScanState>('ENTER_ROLL');
  const [errorMsg, setErrorMsg] = useState('');

  // ── Heartbeat state (in-memory only) ─────────────────────────────────
  // attendanceToken must NOT go into localStorage — ref keeps it off the heap after unmount
  const attendanceTokenRef = useRef<string>('');
  const nonceRef           = useRef<string>('');
  const intervalRef        = useRef<ReturnType<typeof setInterval> | null>(null);
  const wakeRef            = useRef<WakeLockSentinel | null>(null);

  const [heartbeatCount,  setHeartbeatCount]  = useState(0);
  const [heartbeatOnline, setHeartbeatOnline] = useState(true);

  // ── Doubt state ──────────────────────────────────────────────────────
  const [doubtOpen,    setDoubtOpen]    = useState(false);
  const [doubtText,    setDoubtText]    = useState('');
  const [doubtSending, setDoubtSending] = useState(false);
  const [doubtSent,    setDoubtSent]    = useState(false);

  // Estimated total pings needed for 80% coverage (recalculate when we know session length)
  // We don't know session duration from this endpoint, so show a relative bar.
  // Use a rolling 20-ping window: a student needs 16/20 pings minimum.
  const WINDOW = 20;
  const [sentInWindow, setSentInWindow] = useState(0);

  // ── Validate the scan token is present in URL ─────────────────────────
  useEffect(() => {
    if (!scanToken) {
      setState('ERROR');
      setErrorMsg('Invalid QR code — no token found in URL. Please scan a fresh QR code from the projector.');
    }
  }, [scanToken]);

  // ── Screen Wake Lock — keep screen on for heartbeats ─────────────────
  const acquireWakeLock = useCallback(async () => {
    if (!('wakeLock' in navigator)) return; // unsupported (older browsers)
    try {
      wakeRef.current = await (navigator as Navigator & { wakeLock: { request(type: string): Promise<WakeLockSentinel> } }).wakeLock.request('screen');
    } catch {
      // Wake lock denied (e.g. battery saver) — non-fatal
    }
  }, []);

  const releaseWakeLock = useCallback(() => {
    wakeRef.current?.release().catch(() => {});
    wakeRef.current = null;
  }, []);

  // ── Visibility-based heartbeat pause / resume ─────────────────────────
  // ANTI-CHEAT: heartbeats must NOT fire when the student has switched tabs.
  // We store the FSM state in a ref so the visibilitychange handler always
  // sees the current value without needing to be in its dependency array.
  const fsmStateRef = useRef<ScanState>('ENTER_ROLL');
  useEffect(() => { fsmStateRef.current = state; }, [state]);

  useEffect(() => {
    const handler = async () => {
      if (fsmStateRef.current !== 'PRESENT') return;

      if (document.hidden) {
        // Tab went to background — kill the interval immediately
        if (intervalRef.current) {
          clearInterval(intervalRef.current);
          intervalRef.current = null;
        }
        releaseWakeLock();
      } else {
        // Tab came back to foreground — restart the heartbeat loop
        await acquireWakeLock();
        // Fire immediately to trigger backend gap check
        sendHeartbeat();
        intervalRef.current = setInterval(sendHeartbeat, HEARTBEAT_INTERVAL_MS);
      }
    };

    document.addEventListener('visibilitychange', handler);
    return () => document.removeEventListener('visibilitychange', handler);
  // sendHeartbeat is stable (useCallback with no deps that change during PRESENT)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [acquireWakeLock, releaseWakeLock]);

  // ── Heartbeat loop ────────────────────────────────────────────────────
  const stopHeartbeat = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
    releaseWakeLock();
  }, [releaseWakeLock]);

  const sendHeartbeat = useCallback(async () => {
    if (!attendanceTokenRef.current || !nonceRef.current) return;

    const body: HeartbeatRequest = {
      nonce: nonceRef.current,
      webdriver: navigator.webdriver ?? false,
    };

    try {
      const resp = await scanFetch<HeartbeatResponse>(
        '/student/heartbeat',
        attendanceTokenRef.current,
        body,
      );

      // Rotate nonce immediately — never reuse!
      nonceRef.current = resp.nextNonce;

      setHeartbeatOnline(true);
      setHeartbeatCount(c => c + 1);
      setSentInWindow(s => Math.min(WINDOW, s + 1));

      // If the backend has already set status to CONFIRMED or INVALIDATED
      if (resp.status === 'CONFIRMED') {
        stopHeartbeat();
        setState('CONFIRMED');
      } else if (resp.status === 'INVALIDATED') {
        stopHeartbeat();
        setState('INVALIDATED');
        setErrorMsg('Your attendance was invalidated. You may have left the page or the session ended without enough pings.');
      }
    } catch (err: unknown) {
      const e = err as { status?: number; message?: string };
      setHeartbeatOnline(false);

      // 400 = wrong nonce (replay or stale tab). 401 = attendanceToken expired.
      // Both mean invalidation — stop trying.
      if (e.status === 400 || e.status === 401 || e.status === 409) {
        stopHeartbeat();
        setState('INVALIDATED');
        setErrorMsg(
          e.status === 400
            ? 'Attendance invalidated — a duplicate tab was detected or the nonce was rejected.'
            : e.status === 409
            ? 'Attendance invalidated — tab was in the background for too long.'
            : 'Your attendance session token expired. This usually means you kept the tab open too long without a connection.',
        );
      }
      // For other errors (network, 503) we keep the interval alive and retry next tick
    }
  }, [stopHeartbeat]);

  const startHeartbeat = useCallback((token: string, nonce: string) => {
    attendanceTokenRef.current = token;
    nonceRef.current = nonce;

    // Fire once immediately, then every 5 s
    sendHeartbeat();
    intervalRef.current = setInterval(sendHeartbeat, HEARTBEAT_INTERVAL_MS);
  }, [sendHeartbeat]);

  // Session end — backend closes session → heartbeat returns CONFIRMED or INVALIDATED.
  // As a fallback, also detect via attendanceToken 401 (token expired = session over).

  // ── Scan submission ───────────────────────────────────────────────────
  const handleScan = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    const roll = rollNumber.trim().toUpperCase();

    if (!roll) {
      setRollError('Please enter your roll number.');
      return;
    }
    setRollError('');
    setState('SCANNING');

    try {
      const resp = await scanFetch<ScanResponse>(
        '/student/scan',
        scanToken,
        { rollNumber: roll },
      );

      // Transition to PRESENT, start heartbeat
      setState('PRESENT');
      await acquireWakeLock();
      startHeartbeat(resp.attendanceToken, resp.initialNonce);
    } catch (err: unknown) {
      const e = err as { status?: number; message?: string };
      setState('ERROR');

      if (e.status === 401) {
        setErrorMsg('This QR code has expired. Please ask the professor to display a fresh one (they refresh every 15 seconds).');
      } else if (e.status === 404) {
        setErrorMsg(`Roll number "${roll}" was not found in this class roster. Check your roll number and try again.`);
      } else if (e.status === 409) {
        setErrorMsg('You have already scanned into this session. Your attendance is being tracked.');
      } else {
        setErrorMsg(e.message ?? 'Something went wrong. Please try scanning again.');
      }
    }
  }, [rollNumber, scanToken, acquireWakeLock, startHeartbeat]);

  // ── Cleanup on unmount ────────────────────────────────────────────────
  useEffect(() => {
    return () => {
      stopHeartbeat();
    };
  }, [stopHeartbeat]);

  // ── Render helpers ────────────────────────────────────────────────────
  const renderEnterRoll = () => (
    <form className="scan-form" onSubmit={handleScan} noValidate>
      <div className="scan-form__field">
        <label htmlFor="roll-input" className="scan-form__label">
          Your Roll Number
        </label>
        <input
          id="roll-input"
          type="text"
          className={`scan-form__input ${rollError ? 'scan-form__input--error' : ''}`}
          placeholder="e.g. 22CS001"
          value={rollNumber}
          onChange={e => { setRollNumber(e.target.value); setRollError(''); }}
          autoCapitalize="characters"
          autoCorrect="off"
          autoComplete="off"
          spellCheck={false}
          maxLength={20}
          disabled={!scanToken}
        />
        {rollError && (
          <p className="scan-form__error" role="alert">{rollError}</p>
        )}
      </div>

      <button
        type="submit"
        className="scan-submit-btn"
        disabled={!scanToken || !rollNumber.trim()}
        id="scan-submit-btn"
      >
        <QrCode size={18} />
        Mark Attendance
      </button>
    </form>
  );

  const renderScanning = () => (
    <div className="scan-state scan-state--scanning">
      <Loader2 size={48} className="spin-icon" />
      <p className="scan-state__msg">Submitting your scan…</p>
    </div>
  );

  const renderPresent = () => (
    <div className="scan-state scan-state--present">
      {/* Pulse animation behind the icon */}
      <div className="present-pulse-wrap">
        <div className="present-pulse-ring" aria-hidden="true" />
        <ShieldCheck size={56} className="present-icon" />
      </div>

      <h2 className="scan-state__title scan-state__title--success">You're In!</h2>
      <p className="scan-state__msg">
        Keep this page open until the session ends.
        <br />
        Closing or switching tabs may invalidate your attendance.
      </p>

      <HeartbeatRing active={heartbeatOnline} count={heartbeatCount} />

      <CoverageBar sent={sentInWindow} needed={WINDOW} />

      {!heartbeatOnline && (
        <div className="scan-offline-banner" role="alert">
          <WifiOff size={15} />
          No connection — retrying…
        </div>
      )}
      {heartbeatOnline && heartbeatCount > 0 && (
        <div className="scan-online-badge">
          <Wifi size={13} />
          Presence verified
        </div>
      )}
    </div>
  );

  const renderConfirmed = () => (
    <div className="scan-state scan-state--confirmed">
      <ShieldCheck size={64} className="confirmed-icon" />
      <h2 className="scan-state__title scan-state__title--success">Attendance Confirmed ✓</h2>
      <p className="scan-state__msg">
        Your presence has been recorded for this session.
        You may now close this page.
      </p>
      <div className="confirmed-badge">
        {heartbeatCount} heartbeat{heartbeatCount !== 1 ? 's' : ''} sent
      </div>
    </div>
  );

  const renderInvalidated = () => (
    <div className="scan-state scan-state--invalid">
      <ShieldX size={64} className="invalid-icon" />
      <h2 className="scan-state__title scan-state__title--danger">Attendance Invalidated</h2>
      <p className="scan-state__msg">{errorMsg}</p>
      <p className="scan-state__sub">Contact your professor if you believe this is an error.</p>
    </div>
  );

  const renderError = () => (
    <div className="scan-state scan-state--error">
      <AlertTriangle size={56} className="error-icon" />
      <h2 className="scan-state__title scan-state__title--danger">Cannot Record Attendance</h2>
      <p className="scan-state__msg">{errorMsg}</p>
      {/* 409 = already scanned — let them retry to see status */}
      {(errorMsg.includes('already scanned')) && (
        <button
          className="scan-retry-btn"
          onClick={() => { setState('ENTER_ROLL'); setErrorMsg(''); }}
        >
          Go Back
        </button>
      )}
    </div>
  );

  // ── Main render ───────────────────────────────────────────────────────
  return (
    <main className="scan-root">
      {/* Ambient glow orbs */}
      <div className="scan-orb scan-orb-1" aria-hidden="true" />
      <div className="scan-orb scan-orb-2" aria-hidden="true" />

      <div className="scan-card anim-fade-in-scale">
        {/* Card header */}
        <div className="scan-card__header">
          <div className="scan-card__logo">
            <QrCode size={20} />
          </div>
          <div>
            <h1 className="scan-card__title">QR Attendance</h1>
            <p className="scan-card__sub">Student presence verification</p>
          </div>

          {/* Connection indicator */}
          <div className={`scan-conn-badge ${heartbeatOnline ? 'scan-conn-badge--online' : 'scan-conn-badge--offline'}`}>
            {state === 'PRESENT'
              ? heartbeatOnline
                ? <><span className="conn-dot" />Live</>
                : <><WifiOff size={11} />Reconnecting</>
              : null
            }
          </div>
        </div>

        {/* Card body — switches by state */}
        <div className="scan-card__body">
          {state === 'ENTER_ROLL'  && renderEnterRoll()}
          {state === 'SCANNING'    && renderScanning()}
          {state === 'PRESENT'     && renderPresent()}
          {state === 'CONFIRMED'   && renderConfirmed()}
          {state === 'INVALIDATED' && renderInvalidated()}
          {state === 'ERROR'       && renderError()}
        </div>

        {/* Footer */}
        <div className="scan-card__footer">
          <span>Powered by <strong>AttendanceHelper</strong></span>
        </div>
      </div>

      {/* Doubt FAB — only when student is actively present */}
      {state === 'PRESENT' && (
        <>
          <button
            className="doubt-fab"
            onClick={() => { setDoubtOpen(true); setDoubtSent(false); setDoubtText(''); }}
            aria-label="Ask a doubt"
            title="Have a doubt? Ask anonymously"
          >
            <MessageCircle size={20} />
            <span>Doubt?</span>
          </button>

          {doubtOpen && (
            <div className="doubt-modal-backdrop" role="dialog" aria-modal="true">
              <div className="doubt-modal">
                <div className="doubt-modal__header">
                  <span>Ask a Doubt (Anonymous)</span>
                  <button onClick={() => setDoubtOpen(false)} aria-label="Close"><X size={16} /></button>
                </div>
                {doubtSent ? (
                  <div className="doubt-sent">
                    <ShieldCheck size={28} />
                    <p>Doubt submitted anonymously!</p>
                  </div>
                ) : (
                  <>
                    <textarea
                      className="doubt-textarea"
                      rows={4}
                      placeholder="Type your question here..."
                      value={doubtText}
                      onChange={(e) => setDoubtText(e.target.value)}
                    />
                    <button
                      className="doubt-submit-btn"
                      disabled={!doubtText.trim() || doubtSending}
                      onClick={async () => {
                        if (!doubtText.trim() || doubtSending) return;
                        setDoubtSending(true);
                        try {
                          await scanFetch<void>('/student/doubt', attendanceTokenRef.current, { text: doubtText.trim() });
                          setDoubtSent(true);
                        } catch (err: any) { 
                          alert(`Doubt submission failed: ${err.message || 'Unknown error'}`);
                        }
                        finally { setDoubtSending(false); }
                      }}
                    >
                      {doubtSending ? <Loader2 size={16} className="spin" /> : 'Submit'}
                    </button>
                  </>
                )}
              </div>
            </div>
          )}
        </>
      )}
    </main>
  );
};

export default ScanPage;
