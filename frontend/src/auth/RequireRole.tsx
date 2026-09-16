import type { ReactNode } from "react";
import { useAuth } from "./AuthProvider";
import { hasAnyRole, type Role } from "../lib/roles";

/**
 * A usability-only route guard: shows a clear "forbidden" message instead of a broken page when
 * the user's role obviously cannot use a route. This is never the security boundary — every
 * protected backend endpoint independently enforces the same rule (see
 * docs/development/security.md) and returns 403 regardless of what this component renders.
 */
export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const auth = useAuth();
  if (!hasAnyRole(auth.roles, roles)) {
    return (
      <div role="alert" className="forbidden-notice">
        <h1>Access restricted</h1>
        <p>
          Your role ({auth.roles.join(", ") || "none"}) does not include access to this page. It
          requires one of: {roles.join(", ")}.
        </p>
      </div>
    );
  }
  return <>{children}</>;
}
