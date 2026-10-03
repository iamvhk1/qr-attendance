import React from 'react';
import { AlertTriangle, RefreshCw, Home } from 'lucide-react';

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
}

interface ErrorBoundaryProps {
  children: React.ReactNode;
  /** Optional custom fallback element (overrides the default UI) */
  fallback?: React.ReactNode;
}

/**
 * Global React ErrorBoundary — catches unhandled rendering errors in the
 * component tree and displays a polished fallback UI rather than a blank screen.
 *
 * Usage: wrap the top-level router in App.tsx with <ErrorBoundary>.
 */
class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    // Log to console in development — in production this would go to an error tracker
    console.error('[ErrorBoundary] Uncaught error:', error, info.componentStack);
  }

  handleReload = () => {
    window.location.reload();
  };

  handleGoHome = () => {
    // Hard-navigate to root — can't use react-router here since we're outside it
    window.location.href = '/';
  };

  handleReset = () => {
    this.setState({ hasError: false, error: null });
  };

  render() {
    if (this.state.hasError) {
      // Honour a custom fallback if provided
      if (this.props.fallback) {
        return <>{this.props.fallback}</>;
      }

      return (
        <div className="error-boundary-root">
          <div className="error-boundary-card">
            <AlertTriangle
              size={56}
              strokeWidth={1.5}
              className="error-boundary-icon"
              aria-hidden="true"
            />

            <h1 className="error-boundary-title">Something went wrong</h1>

            <p className="error-boundary-msg">
              An unexpected error occurred in the application. Your attendance
              data is safe — please reload or return home to continue.
            </p>

            {this.state.error?.message && (
              <pre className="error-boundary-detail" role="alert" aria-label="Error details">
                {this.state.error.message}
              </pre>
            )}

            <div className="error-boundary-actions">
              <button
                className="scan-submit-btn"
                onClick={this.handleReload}
                style={{ minWidth: '140px' }}
                id="error-reload-btn"
              >
                <RefreshCw size={16} aria-hidden="true" />
                Reload Page
              </button>
              <button
                className="scan-retry-btn"
                onClick={this.handleGoHome}
                style={{ minWidth: '120px' }}
                id="error-home-btn"
              >
                <Home size={16} aria-hidden="true" />
                Go Home
              </button>
            </div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}

export default ErrorBoundary;
