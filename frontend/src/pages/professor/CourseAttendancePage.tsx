import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  ArrowLeft, FileSpreadsheet, Search, Users, CalendarCheck, TrendingUp, TriangleAlert, RefreshCw,
} from 'lucide-react';
import { Card, CardBody, Button, Badge, Loader } from '../../components/ui';
import { ToastViewport } from '../../components/ui/Toast';
import { useToast } from '../../hooks/useToast';
import { apiFetch, ApiError } from '../../lib/api';
import { downloadFile } from '../../lib/download';
import type {
  AttendanceCell, CourseAttendanceReport, ReportSessionColumn, ReportStudentRow,
} from '../../types/report';
import './CourseAttendancePage.css';

// ── Display helpers ───────────────────────────────────────────────

const CELL_CONFIG: Record<AttendanceCell, { symbol: string; label: string; className: string }> = {
  CONFIRMED:   { symbol: '✓', label: 'Present',             className: 'cell-present' },
  INVALIDATED: { symbol: '✗', label: 'Failed presence check', className: 'cell-invalid' },
  PENDING:     { symbol: '•', label: 'Pending',             className: 'cell-pending' },
  ABSENT:      { symbol: '–', label: 'Absent',              className: 'cell-absent' },
};

const formatPct = (pct: number | null): string => (pct == null ? '—' : `${pct.toFixed(1)}%`);

const formatDay  = (iso: string) => new Date(iso).toLocaleDateString([], { day: 'numeric', month: 'short' });
const formatTime = (iso: string) => new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const formatFull = (iso: string) => `${new Date(iso).toLocaleDateString()} ${formatTime(iso)}`;

type SortKey = 'roll' | 'name' | 'pct-asc' | 'pct-desc';

/** Sorts nulls (no counted sessions) last in both percentage orders. */
function compareRows(a: ReportStudentRow, b: ReportStudentRow, key: SortKey): number {
  switch (key) {
    case 'name':
      return a.fullName.localeCompare(b.fullName) || a.rollNumber.localeCompare(b.rollNumber);
    case 'pct-asc':
    case 'pct-desc': {
      if (a.percentage == null && b.percentage == null) return a.rollNumber.localeCompare(b.rollNumber);
      if (a.percentage == null) return 1;
      if (b.percentage == null) return -1;
      const diff = key === 'pct-asc' ? a.percentage - b.percentage : b.percentage - a.percentage;
      return diff || a.rollNumber.localeCompare(b.rollNumber);
    }
    default:
      return a.rollNumber.localeCompare(b.rollNumber);
  }
}

// ── Stat card ─────────────────────────────────────────────────────

interface StatProps {
  icon: React.ReactNode;
  label: string;
  value: string;
  hint?: string;
  tone?: 'default' | 'danger';
}

const Stat: React.FC<StatProps> = ({ icon, label, value, hint, tone = 'default' }) => (
  <Card padding="sm" className={`att-stat ${tone === 'danger' ? 'att-stat-danger' : ''}`}>
    <div className="att-stat-inner">
      <span className="att-stat-icon" aria-hidden="true">{icon}</span>
      <div>
        <p className="att-stat-label">{label}</p>
        <p className="att-stat-value">{value}</p>
        {hint && <p className="att-stat-hint">{hint}</p>}
      </div>
    </div>
  </Card>
);

// ── Session column header ─────────────────────────────────────────

const SessionHeader: React.FC<{ session: ReportSessionColumn; index: number }> = ({ session, index }) => (
  <th
    scope="col"
    className={`att-session-col ${session.counted ? '' : 'att-col-live'}`}
    title={session.counted
      ? `Session ${index + 1} — ${formatFull(session.createdAt)}`
      : `Session ${index + 1} — ${formatFull(session.createdAt)} (live, not counted yet)`}
  >
    <Link to={`/sessions/${session.id}`} className="att-session-link">
      <span className="att-session-day">{formatDay(session.createdAt)}</span>
      <span className="att-session-time">{session.counted ? formatTime(session.createdAt) : 'Live'}</span>
    </Link>
  </th>
);

// ── Main Page ─────────────────────────────────────────────────────

const CourseAttendancePage: React.FC = () => {
  const { courseId } = useParams<{ courseId: string }>();
  const { toasts, removeToast, success, error: toastError } = useToast();

  const [report, setReport]       = useState<CourseAttendanceReport | null>(null);
  const [loading, setLoading]     = useState(true);
  const [loadError, setLoadError] = useState<{ status: number; message: string } | null>(null);
  const [downloading, setDownloading] = useState(false);

  const [query, setQuery]         = useState('');
  const [sortKey, setSortKey]     = useState<SortKey>('roll');
  const [belowOnly, setBelowOnly] = useState(false);

  const fetchReport = useCallback(async () => {
    if (!courseId) return;
    setLoading(true);
    setLoadError(null);
    try {
      setReport(await apiFetch<CourseAttendanceReport>(`/reports/courses/${courseId}`));
    } catch (err) {
      setLoadError(err instanceof ApiError
        ? { status: err.status, message: err.message }
        : { status: 0, message: 'Could not load attendance.' });
    } finally {
      setLoading(false);
    }
  }, [courseId]);

  useEffect(() => { fetchReport(); }, [fetchReport]);

  const rows = useMemo(() => {
    if (!report) return [];
    const q = query.trim().toLowerCase();
    return report.students
      .filter((s) => !belowOnly || s.belowThreshold)
      .filter((s) => !q || s.rollNumber.toLowerCase().includes(q) || s.fullName.toLowerCase().includes(q))
      .sort((a, b) => compareRows(a, b, sortKey));
  }, [report, query, sortKey, belowOnly]);

  const handleDownload = async () => {
    if (!courseId) return;
    setDownloading(true);
    try {
      await downloadFile(`/reports/courses/${courseId}/excel`, `course-report-${report?.courseCode ?? courseId}.xlsx`);
      success('Report downloaded');
    } catch (err) {
      toastError('Download failed', err instanceof Error ? err.message : undefined);
    } finally {
      setDownloading(false);
    }
  };

  // ── Loading / error states ──

  if (loading && !report) {
    return <div className="att-loading"><Loader variant="spinner" size="lg" label="Loading attendance…" /></div>;
  }

  if (loadError || !report) {
    const notFound = loadError?.status === 404 || loadError?.status === 403;
    return (
      <div className="att-state">
        <h2>{notFound ? 'Course not found' : 'Could not load attendance'}</h2>
        <p>{notFound ? 'This course may have been deleted, or it belongs to another account.' : loadError?.message}</p>
        <div className="att-state-actions">
          {!notFound && (
            <Button variant="primary" leftIcon={<RefreshCw size={16} />} onClick={fetchReport}>Try again</Button>
          )}
          <Link to="/courses"><Button variant="secondary" leftIcon={<ArrowLeft size={16} />}>Back to Courses</Button></Link>
        </div>
      </div>
    );
  }

  const { summary, sessions } = report;
  const threshold = summary.threshold;
  const hasStudents = report.students.length > 0;

  return (
    <section className="att-page">
      {/* ── Breadcrumb ── */}
      <nav className="att-breadcrumb" aria-label="Breadcrumb">
        <Link to="/courses" className="att-crumb-link">
          <ArrowLeft size={16} aria-hidden="true" /> Courses
        </Link>
        <span className="att-crumb-sep">/</span>
        <Link to={`/courses/${report.courseId}`} className="att-crumb-link">{report.courseName}</Link>
        <span className="att-crumb-sep">/</span>
        <span className="att-crumb-current">Attendance</span>
      </nav>

      {/* ── Header ── */}
      <header className="att-header">
        <div>
          <div className="att-header-top">
            <Badge variant="primary">{report.courseCode}</Badge>
          </div>
          <h1 className="att-title">Attendance</h1>
          <p className="att-subtitle">{report.courseName}</p>
        </div>
        <div className="att-header-actions">
          <Button
            variant="ghost"
            size="sm"
            leftIcon={<RefreshCw size={16} />}
            onClick={fetchReport}
            disabled={loading}
            aria-label="Refresh attendance"
          >
            {loading ? 'Refreshing…' : 'Refresh'}
          </Button>
          <Button
            variant="secondary"
            size="sm"
            leftIcon={downloading ? <Loader variant="spinner" size="sm" /> : <FileSpreadsheet size={16} />}
            onClick={handleDownload}
            disabled={downloading}
          >
            {downloading ? 'Downloading…' : 'Download Excel'}
          </Button>
        </div>
      </header>

      {/* ── Summary ── */}
      <div className="att-stats">
        <Stat icon={<Users size={18} />} label="Students" value={String(summary.studentCount)} />
        <Stat
          icon={<CalendarCheck size={18} />}
          label="Sessions held"
          value={String(summary.countedSessions)}
          hint={summary.liveSessions > 0 ? `+${summary.liveSessions} live, not counted yet` : undefined}
        />
        <Stat icon={<TrendingUp size={18} />} label="Average attendance" value={formatPct(summary.averagePercentage)} />
        <Stat
          icon={<TriangleAlert size={18} />}
          label={`Below ${threshold}%`}
          value={String(summary.belowThresholdCount)}
          tone={summary.belowThresholdCount > 0 ? 'danger' : 'default'}
        />
      </div>

      {/* ── Matrix ── */}
      <Card padding="none" className="att-matrix-card">
        {!hasStudents ? (
          <CardBody>
            <div className="att-empty">
              <Users size={32} strokeWidth={1.25} aria-hidden="true" />
              <p>No students enrolled yet.</p>
              <Link to={`/courses/${report.courseId}`}>
                <Button variant="secondary" size="sm">Add students</Button>
              </Link>
            </div>
          </CardBody>
        ) : (
          <>
            {/* Toolbar */}
            <div className="att-toolbar">
              <label className="att-search">
                <Search size={16} aria-hidden="true" />
                <input
                  type="search"
                  placeholder="Search roll no. or name"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  aria-label="Search students"
                />
              </label>
              <label className="att-sort">
                <span>Sort</span>
                <select value={sortKey} onChange={(e) => setSortKey(e.target.value as SortKey)} aria-label="Sort students">
                  <option value="roll">Roll number</option>
                  <option value="name">Name</option>
                  <option value="pct-asc">Attendance (lowest first)</option>
                  <option value="pct-desc">Attendance (highest first)</option>
                </select>
              </label>
              <label className="att-toggle">
                <input type="checkbox" checked={belowOnly} onChange={(e) => setBelowOnly(e.target.checked)} />
                Below {threshold}% only
              </label>
            </div>

            {/* Legend */}
            <div className="att-legend" aria-label="Legend">
              {(Object.keys(CELL_CONFIG) as AttendanceCell[]).map((k) => (
                <span key={k} className="att-legend-item">
                  <span className={`att-cell-mark ${CELL_CONFIG[k].className}`} aria-hidden="true">{CELL_CONFIG[k].symbol}</span>
                  {CELL_CONFIG[k].label}
                </span>
              ))}
              {summary.liveSessions > 0 && (
                <span className="att-legend-item att-legend-live">Shaded columns are live and not counted yet</span>
              )}
            </div>

            {sessions.length === 0 && (
              <p className="att-notice">No sessions held yet — percentages will appear after the first session ends.</p>
            )}

            <div className="att-table-wrap">
              <table className="att-table">
                <caption className="sr-only">
                  Attendance for {report.courseName}: {rows.length} students across {sessions.length} sessions
                </caption>
                <thead>
                  <tr>
                    <th scope="col" className="att-sticky att-col-roll">Roll No.</th>
                    <th scope="col" className="att-sticky att-col-name">Name</th>
                    <th scope="col" className="att-col-num">Attended</th>
                    <th scope="col" className="att-col-num">%</th>
                    {sessions.map((s, i) => <SessionHeader key={s.id} session={s} index={i} />)}
                  </tr>
                </thead>
                <tbody>
                  {rows.length === 0 ? (
                    <tr>
                      <td colSpan={4 + sessions.length} className="att-no-match">
                        No students match {belowOnly ? `“below ${threshold}%”` : ''}{belowOnly && query ? ' and ' : ''}{query ? `“${query}”` : ''}.
                      </td>
                    </tr>
                  ) : rows.map((s) => (
                    <tr key={s.rollNumber} className={s.belowThreshold ? 'att-row-low' : ''}>
                      <th scope="row" className="att-sticky att-col-roll"><code className="att-roll">{s.rollNumber}</code></th>
                      <td className="att-sticky att-col-name">{s.fullName}</td>
                      <td className="att-col-num">{s.attended} / {s.total}</td>
                      <td className={`att-col-num att-pct ${s.belowThreshold ? 'att-pct-low' : ''}`}>
                        {formatPct(s.percentage)}
                      </td>
                      {s.cells.map((cell, i) => {
                        const cfg = CELL_CONFIG[cell];
                        const session = sessions[i];
                        return (
                          <td
                            key={session.id}
                            className={`att-cell ${session.counted ? '' : 'att-col-live'}`}
                            title={`${cfg.label} — ${formatFull(session.createdAt)}${session.counted ? '' : ' (live)'}`}
                          >
                            <span className={`att-cell-mark ${cfg.className}`} aria-hidden="true">{cfg.symbol}</span>
                            <span className="sr-only">{cfg.label}</span>
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
                {sessions.length > 0 && rows.length > 0 && (
                  <tfoot>
                    <tr>
                      <th scope="row" className="att-sticky att-col-roll">Present</th>
                      <td className="att-sticky att-col-name" />
                      <td className="att-col-num" />
                      <td className="att-col-num" />
                      {sessions.map((s) => (
                        <td key={s.id} className={`att-cell att-foot-count ${s.counted ? '' : 'att-col-live'}`}>
                          {s.confirmedCount}
                        </td>
                      ))}
                    </tr>
                  </tfoot>
                )}
              </table>
            </div>
            <p className="att-count">
              Showing {rows.length} of {report.students.length} students
            </p>
          </>
        )}
      </Card>

      <ToastViewport toasts={toasts} onRemove={removeToast} />
    </section>
  );
};

export default CourseAttendancePage;
