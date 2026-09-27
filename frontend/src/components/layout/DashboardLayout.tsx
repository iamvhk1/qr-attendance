import React, { useState } from 'react';
import { Outlet, NavLink, useNavigate } from 'react-router-dom';
import {
  LayoutDashboard,
  BookOpen,
  LogOut,
  Menu,
  X,
  ShieldCheck,
  ChevronRight,
} from 'lucide-react';
import { useAuth } from '../../lib/auth';
import './DashboardLayout.css';

const DashboardLayout: React.FC = () => {
  const { professor, logout } = useAuth();
  const navigate = useNavigate();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <div className="dashboard-root">
      {/* ── Mobile overlay ─────────────────────────────────── */}
      {sidebarOpen && (
        <div
          className="sidebar-overlay"
          onClick={() => setSidebarOpen(false)}
          aria-hidden="true"
        />
      )}

      {/* ── Sidebar ────────────────────────────────────────── */}
      <aside className={`sidebar ${sidebarOpen ? 'sidebar-open' : ''}`} aria-label="Navigation">
        {/* Brand */}
        <div className="sidebar-brand">
          <span className="sidebar-brand-icon" aria-hidden="true">
            <ShieldCheck size={22} strokeWidth={1.75} />
          </span>
          <span className="sidebar-brand-name gradient-text">QR Attend</span>
          <button
            className="sidebar-close-btn"
            onClick={() => setSidebarOpen(false)}
            aria-label="Close navigation"
          >
            <X size={18} />
          </button>
        </div>

        {/* Nav links */}
        <nav className="sidebar-nav">
          <NavLink
            to="/courses"
            className={({ isActive }) =>
              `sidebar-link ${isActive ? 'sidebar-link-active' : ''}`
            }
            onClick={() => setSidebarOpen(false)}
          >
            <BookOpen size={18} aria-hidden="true" />
            <span>Courses</span>
            <ChevronRight size={14} className="sidebar-chevron" aria-hidden="true" />
          </NavLink>
        </nav>

        {/* Professor info + logout */}
        <div className="sidebar-footer">
          <div className="sidebar-professor">
            <div className="sidebar-avatar" aria-hidden="true">
              {professor?.fullName?.[0]?.toUpperCase() ?? 'P'}
            </div>
            <div className="sidebar-professor-info">
              <p className="sidebar-professor-name">{professor?.fullName ?? 'Professor'}</p>
              <p className="sidebar-professor-email">{professor?.email ?? ''}</p>
            </div>
          </div>
          <button
            className="sidebar-logout"
            onClick={handleLogout}
            aria-label="Log out"
          >
            <LogOut size={16} aria-hidden="true" />
            <span>Log out</span>
          </button>
        </div>
      </aside>

      {/* ── Main content area ───────────────────────────────── */}
      <div className="dashboard-main">
        {/* Topbar */}
        <header className="dashboard-topbar">
          <button
            className="topbar-menu-btn"
            onClick={() => setSidebarOpen(true)}
            aria-label="Open navigation"
          >
            <Menu size={20} />
          </button>

          <div className="topbar-brand">
            <LayoutDashboard size={18} color="var(--primary-400)" aria-hidden="true" />
            <span className="topbar-title">Professor Dashboard</span>
          </div>

          <div className="topbar-right">
            <span className="topbar-professor-name">{professor?.fullName ?? ''}</span>
          </div>
        </header>

        {/* Page content */}
        <main className="dashboard-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
};

export default DashboardLayout;
