import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import {
  ArrowLeft, Users, Upload, Plus, Trash2, Play, X, FileSpreadsheet,
} from 'lucide-react';
import { Card, CardHeader, CardBody, Button, Input, Badge, Loader } from '../../components/ui';
import { apiFetch, apiUpload, ApiError } from '../../lib/api';
import { useToast } from '../../hooks/useToast';
import { ToastViewport } from '../../components/ui/Toast';
import type { CourseResponse } from '../../types/course';
import type { StudentResponse, StudentRequest, RosterSyncReport } from '../../types/student';
import type { SessionResponse } from '../../types/session';
import './CourseDetailPage.css';

// ── Add Student Modal ─────────────────────────────────────────────

interface AddStudentModalProps {
  courseId: string;
  onClose: () => void;
  onAdded: (s: StudentResponse) => void;
}

const AddStudentModal: React.FC<AddStudentModalProps> = ({ courseId, onClose, onAdded }) => {
  const [form, setForm]     = useState<StudentRequest>({ rollNumber: '', name: '', email: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState<string | null>(null);

  const set = (f: keyof StudentRequest) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((p) => ({ ...p, [f]: e.target.value }));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!form.rollNumber.trim() || !form.name.trim()) {
      setError('Roll number and name are required.');
      return;
    }
    setLoading(true);
    try {
      const created = await apiFetch<StudentResponse>(`/courses/${courseId}/students`, {
        method: 'POST',
        body: JSON.stringify({
          rollNumber: form.rollNumber.trim().toUpperCase(),
          name:       form.name.trim(),
          email:      form.email?.trim() || undefined,
        }),
      });
      onAdded(created);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to add student.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose} role="dialog" aria-modal="true" aria-label="Add student">
      <div className="modal-panel anim-fade-in-scale" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>Add Student</h3>
          <button className="modal-close" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>
        <form className="modal-form" onSubmit={handleSubmit} noValidate>
          <Input label="Roll number" id="as-roll" placeholder="CS24B001" value={form.rollNumber} onChange={set('rollNumber')} disabled={loading} />
          <Input label="Name" id="as-name" placeholder="Alice Johnson" value={form.name} onChange={set('name')} disabled={loading} />
          <Input label="Email (optional)" type="email" id="as-email" placeholder="alice@college.edu" value={form.email ?? ''} onChange={set('email')} disabled={loading} />
          {error && <p className="modal-error" role="alert">{error}</p>}
          <div className="modal-actions">
            <Button type="button" variant="ghost" onClick={onClose} disabled={loading}>Cancel</Button>
            <Button type="submit" variant="primary" loading={loading} leftIcon={<Plus size={16} />}>Add</Button>
          </div>
        </form>
      </div>
    </div>
  );
};

// ── Start Session Modal ───────────────────────────────────────────

interface StartSessionModalProps {
  courseId: string;
  onClose: () => void;
  onStarted: (session: SessionResponse) => void;
}

const StartSessionModal: React.FC<StartSessionModalProps> = ({ courseId, onClose, onStarted }) => {
  const [duration, setDuration] = useState(120);
  const [loading, setLoading]   = useState(false);
  const [error, setError]       = useState<string | null>(null);

  const handleStart = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (duration < 1) {
      setError('Duration must be at least 1 second.');
      return;
    }
    setLoading(true);
    try {
      const session = await apiFetch<SessionResponse>('/sessions', {
        method: 'POST',
        body: JSON.stringify({ courseId, durationSeconds: duration }),
      });
      onStarted(session);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to start session.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose} role="dialog" aria-modal="true" aria-label="Start session">
      <div className="modal-panel modal-panel-sm anim-fade-in-scale" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>Start Attendance Session</h3>
          <button className="modal-close" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>
        <form className="modal-form" onSubmit={handleStart} noValidate>
          <div className="duration-field">
            <label htmlFor="session-duration" className="duration-label">Session duration (seconds)</label>
            <input
              id="session-duration"
              type="number"
              className="duration-input"
              min={1}
              max={3600}
              value={duration}
              onChange={(e) => setDuration(Number(e.target.value))}
              disabled={loading}
            />
            <p className="duration-hint">Default 120 s · Students have 15 s to scan the QR code</p>
          </div>
          {error && <p className="modal-error" role="alert">{error}</p>}
          <div className="modal-actions">
            <Button type="button" variant="ghost" onClick={onClose} disabled={loading}>Cancel</Button>
            <Button type="submit" variant="success" loading={loading} leftIcon={<Play size={16} />}>Start Session</Button>
          </div>
        </form>
      </div>
    </div>
  );
};

// ── Main Page ─────────────────────────────────────────────────────

const MAX_FILE_BYTES = 5 * 1024 * 1024; // 5 MB
const ALLOWED_MIME   = [
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', // .xlsx
  'application/vnd.ms-excel', // .xls
];

const CourseDetailPage: React.FC = () => {
  const { courseId } = useParams<{ courseId: string }>();
  const navigate = useNavigate();
  const { toasts, removeToast, success, error: toastError, info } = useToast();
  const fileRef = useRef<HTMLInputElement>(null);

  const [course, setCourse]       = useState<CourseResponse | null>(null);
  const [students, setStudents]   = useState<StudentResponse[]>([]);
  const [loading, setLoading]     = useState(true);
  const [notFound, setNotFound]   = useState(false);
  const [uploading, setUploading] = useState(false);

  const [showAdd, setShowAdd]         = useState(false);
  const [showSession, setShowSession] = useState(false);

  const fetchAll = useCallback(async () => {
    if (!courseId) return;
    setLoading(true);
    try {
      const [c, s] = await Promise.all([
        apiFetch<CourseResponse>(`/courses/${courseId}`),
        apiFetch<StudentResponse[]>(`/courses/${courseId}/students`),
      ]);
      setCourse(c);
      setStudents(s);
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        setNotFound(true);
      } else {
        toastError('Failed to load course', err instanceof ApiError ? err.message : undefined);
      }
    } finally {
      setLoading(false);
    }
  }, [courseId, toastError]);

  useEffect(() => { fetchAll(); }, [fetchAll]);

  const handleDeleteStudent = async (studentId: string, name: string) => {
    try {
      await apiFetch<void>(`/students/${studentId}`, { method: 'DELETE' });
      setStudents((prev) => prev.filter((s) => s.id !== studentId));
      info(`${name} removed`);
    } catch (err) {
      toastError('Failed to remove student', err instanceof ApiError ? err.message : undefined);
    }
  };

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    // Client-side validation
    const ext = file.name.split('.').pop()?.toLowerCase();
    const mimeOk = ALLOWED_MIME.includes(file.type);
    const extOk  = ext === 'xlsx' || ext === 'xls';
    if (!mimeOk && !extOk) {
      toastError('Invalid file', 'Please upload an Excel file (.xlsx or .xls)');
      if (fileRef.current) fileRef.current.value = '';
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      toastError('File too large', 'Maximum allowed size is 5 MB');
      if (fileRef.current) fileRef.current.value = '';
      return;
    }

    setUploading(true);
    try {
      const fd = new FormData();
      fd.append('file', file);
      const report = await apiUpload<RosterSyncReport>(`/courses/${courseId}/students/import`, fd);
      success(
        'Roster synced',
        `Added ${report.added} · Removed ${report.removed} · Unchanged ${report.unchanged}`,
      );
      // Re-fetch updated student list
      const updated = await apiFetch<StudentResponse[]>(`/courses/${courseId}/students`);
      setStudents(updated);
    } catch (err) {
      toastError('Import failed', err instanceof ApiError ? err.message : undefined);
    } finally {
      setUploading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  const handleSessionStarted = (session: SessionResponse) => {
    navigate(`/sessions/${session.id}`);
  };

  if (loading) {
    return <div className="detail-loading"><Loader variant="spinner" size="lg" label="Loading course…" /></div>;
  }

  if (notFound) {
    return (
      <div className="detail-not-found">
        <h2>Course not found</h2>
        <p>This course may have been deleted.</p>
        <Link to="/courses"><Button variant="secondary" leftIcon={<ArrowLeft size={16} />}>Back to Courses</Button></Link>
      </div>
    );
  }

  if (!course) return null;

  return (
    <section className="detail-page">
      {/* ── Breadcrumb ── */}
      <div className="detail-breadcrumb">
        <Link to="/courses" className="breadcrumb-link">
          <ArrowLeft size={16} aria-hidden="true" /> Courses
        </Link>
        <span className="breadcrumb-sep">/</span>
        <span className="breadcrumb-current">{course.name}</span>
      </div>

      {/* ── Hero ── */}
      <Card glass accent className="detail-hero">
        <div className="detail-hero-inner">
          <div>
            <div className="detail-hero-top">
              <Badge variant="primary">{course.code}</Badge>
              <span className="detail-semester">{course.semester}</span>
            </div>
            <h1 className="detail-course-name">{course.name}</h1>
            <p className="detail-student-count"><Users size={15} aria-hidden="true" /> {course.studentCount} students enrolled</p>
          </div>
          <Button
            id="start-session-btn"
            variant="success"
            size="lg"
            leftIcon={<Play size={18} />}
            onClick={() => setShowSession(true)}
          >
            Start Session
          </Button>
        </div>
      </Card>

      {/* ── Students Card ── */}
      <Card className="students-card">
        <CardHeader
          title={`Students (${students.length})`}
          subtitle="Manage the roster for this course"
          action={
            <div className="roster-actions">
              {/* Hidden file input */}
              <input
                ref={fileRef}
                type="file"
                accept=".xlsx,.xls"
                id="roster-import-input"
                className="sr-only"
                onChange={handleFileSelect}
                disabled={uploading}
              />
              <Button
                variant="ghost"
                size="sm"
                leftIcon={uploading ? <Loader variant="spinner" size="sm" /> : <FileSpreadsheet size={16} />}
                onClick={() => fileRef.current?.click()}
                disabled={uploading}
                id="import-excel-btn"
              >
                {uploading ? 'Importing…' : 'Import Excel'}
              </Button>
              <Button
                variant="secondary"
                size="sm"
                leftIcon={<Plus size={16} />}
                onClick={() => setShowAdd(true)}
                id="add-student-btn"
              >
                Add Student
              </Button>
            </div>
          }
        />
        <CardBody>
          {students.length === 0 ? (
            <div className="students-empty">
              <Users size={32} strokeWidth={1.25} aria-hidden="true" />
              <p>No students yet. Add them manually or import from Excel.</p>
            </div>
          ) : (
            <div className="students-table-wrap">
              <table className="students-table" aria-label="Student roster">
                <thead>
                  <tr>
                    <th>Roll No.</th>
                    <th>Name</th>
                    <th>Email</th>
                    <th aria-label="Actions" />
                  </tr>
                </thead>
                <tbody>
                  {students.map((s) => (
                    <tr key={s.id}>
                      <td><code className="roll-no">{s.rollNumber}</code></td>
                      <td>{s.name}</td>
                      <td className="email-cell">{s.email ?? <span className="no-email">—</span>}</td>
                      <td>
                        <button
                          className="student-delete-btn"
                          onClick={() => handleDeleteStudent(s.id, s.name)}
                          aria-label={`Remove ${s.name}`}
                        >
                          <Trash2 size={15} />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardBody>
      </Card>

      {/* ── Modals ── */}
      {showAdd && (
        <AddStudentModal
          courseId={courseId!}
          onClose={() => setShowAdd(false)}
          onAdded={(s) => { setStudents((prev) => [s, ...prev]); setShowAdd(false); success(`${s.name} added`); }}
        />
      )}
      {showSession && (
        <StartSessionModal
          courseId={courseId!}
          onClose={() => setShowSession(false)}
          onStarted={handleSessionStarted}
        />
      )}

      <ToastViewport toasts={toasts} onRemove={removeToast} />
    </section>
  );
};

export default CourseDetailPage;
