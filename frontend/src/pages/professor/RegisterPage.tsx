import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Card, CardHeader, Button, Badge, Input } from '../../components/ui';
import { KeyRound, UserPlus } from 'lucide-react';
import { apiFetch, ApiError } from '../../lib/api';
import type { RegisterResponse } from '../../types/auth';
import './RegisterPage.css';

interface FormState {
  inviteCode: string;
  fullName: string;
  email: string;
  password: string;
  confirmPassword: string;
}

const INIT: FormState = {
  inviteCode: '',
  fullName: '',
  email: '',
  password: '',
  confirmPassword: '',
};

const RegisterPage: React.FC = () => {
  const navigate = useNavigate();
  const [form, setForm]     = useState<FormState>(INIT);
  const [loading, setLoading] = useState(false);
  const [errors, setErrors]   = useState<Partial<FormState>>({});
  const [apiError, setApiError] = useState<string | null>(null);
  const [success, setSuccess]   = useState(false);

  const set = (field: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setForm((prev) => ({ ...prev, [field]: e.target.value }));
    // Clear field error on change
    if (errors[field]) setErrors((prev) => ({ ...prev, [field]: undefined }));
  };

  const validate = (): boolean => {
    const errs: Partial<FormState> = {};

    if (!form.inviteCode.trim()) errs.inviteCode = 'Invite code is required.';
    if (!form.fullName.trim())   errs.fullName   = 'Full name is required.';

    if (!form.email.trim()) {
      errs.email = 'Email is required.';
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
      errs.email = 'Enter a valid email address.';
    }

    if (!form.password) {
      errs.password = 'Password is required.';
    } else if (form.password.length < 8) {
      errs.password = 'Password must be at least 8 characters.';
    }

    if (!form.confirmPassword) {
      errs.confirmPassword = 'Please confirm your password.';
    } else if (form.password !== form.confirmPassword) {
      errs.confirmPassword = 'Passwords do not match.';
    }

    setErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setApiError(null);
    if (!validate()) return;

    setLoading(true);
    try {
      // confirmPassword is never sent to the backend
      await apiFetch<RegisterResponse>('/auth/register', {
        method: 'POST',
        body: JSON.stringify({
          inviteCode: form.inviteCode.trim(),
          fullName:   form.fullName.trim(),
          email:      form.email.trim(),
          password:   form.password,
        }),
      });
      setSuccess(true);
      setTimeout(() => navigate('/login'), 2500);
    } catch (err) {
      if (err instanceof ApiError) {
        // Backend returns semicolon-joined field errors — display them clearly
        setApiError(err.message.replace(/;\s*/g, '\n'));
      } else {
        setApiError('An unexpected error occurred. Please try again.');
      }
    } finally {
      setLoading(false);
    }
  };

  if (success) {
    return (
      <main className="register-root">
        <div className="register-container anim-fade-in-scale">
          <Card glass accent padding="lg" className="register-card">
            <div className="register-success">
              <span className="register-success-icon" aria-hidden="true">✓</span>
              <h2>Account created!</h2>
              <p>Redirecting you to sign in…</p>
            </div>
          </Card>
        </div>
      </main>
    );
  }

  return (
    <main className="register-root">
      <div className="register-orb register-orb-1" aria-hidden="true" />
      <div className="register-orb register-orb-2" aria-hidden="true" />

      <div className="register-container anim-fade-in-scale">
        <div className="register-brand">
          <span className="register-icon-wrap" aria-hidden="true">
            <UserPlus size={26} strokeWidth={1.75} />
          </span>
          <div>
            <h1 className="register-app-name gradient-text">Create Account</h1>
            <p className="register-app-tagline">Register with an admin invite code</p>
          </div>
        </div>

        <Card glass accent padding="lg" className="register-card">
          <CardHeader
            title="Professor Registration"
            subtitle="Fill in your details below"
            action={<Badge variant="warning" dot>Invite only</Badge>}
          />

          <form className="register-form stagger" onSubmit={handleSubmit} noValidate>
            <Input
              label="Invite code"
              type="text"
              id="register-invite"
              placeholder="Paste your invite code"
              leftAddon={<KeyRound size={16} />}
              value={form.inviteCode}
              onChange={set('inviteCode')}
              error={errors.inviteCode}
              disabled={loading}
              autoComplete="off"
            />

            <Input
              label="Full name"
              type="text"
              id="register-name"
              placeholder="Dr. Jane Smith"
              value={form.fullName}
              onChange={set('fullName')}
              error={errors.fullName}
              disabled={loading}
              autoComplete="name"
            />

            <Input
              label="Email address"
              type="email"
              id="register-email"
              placeholder="professor@college.edu"
              value={form.email}
              onChange={set('email')}
              error={errors.email}
              disabled={loading}
              autoComplete="email"
            />

            <Input
              label="Password"
              type="password"
              id="register-password"
              placeholder="Min. 8 characters"
              value={form.password}
              onChange={set('password')}
              error={errors.password}
              disabled={loading}
              autoComplete="new-password"
            />

            <Input
              label="Confirm password"
              type="password"
              id="register-confirm"
              placeholder="Repeat your password"
              value={form.confirmPassword}
              onChange={set('confirmPassword')}
              error={errors.confirmPassword}
              disabled={loading}
              autoComplete="new-password"
            />

            {apiError && (
              <p className="register-error" role="alert" style={{ whiteSpace: 'pre-line' }}>
                {apiError}
              </p>
            )}

            <Button
              type="submit"
              variant="primary"
              size="lg"
              fullWidth
              loading={loading}
            >
              Create Account
            </Button>
          </form>

          <p className="register-footer-note">
            Already have an account?{' '}
            <Link to="/login" className="register-login-link">
              Sign in
            </Link>
          </p>
        </Card>
      </div>
    </main>
  );
};

export default RegisterPage;
