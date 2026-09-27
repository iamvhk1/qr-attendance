import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import { QRCodeSVG } from 'qrcode.react';
import { ArrowLeft, Users, CheckCircle, Clock, XCircle, Wifi, WifiOff, Square } from 'lucide-react';
import { Card, CardHeader, CardBody, Button, Badge, Loader } from '../../components/ui';
import { apiFetch, ApiError } from '../../lib/api';
import { useToast } from '../../hooks/useToast';
import { ToastViewport } from '../../components/ui/Toast';
import type { SessionResponse, AttendanceResponse, QrDataResponse } from '../../types/session';
import './SessionPage.css';

// ── Countdown Ring ────────────────────────────────────────────────

interface CountdownRingProps {
  secondsLeft: number;
  totalSeconds: number;
  closing?: boolean;
}

const CountdownRing: React.FC<CountdownRingProps> = ({ secondsLeft, totalSeconds, closing }) => {
  const R = 54;
  const circ = 2 * Math.PI * R;
  const progress = totalSeconds > 0 ? secondsLeft / totalSeconds : 0;
  const dash = circ * progress;

  const mins = Math.floor(secondsLeft / 60);
  const secs = secondsLeft % 60;
  const label = `${mins}:${secs.toString().padStart(2, '0')}`;

  const color =
    progress > 0.5 ? 'var(--accent-400)' :
    progress > 0.2 ? 'var(--warning-400)' :
    'var(--danger-400)';

  return (
    <div className="countdown-ring-wrap" aria-label={`${label} remaining`}>
      <svg width="128" height="128" viewBox="0 0 128 128" aria-hidden="true">
        {/* Track */}
        <circle cx="64" cy="64" r={R} fill="none" stroke="var(--bg-elevated)" strokeWidth="8" />
        {/* Progress */}
        <circle
          cx="64" cy="64" r={R}
          fill="none"
          stroke={color}
          strokeWidth="8"
          strokeLinecap="round"
          strokeDasharray={`${dash} ${circ}`}
          strokeDashoffset={0}
          transform="rotate(-90 64 64)"
          style={{ transition: 'stroke-dasharray 0.9s linear, stroke 0.5s' }}
        />
      </svg>
      <div className="countdown-label">
        <span className="countdown-time" style={{ color }}>
          {closing ? 'Closing…' : label}
        </span>
        <span className="countdown-unit">remaining</span>
      </div>
    </div>
  );
};

// ── Attendance Table ──────────────────────────────────────────────

const STATUS_CONFIG = {
  CONFIRMED:   { label: 'Confirmed',   variant: 'success' as const },
  PENDING:     { label: 'Pending',     variant: 'warning' as const },
  INVALIDATED: { label: 'Invalidated', variant: 'danger'  as const },
};

interface AttendanceTableProps {
  records: AttendanceResponse[];
}

const AttendanceTable: React.FC<AttendanceTableProps> = ({ records }) => {
  if (records.length === 0) {
    return (
      <div className="attendance-empty">
        <Users size={32} strokeWidth={1.25} aria-hidden="true" />
        <p>No students have scanned yet.</p>
      </div>
    );
  }

  const confirmed   = records.filter((r) => r.status === 'CONFIRMED').length;
  const pending     = records.filter((r) => r.status === 'PENDING').length;
  const invalidated = records.filter((r) => r.status === 'INVALIDATED').length;

  return (
    <div>
      <div className="attendance-summary">
        <div className="attendance-stat attendance-stat-confirmed">
          <CheckCircle size={16} aria-hidden="true" />{confirmed} Confirmed
        </div>
        <div className="attendance-stat attendance-stat-pending">
          <Clock size={16} aria-hidden="true" />{pending} Pending
        </div>
        <div className="attendance-stat attendance-stat-invalid">
          <XCircle size={16} aria-hidden="true" />{invalidated} Invalidated
        </div>
      </div>

      <div className="attendance-table-wrap">
        <table className="attendance-table" aria-label="Live attendance roster">
          <thead>
            <tr>
              <th>Roll No.</th>
              <th>Name</th>
              <th>Status</th>
              <th>Scanned At</th>
              <th>Coverage</th>
            </tr>
          </thead>
          <tbody>
            {records.map((r) => {
              const cfg = STATUS_CONFIG[r.status];
              return (
                <tr key={r.id}>
                  <td><code className="roll-no">{r.rollNumber}</code></td>
                  <td>{r.studentName}</td>
                  <td>
                    <Badge variant={cfg.variant} dot>{cfg.label}</Badge>
                    {r.manuallyAdded && <span className="manual-badge" title="Manual override">M</span>}
                  </td>
                  <td className="time-cell">
                    {new Date(r.markedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                  </td>
                  <td>
                    {r.heartbeatCoverage != null
                      ? `${(r.heartbeatCoverage * 100).toFixed(0)}%`
                      : <span className="no-coverage">—</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
};

// ── Main Page ─────────────────────────────────────────────────────

const QR_POLL_MS   = 14_000;  // refresh QR every 14s (embedded JWT expires at 15s)
const SESS_POLL_MS = 5_000;   // check session status every 5s
const ATT_POLL_MS  = 5_000;   // refresh attendance every 5s

const SessionPage: React.FC = () => {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const { toasts, removeToast, error: toastError, success } = useToast();

  const [session, setSession]       = useState<SessionResponse | null>(null);
  const [qrUrl, setQrUrl]           = useState<string | null>(null);
  const [attendance, setAttendance] = useState<AttendanceResponse[]>([]);
  const [loading, setLoading]       = useState(true);
  const [qrOffline, setQrOffline]   = useState(false);
  const [closing, setClosing]       = useState(false);
  const [secondsLeft, setSecondsLeft] = useState(0);

  const qrIntervalRef   = useRef<ReturnType<typeof setInterval> | null>(null);
  const sessIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const attIntervalRef  = useRef<ReturnType<typeof setInterval> | null>(null);
  const tickRef         = useRef<ReturnType<typeof setInterval> | null>(null);
  const sessionRef      = useRef<SessionResponse | null>(null);

  // ── Helpers ──

  const clearAllIntervals = () => {
    [qrIntervalRef, sessIntervalRef, attIntervalRef, tickRef].forEach((r) => {
      if (r.current) { clearInterval(r.current); r.current = null; }
    });
  };

  const computeSecondsLeft = (s: SessionResponse): number =>
    Math.max(0, Math.floor((new Date(s.expiresAt).getTime() - Date.now()) / 1000));

  // ── QR fetch ──

  const fetchQr = useCallback(async () => {
    if (!sessionId) return;
    try {
      const data = await apiFetch<QrDataResponse>(`/sessions/${sessionId}/qr-data`);
      setQrUrl(data.url);
      setQrOffline(false);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        // Session closed between polls — update status
        setSession((prev) => prev ? { ...prev, status: 'CLOSED' } : prev);
        clearAllIntervals();
      } else {
        // Network issue — keep interval alive, show offline indicator
        setQrOffline(true);
      }
    }
  }, [sessionId]);

  // ── Session poll ──

  const fetchSession = useCallback(async () => {
    if (!sessionId) return;
    try {
      const s = await apiFetch<SessionResponse>(`/sessions/${sessionId}`);
      sessionRef.current = s;
      setSession(s);
      if (s.status === 'CLOSED') {
        clearAllIntervals();
      }
    } catch { /* ignore transient errors */ }
  }, [sessionId]);

  // ── Attendance poll ──

  const fetchAttendance = useCallback(async () => {
    if (!sessionId) return;
    try {
      const records = await apiFetch<AttendanceResponse[]>(`/sessions/${sessionId}/attendance`);
      setAttendance(records);
    } catch { /* ignore transient errors */ }
  }, [sessionId]);

  // ── Polling lifecycle ──

  const startPolling = useCallback(() => {
    fetchQr();
    fetchSession();
    fetchAttendance();

    if (!qrIntervalRef.current)   qrIntervalRef.current   = setInterval(fetchQr,        QR_POLL_MS);
    if (!sessIntervalRef.current) sessIntervalRef.current = setInterval(fetchSession,    SESS_POLL_MS);
    if (!attIntervalRef.current)  attIntervalRef.current  = setInterval(fetchAttendance, ATT_POLL_MS);
  }, [fetchQr, fetchSession, fetchAttendance]);

  // ── Mount: initial load + start polling ──

  useEffect(() => {
    if (!sessionId) return;

    const init = async () => {
      try {
        const s = await apiFetch<SessionResponse>(`/sessions/${sessionId}`);
        sessionRef.current = s;
        setSession(s);
        setSecondsLeft(computeSecondsLeft(s));

        if (s.status === 'LIVE') {
          startPolling();
          // Tick countdown every second
          tickRef.current = setInterval(() => {
            setSecondsLeft(computeSecondsLeft(sessionRef.current!));
          }, 1000);
        } else {
          // Arrive at a closed session — fetch attendance for summary
          const records = await apiFetch<AttendanceResponse[]>(`/sessions/${sessionId}/attendance`);
          setAttendance(records);
        }
      } catch (err) {
        toastError('Failed to load session', err instanceof ApiError ? err.message : undefined);
      } finally {
        setLoading(false);
      }
    };

    init();

    // Page Visibility API — pause polls when tab is hidden, resume when visible
    const handleVisibility = () => {
      if (document.hidden) {
        clearAllIntervals();
      } else if (sessionRef.current?.status === 'LIVE') {
        startPolling();
      }
    };
    document.addEventListener('visibilitychange', handleVisibility);

    return () => {
      clearAllIntervals();
      document.removeEventListener('visibilitychange', handleVisibility);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  // ── Close session handler ──

  const handleClose = async () => {
    if (!sessionId || closing) return;
    setClosing(true);
    try {
      const s = await apiFetch<SessionResponse>(`/sessions/${sessionId}/close`, { method: 'PATCH' });
      sessionRef.current = s;
      setSession(s);
      clearAllIntervals();
      success('Session closed', 'All attendance has been finalised.');
      // Fetch final attendance
      const records = await apiFetch<AttendanceResponse[]>(`/sessions/${sessionId}/attendance`);
      setAttendance(records);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        // Already closed
        const s = await apiFetch<SessionResponse>(`/sessions/${sessionId}`).catch(() => null);
        if (s) { sessionRef.current = s; setSession(s); clearAllIntervals(); }
      } else {
        toastError('Failed to close session', err instanceof ApiError ? err.message : undefined);
      }
    } finally {
      setClosing(false);
    }
  };

  // ── Render ──

  if (loading) {
    return <div className="session-loading"><Loader variant="spinner" size="lg" label="Loading session…" /></div>;
  }

  if (!session) return null;

  const isLive      = session.status === 'LIVE';
  const totalSecs   = Math.round(
    (new Date(session.expiresAt).getTime() - new Date(session.createdAt).getTime()) / 1000
  );
  const isTimerZero = secondsLeft === 0 && isLive;

  return (
    <section className="session-page">
      {/* Breadcrumb */}
      <div className="detail-breadcrumb">
        <Link to={`/courses/${session.courseId}`} className="breadcrumb-link">
          <ArrowLeft size={16} aria-hidden="true" /> Course
        </Link>
        <span className="breadcrumb-sep">/</span>
        <span className="breadcrumb-current">Live Session</span>
      </div>

      {/* Status banner */}
      <div className={`session-status-bar ${isLive ? 'status-live' : 'status-closed'}`}>
        {isLive ? (
          <><span className="status-dot" aria-hidden="true" />Session is LIVE</>
        ) : (
          <>Session CLOSED</>
        )}
        <Badge variant={isLive ? 'success' : 'primary'}>
          {attendance.filter((r) => r.status === 'CONFIRMED').length} confirmed
        </Badge>
      </div>

      {/* ── Two column: QR + Attendance ── */}
      <div className="session-grid">

        {/* QR Panel */}
        <Card glass accent className="qr-panel">
          <CardHeader
            title="QR Code"
            subtitle={isLive ? 'Refreshes every 14 seconds — prevents sharing' : 'Session ended'}
            action={
              qrOffline
                ? <span className="qr-offline-badge"><WifiOff size={14} aria-hidden="true" /> Reconnecting</span>
                : isLive ? <span className="qr-online-badge"><Wifi size={14} aria-hidden="true" /> Live</span>
                : null
            }
          />
          <CardBody>
            <div className="qr-content">
              {isLive ? (
                <>
                  <div className="qr-frame">
                    {qrUrl ? (
                      <div className={`qr-code-wrap ${qrOffline ? 'qr-faded' : ''}`}>
                        <QRCodeSVG
                          value={qrUrl}
                          size={220}
                          bgColor="transparent"
                          fgColor="#000000"
                          level="M"
                        />
                      </div>
                    ) : (
                      <div className="qr-skeleton">
                        <Loader variant="pulse" size="lg" label="Generating QR…" />
                      </div>
                    )}
                    {qrOffline && (
                      <div className="qr-offline-overlay" aria-live="polite">
                        <WifiOff size={24} aria-hidden="true" />
                        <span>QR unavailable — reconnecting…</span>
                      </div>
                    )}
                  </div>

                  <CountdownRing
                    secondsLeft={secondsLeft}
                    totalSeconds={totalSecs}
                    closing={isTimerZero}
                  />

                  <Button
                    variant="danger"
                    leftIcon={<Square size={16} />}
                    loading={closing}
                    onClick={handleClose}
                    className="close-session-btn"
                    id="close-session-btn"
                  >
                    End Session Early
                  </Button>
                </>
              ) : (
                <div className="session-closed-state">
                  <CheckCircle size={48} color="var(--accent-400)" strokeWidth={1.5} aria-hidden="true" />
                  <h3>Session Complete</h3>
                  <p>All attendance records have been finalised.</p>
                </div>
              )}
            </div>
          </CardBody>
        </Card>

        {/* Attendance Panel */}
        <Card className="attendance-panel">
          <CardHeader
            title={`Attendance (${attendance.length})`}
            subtitle={isLive ? 'Auto-refreshing every 5 seconds' : 'Final records'}
            action={isLive ? <span className="live-indicator"><span className="live-dot" aria-hidden="true" />Live</span> : undefined}
          />
          <CardBody>
            <AttendanceTable records={attendance} />
          </CardBody>
        </Card>
      </div>

      <ToastViewport toasts={toasts} onRemove={removeToast} />
    </section>
  );
};

export default SessionPage;
