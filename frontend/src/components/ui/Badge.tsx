import React from 'react';

export type BadgeVariant = 'primary' | 'success' | 'danger' | 'warning' | 'muted';

export interface BadgeProps {
  variant?: BadgeVariant;
  children: React.ReactNode;
  dot?: boolean;
  className?: string;
}

const variantClass: Record<BadgeVariant, string> = {
  primary: 'badge-primary',
  success: 'badge-success',
  danger:  'badge-danger',
  warning: 'badge-warning',
  muted:   '',
};

/**
 * Badge — compact status label.
 * Uses the .badge utility classes from index.css.
 */
export const Badge: React.FC<BadgeProps> = ({ variant = 'primary', children, dot = false, className = '' }) => (
  <span className={`badge ${variantClass[variant]} ${className}`}>
    {dot && (
      <svg width="6" height="6" viewBox="0 0 6 6" aria-hidden="true">
        <circle cx="3" cy="3" r="3" fill="currentColor" />
      </svg>
    )}
    {children}
  </span>
);
