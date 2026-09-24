import React, { Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Loader } from './components/ui';
import './App.css';

// ── Lazy-loaded page chunks ─────────────────────────────────────
// Each import() boundary creates a separate JS bundle (code-split).
// Professor pages — never shipped to the student scan interface.
const LandingPage    = lazy(() => import('./pages/professor/LandingPage'));
const LoginPage      = lazy(() => import('./pages/professor/LoginPage'));
const DashboardPage  = lazy(() => import('./pages/professor/DashboardPage'));

// Student page — lightweight mobile-optimised bundle.
const ScanPage       = lazy(() => import('./pages/student/ScanPage'));

// ── Suspense fallback ──────────────────────────────────────────
const PageLoader: React.FC = () => <Loader variant="fullscreen" label="Loading…" />;

// ── App ────────────────────────────────────────────────────────
const App: React.FC = () => (
  <BrowserRouter>
    <Suspense fallback={<PageLoader />}>
      <Routes>
        {/* Public routes */}
        <Route path="/login"  element={<LoginPage />} />

        {/* Student scan route (mobile-first, no auth required) */}
        <Route path="/scan"   element={<ScanPage />} />

        {/* Professor routes (Phase 2 will add auth guard) */}
        <Route path="/dashboard" element={<DashboardPage />} />

        {/* Landing page */}
        <Route path="/"       element={<LandingPage />} />
        <Route path="*"       element={<Navigate to="/login" replace />} />
      </Routes>
    </Suspense>
  </BrowserRouter>
);

export default App;
