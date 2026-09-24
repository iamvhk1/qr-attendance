import React from 'react';
import './Button.css';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'success';
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  leftIcon?: React.ReactNode;
  rightIcon?: React.ReactNode;
  fullWidth?: boolean;
}

/**
 * Button — premium, animated button component.
 * Supports primary / secondary / ghost / danger / success variants.
 */
export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      variant = 'primary',
      size = 'md',
      loading = false,
      leftIcon,
      rightIcon,
      fullWidth = false,
      disabled,
      children,
      className = '',
      ...rest
    },
    ref
  ) => {
    const classes = [
      'btn',
      `btn-${variant}`,
      `btn-${size}`,
      fullWidth ? 'btn-full' : '',
      loading ? 'btn-loading' : '',
      className,
    ]
      .filter(Boolean)
      .join(' ');

    return (
      <button
        ref={ref}
        className={classes}
        disabled={disabled || loading}
        aria-busy={loading}
        {...rest}
      >
        {/* Shimmer overlay */}
        <span className="btn-shimmer" aria-hidden="true" />

        {loading ? (
          <span className="btn-spinner" aria-hidden="true" />
        ) : (
          leftIcon && <span className="btn-icon btn-icon-left">{leftIcon}</span>
        )}

        <span className="btn-label">{children}</span>

        {!loading && rightIcon && (
          <span className="btn-icon btn-icon-right">{rightIcon}</span>
        )}
      </button>
    );
  }
);

Button.displayName = 'Button';
