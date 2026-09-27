import React, { Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate, Outlet, useLocation } from 'react-router-dom';
import { Loader } from './components/ui';
import { AuthProvider, useAuth } from './lib/auth';
import './App.css';

// ── Lazy-loaded page chunks ──────────────────────────────────────
// Professor — auth pages
const LandingPage   = lazy(() => import('./pages/professor/LandingPage'));
const LoginPage     = lazy(() => import('./pages/professor/LoginPage'));
const RegisterPage  = lazy(() => import('./pages/professor/RegisterPage'));

// Professor — dashboard pages (protected)
const CoursesPage      = lazy(() => import('./pages/professor/CoursesPage'));
const CourseDetailPage = lazy(() => import('./pages/professor/CourseDetailPage'));
const SessionPage      = lazy(() => import('./pages/professor/SessionPage'));

// Layout
const DashboardLayout = lazy(() => import('./components/layout/DashboardLayout'));

// Student
const ScanPage = lazy(() => import('./pages/student/ScanPage'));

// ── Suspense fallback ────────────────────────────────────────────
const PageLoader: React.FC = () => <Loader variant="fullscreen" label="Loading…" />;

// ── Protected route guard ────────────────────────────────────────
const ProtectedRoute: React.FC = () => {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  return <Outlet />;
};

// ── Public-only route (redirect away if already logged in) ───────
const PublicRoute: React.FC = () => {
  const { isAuthenticated } = useAuth();
  if (isAuthenticated) return <Navigate to="/courses" replace />;
  return <Outlet />;
};

// ── App ──────────────────────────────────────────────────────────
const App: React.FC = () => (
  <BrowserRouter>
    <AuthProvider>
      <Suspense fallback={<PageLoader />}>
        <Routes>
          {/* Landing */}
          <Route path="/" element={<LandingPage />} />

          {/* Student scan route — no auth required */}
          <Route path="/scan" element={<ScanPage />} />

          {/* Public auth routes — redirect away if already logged in */}
          <Route element={<PublicRoute />}>
            <Route path="/login"    element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
          </Route>

          {/* Protected professor routes */}
          <Route element={<ProtectedRoute />}>
            <Route element={<DashboardLayout />}>
              <Route path="/courses"           element={<CoursesPage />} />
              <Route path="/courses/:courseId" element={<CourseDetailPage />} />
              <Route path="/sessions/:sessionId" element={<SessionPage />} />
            </Route>
          </Route>

          {/* Catch-all */}
          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      </Suspense>
    </AuthProvider>
  </BrowserRouter>
);

export default App;
