import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, BookOpen, Users, Trash2, ChevronRight, X } from 'lucide-react';
import { Card, CardHeader, CardBody, Button, Input, Badge, Loader } from '../../components/ui';
import { apiFetch, ApiError } from '../../lib/api';
import { useToast } from '../../hooks/useToast';
import { ToastViewport } from '../../components/ui/Toast';
import type { CourseResponse, CourseRequest } from '../../types/course';
import './CoursesPage.css';

// ── Create Course Modal ───────────────────────────────────────────

interface CreateModalProps {
  onClose: () => void;
  onCreated: (c: CourseResponse) => void;
}

const CreateCourseModal: React.FC<CreateModalProps> = ({ onClose, onCreated }) => {
  const [form, setForm]     = useState<CourseRequest>({ name: '', code: '', semester: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState<string | null>(null);

  const set = (field: keyof CourseRequest) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((p) => ({ ...p, [field]: e.target.value }));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!form.name.trim() || !form.code.trim() || !form.semester.trim()) {
      setError('All fields are required.');
      return;
    }
    setLoading(true);
    try {
      const created = await apiFetch<CourseResponse>('/courses', {
        method: 'POST',
        body: JSON.stringify({
          name:     form.name.trim(),
          code:     form.code.trim().toUpperCase(),
          semester: form.semester.trim(),
        }),
      });
      onCreated(created);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to create course.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose} role="dialog" aria-modal="true" aria-label="Create course">
      <div className="modal-panel anim-fade-in-scale" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>New Course</h3>
          <button className="modal-close" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>
        <form className="modal-form" onSubmit={handleSubmit} noValidate>
          <Input label="Course name" id="cn-name" placeholder="Data Structures" value={form.name} onChange={set('name')} disabled={loading} />
          <Input label="Course code" id="cn-code" placeholder="CS201" value={form.code} onChange={set('code')} disabled={loading} />
          <Input label="Semester" id="cn-sem"  placeholder="Fall 2026" value={form.semester} onChange={set('semester')} disabled={loading} />
          {error && <p className="modal-error" role="alert">{error}</p>}
          <div className="modal-actions">
            <Button type="button" variant="ghost" onClick={onClose} disabled={loading}>Cancel</Button>
            <Button type="submit" variant="primary" loading={loading} leftIcon={<Plus size={16} />}>Create</Button>
          </div>
        </form>
      </div>
    </div>
  );
};

// ── Delete Confirm Modal ──────────────────────────────────────────

interface DeleteModalProps {
  course: CourseResponse;
  onClose: () => void;
  onDeleted: (id: string) => void;
}

const DeleteCourseModal: React.FC<DeleteModalProps> = ({ course, onClose, onDeleted }) => {
  const [loading, setLoading] = useState(false);

  const handleDelete = async () => {
    setLoading(true);
    try {
      await apiFetch<void>(`/courses/${course.id}`, { method: 'DELETE' });
      onDeleted(course.id);
    } catch (err) {
      // 404 means already gone — treat as success
      if (err instanceof ApiError && err.status === 404) {
        onDeleted(course.id);
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose} role="dialog" aria-modal="true" aria-label="Delete course">
      <div className="modal-panel modal-panel-sm anim-fade-in-scale" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>Delete Course</h3>
          <button className="modal-close" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>
        <div className="modal-body">
          <p>Are you sure you want to delete <strong>{course.name}</strong>? This will remove all associated students and sessions.</p>
        </div>
        <div className="modal-actions">
          <Button variant="ghost" onClick={onClose} disabled={loading}>Cancel</Button>
          <Button variant="danger" loading={loading} onClick={handleDelete} leftIcon={<Trash2 size={16} />}>Delete</Button>
        </div>
      </div>
    </div>
  );
};

// ── Skeleton card ─────────────────────────────────────────────────

const SkeletonCard: React.FC = () => (
  <div className="course-card skeleton-card">
    <div className="skeleton sk-title" />
    <div className="skeleton sk-subtitle" />
    <div className="skeleton sk-meta" />
  </div>
);

// ── Empty state ───────────────────────────────────────────────────

interface EmptyProps { onNew: () => void }
const EmptyState: React.FC<EmptyProps> = ({ onNew }) => (
  <div className="courses-empty anim-fade-in">
    <div className="courses-empty-icon" aria-hidden="true"><BookOpen size={40} strokeWidth={1.25} /></div>
    <h2>No courses yet</h2>
    <p>Create your first course to start taking attendance.</p>
    <Button variant="primary" leftIcon={<Plus size={18} />} onClick={onNew}>Create a Course</Button>
  </div>
);

// ── Main page ─────────────────────────────────────────────────────

const CoursesPage: React.FC = () => {
  const navigate = useNavigate();
  const { toasts, removeToast, success, error: toastError } = useToast();

  const [courses, setCourses] = useState<CourseResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<CourseResponse | null>(null);

  const fetchCourses = useCallback(async () => {
    try {
      const data = await apiFetch<CourseResponse[]>('/courses');
      setCourses(data);
    } catch (err) {
      toastError('Failed to load courses', err instanceof ApiError ? err.message : undefined);
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => { fetchCourses(); }, [fetchCourses]);

  const handleCreated = (c: CourseResponse) => {
    setCourses((prev) => [c, ...prev]);
    setShowCreate(false);
    success('Course created', `${c.name} (${c.code})`);
  };

  const handleDeleted = (id: string) => {
    setCourses((prev) => prev.filter((c) => c.id !== id));
    setDeleteTarget(null);
    success('Course deleted');
  };

  return (
    <section className="courses-page">
      {/* ── Header ── */}
      <div className="courses-header">
        <div>
          <h1 className="courses-title">Courses</h1>
          <p className="courses-subtitle">{courses.length} course{courses.length !== 1 ? 's' : ''} in your account</p>
        </div>
        <Button
          id="create-course-btn"
          variant="primary"
          leftIcon={<Plus size={18} />}
          onClick={() => setShowCreate(true)}
        >
          New Course
        </Button>
      </div>

      {/* ── Grid ── */}
      {loading ? (
        <div className="courses-grid">
          {[1, 2, 3].map((n) => <SkeletonCard key={n} />)}
        </div>
      ) : courses.length === 0 ? (
        <EmptyState onNew={() => setShowCreate(true)} />
      ) : (
        <div className="courses-grid">
          {courses.map((c) => (
            <div key={c.id} className="course-card" onClick={() => navigate(`/courses/${c.id}`)}>
              <div className="course-card-inner">
                <div className="course-card-top">
                  <div className="course-icon" aria-hidden="true"><BookOpen size={20} strokeWidth={1.5} /></div>
                  <Badge variant="primary">{c.code}</Badge>
                </div>
                <h3 className="course-name">{c.name}</h3>
                <p className="course-semester">{c.semester}</p>
                <div className="course-meta">
                  <span className="course-meta-item"><Users size={14} aria-hidden="true" />{c.studentCount} students</span>
                  <button
                    className="course-delete-btn"
                    onClick={(e) => { e.stopPropagation(); setDeleteTarget(c); }}
                    aria-label={`Delete ${c.name}`}
                  >
                    <Trash2 size={15} />
                  </button>
                  <ChevronRight size={16} className="course-chevron" aria-hidden="true" />
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* ── Modals ── */}
      {showCreate && (
        <CreateCourseModal onClose={() => setShowCreate(false)} onCreated={handleCreated} />
      )}
      {deleteTarget && (
        <DeleteCourseModal course={deleteTarget} onClose={() => setDeleteTarget(null)} onDeleted={handleDeleted} />
      )}

      <ToastViewport toasts={toasts} onRemove={removeToast} />
    </section>
  );
};

export default CoursesPage;
