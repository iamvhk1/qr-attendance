import React from 'react';
import './Card.css';

export interface CardProps {
  children: React.ReactNode;
  className?: string;
  style?: React.CSSProperties;
  /** Apply glassmorphism styling */
  glass?: boolean;
  /** Emit a subtle glow on hover */
  hoverable?: boolean;
  /** Optional click handler — adds pointer cursor */
  onClick?: () => void;
  padding?: 'none' | 'sm' | 'md' | 'lg';
  /** Show a top-border accent bar */
  accent?: boolean;
}

export const Card: React.FC<CardProps> = ({
  children,
  className = '',
  style,
  glass = false,
  hoverable = false,
  onClick,
  padding = 'md',
  accent = false,
}) => {
  const classes = [
    'card',
    glass ? 'card-glass' : '',
    hoverable ? 'card-hoverable' : '',
    onClick ? 'card-clickable' : '',
    `card-pad-${padding}`,
    accent ? 'card-accent' : '',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div
      className={classes}
      style={style}
      onClick={onClick}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      onKeyDown={
        onClick
          ? (e) => {
              if (e.key === 'Enter' || e.key === ' ') onClick();
            }
          : undefined
      }
    >
      {accent && <div className="card-accent-bar" aria-hidden="true" />}
      {children}
    </div>
  );
};

export interface CardHeaderProps {
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  action?: React.ReactNode;
  className?: string;
}

export const CardHeader: React.FC<CardHeaderProps> = ({ title, subtitle, action, className = '' }) => (
  <div className={`card-header ${className}`}>
    <div className="card-header-text">
      {typeof title === 'string' ? <h3 className="card-title">{title}</h3> : title}
      {subtitle && <p className="card-subtitle">{subtitle}</p>}
    </div>
    {action && <div className="card-header-action">{action}</div>}
  </div>
);

export const CardBody: React.FC<{ children: React.ReactNode; className?: string }> = ({
  children,
  className = '',
}) => <div className={`card-body ${className}`}>{children}</div>;

export const CardFooter: React.FC<{ children: React.ReactNode; className?: string }> = ({
  children,
  className = '',
}) => <div className={`card-footer ${className}`}>{children}</div>;
