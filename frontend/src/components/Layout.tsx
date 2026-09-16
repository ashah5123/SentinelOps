import type { ReactNode } from "react";
import { NavLink } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { hasRole } from "../lib/roles";

export function Layout({ children }: { children: ReactNode }) {
  const auth = useAuth();

  return (
    <div className="app-shell">
      <a href="#main-content" className="skip-link">
        Skip to main content
      </a>
      <header className="app-header">
        <span className="app-title">SentinelOps Operator Console</span>
        <nav aria-label="Primary">
          <NavLink to="/" end>
            Dashboard
          </NavLink>
          <NavLink to="/incidents">Incidents</NavLink>
          <NavLink to="/proposals">Agent Proposals</NavLink>
          {hasRole(auth.roles, "ADMIN") && <NavLink to="/admin/recovery">Recovery</NavLink>}
          {hasRole(auth.roles, "ADMIN") && <NavLink to="/admin/connectors">Connectors</NavLink>}
        </nav>
        <div className="app-user">
          {auth.username && (
            <span>
              {auth.username} ({auth.roles.join(", ") || "no role"})
            </span>
          )}
          <button type="button" onClick={auth.signOut}>
            Sign out
          </button>
        </div>
      </header>
      <main id="main-content" tabIndex={-1}>
        {children}
      </main>
    </div>
  );
}
