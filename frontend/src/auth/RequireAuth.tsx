import type { ReactNode } from "react";
import { useAuth } from "./AuthProvider";
import { SignedOut } from "../pages/SignedOut";

/**
 * Route guard — a usability feature only. It never substitutes for the backend's own
 * authorization (see docs/development/security.md); every action still calls a protected
 * endpoint that enforces the real rule server-side, regardless of what this component renders.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth();

  if (auth.isLoading) {
    return (
      <div role="status" aria-live="polite">
        Loading…
      </div>
    );
  }
  if (!auth.isAuthenticated) {
    return <SignedOut />;
  }
  return <>{children}</>;
}
