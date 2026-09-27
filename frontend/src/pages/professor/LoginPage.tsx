import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { Card, CardHeader, Button, Badge, Input } from '../../components/ui';
import { LogIn, ShieldCheck } from 'lucide-react';
import { apiFetch, ApiError } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import type { LoginResponse } from '../../types/auth';
import './LoginPage.css';

const LoginPage: React.FC = () => {
  const { login } = useAuth();

  const [email, setEmail]       = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading]   = useState(false);
  const [error, setError]       = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    // Client-side validation
    if (!email.trim() || !password.trim()) {
      setError('Email and password are required.');
      return;
    }

    setLoading(true);
    try {
      const resp = await apiFetch<LoginResponse>('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email: email.trim(), password }),
      });
      login(resp); // navigates to /courses
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('An unexpected error occurred. Please try again.');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
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

          <form className="login-form stagger" onSubmit={handleSubmit} noValidate>
            <Input
              label="Email address"
              type="email"
              id="login-email"
              placeholder="professor@college.edu"
              autoComplete="email"
              leftAddon={<LogIn size={16} />}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              disabled={loading}
            />

            <Input
              label="Password"
              type="password"
              id="login-password"
              placeholder="••••••••"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={loading}
            />

            {error && (
              <p className="login-error" role="alert">
                {error}
              </p>
            )}

            <Button
              type="submit"
              variant="primary"
              size="lg"
              fullWidth
              loading={loading}
            >
              Sign In
            </Button>
          </form>

          <p className="login-footer-note">
            Don't have an account?{' '}
            <Link to="/register" className="login-register-link">
              Register with invite code
            </Link>
          </p>
        </Card>
      </div>
    </main>
  );
};

export default LoginPage;
