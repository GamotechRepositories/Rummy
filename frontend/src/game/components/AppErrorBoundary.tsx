import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/** Prevents a render crash from leaving a pure black screen. */
export class AppErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('[AppErrorBoundary]', error, info.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <div
          className="app-frame"
          style={{
            display: 'grid',
            placeItems: 'center',
            padding: 24,
            color: '#f8fafc',
            textAlign: 'center',
          }}
        >
          <div style={{ maxWidth: 420 }}>
            <div style={{ fontWeight: 800, fontSize: 18, marginBottom: 8 }}>Something went wrong</div>
            <div style={{ fontSize: 13, color: '#94a3b8', marginBottom: 16, wordBreak: 'break-word' }}>
              {this.state.error.message || 'UI crashed while loading the table.'}
            </div>
            <button
              type="button"
              onClick={() => {
                try {
                  localStorage.removeItem('rummy_active_table');
                  sessionStorage.removeItem('rummy_active_table');
                } catch {
                  // ignore
                }
                window.location.href = '/';
              }}
              style={{
                padding: '10px 18px',
                borderRadius: 12,
                border: '1px solid rgba(251,191,36,0.5)',
                background: 'linear-gradient(135deg,#f59e0b,#d97706)',
                color: '#111',
                fontWeight: 800,
                cursor: 'pointer',
              }}
            >
              Back to lobby
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
