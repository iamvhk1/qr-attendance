import React from 'react';
import './Loader.css';

export type LoaderVariant = 'spinner' | 'dots' | 'pulse' | 'fullscreen';
export type LoaderSize   = 'sm' | 'md' | 'lg';

export interface LoaderProps {
  variant?: LoaderVariant;
  size?: LoaderSize;
  label?: string;
  className?: string;
}

/**
 * Loader — animated loading indicators.
 *
 * - `spinner` — single orbital ring (default)
 * - `dots`    — three bouncing dots
 * - `pulse`   — expanding ring
 * - `fullscreen` — centred full-viewport overlay
 */
export const Loader: React.FC<LoaderProps> = ({
  variant = 'spinner',
  size = 'md',
  label = 'Loading…',
  className = '',
}) => {
  if (variant === 'fullscreen') {
    return (
      <div className="loader-fullscreen" role="status" aria-label={label}>
        <div className="loader-fullscreen-inner">
          <SpinnerIcon size="lg" />
          <p className="loader-fullscreen-label">{label}</p>
        </div>
      </div>
    );
  }

  if (variant === 'dots') {
    return (
      <span className={`loader-dots loader-dots-${size} ${className}`} role="status" aria-label={label}>
        <span /><span /><span />
        <span className="sr-only">{label}</span>
      </span>
    );
  }

  if (variant === 'pulse') {
    return (
      <span className={`loader-pulse loader-pulse-${size} ${className}`} role="status" aria-label={label}>
        <span className="loader-pulse-ring" />
        <span className="loader-pulse-ring loader-pulse-ring-delay" />
        <span className="sr-only">{label}</span>
      </span>
    );
  }

  return (
    <span className={`loader-spinner loader-spinner-${size} ${className}`} role="status" aria-label={label}>
      <SpinnerIcon size={size} />
      <span className="sr-only">{label}</span>
    </span>
  );
};

/* Internal SVG spinner */
const SpinnerIcon: React.FC<{ size: LoaderSize }> = ({ size }) => {
  const dim = size === 'sm' ? 18 : size === 'md' ? 28 : 44;
  return (
    <svg
      width={dim}
      height={dim}
      viewBox="0 0 50 50"
      fill="none"
      className="loader-svg"
      aria-hidden="true"
    >
      <circle cx="25" cy="25" r="20" stroke="currentColor" strokeWidth="4" opacity="0.15" />
      <path
        d="M 25 5 A 20 20 0 0 1 45 25"
        stroke="url(#spinner-gradient)"
        strokeWidth="4"
        strokeLinecap="round"
      />
      <defs>
        <linearGradient id="spinner-gradient" x1="0%" y1="0%" x2="100%" y2="0%">
          <stop offset="0%" stopColor="var(--primary-400)" />
          <stop offset="100%" stopColor="var(--accent-400)" />
        </linearGradient>
      </defs>
    </svg>
  );
};
