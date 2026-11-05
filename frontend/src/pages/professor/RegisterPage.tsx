import React, { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Card, CardHeader, Button, Badge, Input } from '../../components/ui';
import { Mail, ShieldCheck, UserPlus } from 'lucide-react';
import { apiFetch, ApiError } from '../../lib/api';
import type { OtpResponse, RegisterResponse } from '../../types/auth';
import './RegisterPage.css';

// Mirrors app.registration.* on the backend (which is the real check).
const ALLOWED_DOMAIN = 'iitm.ac.in';
const BLOCKED_DOMAIN = 'smail.iitm.ac.in';

const matchesDomain = (domain: string, base: string) => domain === base || domain.endsWith(`.${base}`);

/** Returns an error message for the email, or null if it looks acceptable. */
function emailProblem(raw: string): string | null {
  const email = raw.trim().toLowerCase();
  if (!email) return 'Email is required.';
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return 'Enter a valid email address.';
  const domain = email.slice(email.lastIndexOf('@') + 1);
  if (matchesDomain(domain, BLOCKED_DOMAIN)) {
    return `Student addresses (@${BLOCKED_DOMAIN}) can't register. Use your faculty email.`;
  }
  if (!matchesDomain(domain, ALLOWED_DOMAIN)) return `Use your IIT Madras email (…@${ALLOWED_DOMAIN}).`;
  return null;
}

interface DetailsState {
  otp: string;
  fullName: string;
  password: string;
  confirmPassword: string;
}

const INIT_DETAILS: DetailsState = { otp: '', fullName: '', password: '', confirmPassword: '' };

const RegisterPage: React.FC = () => {
  const navigate = useNavigate();

  const [step, setStep]         = useState<'email' | 'details'>('email');
  const [email, setEmail]       = useState('');
  const [emailError, setEmailError] = useState<string | null>(null);
  const [details, setDetails]   = useState<DetailsState>(INIT_DETAILS);
  const [errors, setErrors]     = useState<Partial<DetailsState>>({});
  const [apiError, setApiError] = useState<string | null>(null);
  const [alreadyRegistered, setAlreadyRegistered] = useState(false);
  const [notice, setNotice]     = useState<string | null>(null);
  const [sending, setSending]   = useState(false);
  const [loading, setLoading]   = useState(false);
  const [success, setSuccess]   = useState(false);
  const [resendIn, setResendIn] = useState(0);
  const [expiresMinutes, setExpiresMinutes] = useState(10);
  const otpRef = useRef<HTMLInputElement>(null);

  // Resend countdown
  useEffect(() => {
    if (resendIn <= 0) return;
    const t = setTimeout(() => setResendIn((s) => s - 1), 1000);
    return () => clearTimeout(t);
  }, [resendIn]);

  // ── Step 1: send the code ──

  const sendCode = async (isResend = false) => {
    setApiError(null);
    setAlreadyRegistered(false);
    setNotice(null);
    const problem = emailProblem(email);
    setEmailError(problem);
    if (problem) return;

    setSending(true);
    try {
      const res = await apiFetch<OtpResponse>('/auth/otp', {
        method: 'POST',
        body: JSON.stringify({ email: email.trim() }),
      });
      setResendIn(res.resendAfterSeconds);
      setExpiresMinutes(Math.round(res.expiresInSeconds / 60));
      setStep('details');
      setNotice(isResend ? 'A new code has been sent. Earlier codes no longer work.' : null);
      if (isResend) setDetails((d) => ({ ...d, otp: '' }));
      setTimeout(() => otpRef.current?.focus(), 0);
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 409) setAlreadyRegistered(true);
        const waitMatch = err.status === 429 ? /wait (\d+) seconds/.exec(err.message) : null;
        if (waitMatch) setResendIn(Number(waitMatch[1]));
        if (step === 'email' && err.status === 400) setEmailError(err.message);
        else setApiError(err.message);
      } else {
        setApiError('Could not send the code. Please try again.');
      }
    } finally {
      setSending(false);
    }
  };

  const handleEmailSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    sendCode(false);
  };

  const changeEmail = () => {
    setStep('email');
    setDetails((d) => ({ ...d, otp: '' }));
    setErrors({});
    setApiError(null);
    setNotice(null);
  };

  // ── Step 2: verify + create the account ──

  const setField = (field: keyof DetailsState) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = field === 'otp' ? e.target.value.replace(/\D/g, '').slice(0, 6) : e.target.value;
    setDetails((prev) => ({ ...prev, [field]: value }));
    if (errors[field]) setErrors((prev) => ({ ...prev, [field]: undefined }));
  };

  const validateDetails = (): boolean => {
    const errs: Partial<DetailsState> = {};
    if (!/^\d{6}$/.test(details.otp)) errs.otp = 'Enter the 6-digit code from the email.';
    if (!details.fullName.trim()) errs.fullName = 'Full name is required.';
    if (!details.password) errs.password = 'Password is required.';
    else if (details.password.length < 8) errs.password = 'Password must be at least 8 characters.';
    if (!details.confirmPassword) errs.confirmPassword = 'Please confirm your password.';
    else if (details.password !== details.confirmPassword) errs.confirmPassword = 'Passwords do not match.';
    setErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    setApiError(null);
    setNotice(null);
    if (!validateDetails()) return;

    setLoading(true);
    try {
      // confirmPassword is never sent to the backend
      await apiFetch<RegisterResponse>('/auth/register', {
        method: 'POST',
        body: JSON.stringify({
          email:    email.trim(),
          otp:      details.otp,
          fullName: details.fullName.trim(),
          password: details.password,
        }),
      });
      setSuccess(true);
      setTimeout(() => navigate('/login'), 2500);
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 409) setAlreadyRegistered(true);
        // Backend returns semicolon-joined field errors — show one per line
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

  const errorBlock = apiError && (
    <p className="register-error" role="alert" style={{ whiteSpace: 'pre-line' }}>
      {apiError}
      {alreadyRegistered && (
        <> <Link to="/login" className="register-login-link">Sign in</Link></>
      )}
    </p>
  );

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
            <p className="register-app-tagline">For IIT Madras faculty</p>
          </div>
        </div>

        <Card glass accent padding="lg" className="register-card">
          <CardHeader
            title="Professor Registration"
            subtitle={step === 'email' ? 'Step 1 of 2 — verify your email' : 'Step 2 of 2 — your details'}
            action={<Badge variant="primary" dot>@{ALLOWED_DOMAIN}</Badge>}
          />

          {step === 'email' ? (
            <form className="register-form stagger" onSubmit={handleEmailSubmit} noValidate>
              <Input
                label="Faculty email"
                type="email"
                id="register-email"
                placeholder={`name@${ALLOWED_DOMAIN}`}
                leftAddon={<Mail size={16} />}
                value={email}
                onChange={(e) => { setEmail(e.target.value); setEmailError(null); setAlreadyRegistered(false); }}
                error={emailError ?? undefined}
                hint={emailError ? undefined : `We'll email you a 6-digit code. Student @${BLOCKED_DOMAIN} addresses can't register.`}
                disabled={sending}
                autoComplete="email"
                autoFocus
              />
              {alreadyRegistered && !apiError && (
                <p className="register-error" role="alert">
                  An account with this email already exists.{' '}
                  <Link to="/login" className="register-login-link">Sign in</Link>
                </p>
              )}
              {errorBlock}
              <Button type="submit" variant="primary" size="lg" fullWidth loading={sending}>
                Send verification code
              </Button>
            </form>
          ) : (
            <form className="register-form stagger" onSubmit={handleRegister} noValidate>
              <div className="register-sent-to">
                <span>Code sent to <strong>{email.trim().toLowerCase()}</strong></span>
                <button type="button" className="register-link-btn" onClick={changeEmail} disabled={loading}>
                  Change email
                </button>
              </div>

              <Input
                ref={otpRef}
                label="Verification code"
                type="text"
                id="register-otp"
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder="6-digit code"
                leftAddon={<ShieldCheck size={16} />}
                value={details.otp}
                onChange={setField('otp')}
                error={errors.otp}
                hint={errors.otp ? undefined : `Check your inbox (and spam). The code expires in ${expiresMinutes} minutes.`}
                disabled={loading}
                maxLength={6}
              />

              <div className="register-resend">
                {resendIn > 0 ? (
                  <span>You can request a new code in {resendIn}s</span>
                ) : (
                  <button type="button" className="register-link-btn" onClick={() => sendCode(true)} disabled={sending || loading}>
                    {sending ? 'Sending…' : 'Resend code'}
                  </button>
                )}
              </div>

              {notice && <p className="register-notice" role="status">{notice}</p>}

              <Input
                label="Full name"
                type="text"
                id="register-name"
                placeholder="Dr. Jane Smith"
                value={details.fullName}
                onChange={setField('fullName')}
                error={errors.fullName}
                disabled={loading}
                autoComplete="name"
              />

              <Input
                label="Password"
                type="password"
                id="register-password"
                placeholder="Min. 8 characters"
                value={details.password}
                onChange={setField('password')}
                error={errors.password}
                disabled={loading}
                autoComplete="new-password"
              />

              <Input
                label="Confirm password"
                type="password"
                id="register-confirm"
                placeholder="Repeat your password"
                value={details.confirmPassword}
                onChange={setField('confirmPassword')}
                error={errors.confirmPassword}
                disabled={loading}
                autoComplete="new-password"
              />

              {errorBlock}

              <Button type="submit" variant="primary" size="lg" fullWidth loading={loading}>
                Create Account
              </Button>
            </form>
          )}

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
