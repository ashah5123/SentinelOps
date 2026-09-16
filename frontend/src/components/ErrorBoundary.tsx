import { Component, type ErrorInfo, type ReactNode } from "react";

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/** Catches unexpected rendering failures anywhere below it — the app never shows a blank screen. */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // eslint-disable-next-line no-console
    console.error("Unexpected rendering error", error, info.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <div role="alert" className="fatal-error">
          <h1>Something went wrong</h1>
          <p>An unexpected error occurred while rendering this page.</p>
          <button type="button" onClick={() => this.setState({ error: null })}>
            Try again
          </button>
          <button type="button" onClick={() => window.location.assign("/")}>
            Return to dashboard
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
