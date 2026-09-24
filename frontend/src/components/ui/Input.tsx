import React from 'react';
import './Input.css';

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  hint?: string;
  error?: string;
  leftAddon?: React.ReactNode;
  rightAddon?: React.ReactNode;
  /** Stretch to fill container */
  fullWidth?: boolean;
}

/**
 * Input — labelled, validated, animated text field.
 */
export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  (
    {
      label,
      hint,
      error,
      leftAddon,
      rightAddon,
      fullWidth = true,
      id,
      className = '',
      ...rest
    },
    ref
  ) => {
    const inputId = id ?? `input-${Math.random().toString(36).slice(2, 7)}`;
    const hasError = Boolean(error);

    return (
      <div className={`input-field ${fullWidth ? 'input-full' : ''} ${className}`}>
        {label && (
          <label htmlFor={inputId} className="input-label">
            {label}
          </label>
        )}

        <div className={`input-wrapper ${hasError ? 'input-error' : ''} ${rest.disabled ? 'input-disabled' : ''}`}>
          {leftAddon && (
            <span className="input-addon input-addon-left" aria-hidden="true">
              {leftAddon}
            </span>
          )}

          <input
            ref={ref}
            id={inputId}
            className={`input-control ${leftAddon ? 'has-left-addon' : ''} ${rightAddon ? 'has-right-addon' : ''}`}
            aria-invalid={hasError}
            aria-describedby={
              hasError ? `${inputId}-error` : hint ? `${inputId}-hint` : undefined
            }
            {...rest}
          />

          {rightAddon && (
            <span className="input-addon input-addon-right" aria-hidden="true">
              {rightAddon}
            </span>
          )}

          {/* Animated bottom-border focus indicator */}
          <span className="input-focus-line" aria-hidden="true" />
        </div>

        {hasError ? (
          <p id={`${inputId}-error`} className="input-message input-message-error" role="alert">
            {error}
          </p>
        ) : hint ? (
          <p id={`${inputId}-hint`} className="input-message input-message-hint">
            {hint}
          </p>
        ) : null}
      </div>
    );
  }
);

Input.displayName = 'Input';
