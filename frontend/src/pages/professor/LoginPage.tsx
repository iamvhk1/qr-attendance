import React from 'react';
import { Card, CardHeader, Button, Badge, Input } from '../../components/ui';
import { LogIn, ShieldCheck } from 'lucide-react';
import './LoginPage.css';

/**
 * LoginPage — Phase 1 skeleton.
 * Renders a premium login card to prove the design system is wired.
 * Full auth logic will be implemented in Phase 2.
 */
const LoginPage: React.FC = () => (
  <main className="login-root">
    {/* Decorative background glow orbs */}
    <div className="login-orb login-orb-1" aria-hidden="true" />
    <div className="login-orb login-orb-2" aria-hidden="true" />

    <div className="login-container anim-fade-in-scale">
      {/* Brand mark */}
      <div className="login-brand">
        <span className="login-icon-wrap" aria-hidden="true">
          <ShieldCheck size={28} strokeWidth={1.75} />
        </span>
        <div>
          <h1 className="login-app-name gradient-text">QR Attendance</h1>
          <p className="login-app-tagline">Secure classroom presence system</p>
        </div>
      </div>

      <Card glass accent padding="lg" className="login-card">
        <CardHeader
          title="Professor Sign In"
          subtitle="Enter your credentials to access the dashboard"
          action={<Badge variant="primary" dot>Secure</Badge>}
        />

        <form className="login-form stagger" onSubmit={(e) => e.preventDefault()}>
          <Input
            label="Email address"
            type="email"
            id="login-email"
            placeholder="professor@college.edu"
            autoComplete="email"
            leftAddon={<LogIn size={16} />}
          />

          <Input
            label="Password"
            type="password"
            id="login-password"
            placeholder="••••••••"
            autoComplete="current-password"
          />

          <Button type="submit" variant="primary" size="lg" fullWidth>
            Sign In
          </Button>
        </form>

        <p className="login-footer-note">
          Don't have an account? Ask your admin for an invite code.
        </p>
      </Card>
    </div>
  </main>
);

export default LoginPage;
