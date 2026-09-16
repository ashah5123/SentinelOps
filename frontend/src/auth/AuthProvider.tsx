import { type ReactNode, createContext, useContext, useEffect, useMemo, useState } from "react";
import { AuthProvider as OidcAuthProvider, useAuth as useOidcAuth } from "react-oidc-context";
import { WebStorageStateStore } from "oidc-client-ts";
import { type Role, rolesFromClaims } from "../lib/roles";

const AUTHORITY = import.meta.env.VITE_KEYCLOAK_AUTHORITY as string;
const CLIENT_ID = import.meta.env.VITE_KEYCLOAK_CLIENT_ID as string;

/**
 * Authentication (Phase 10): Authorization Code Flow with PKCE via oidc-client-ts/
 * react-oidc-context (a maintained library — no hand-rolled token exchange or password handling
 * here). Tokens are held in sessionStorage (never localStorage) via an explicit WebStorageStateStore,
 * and only for the lifetime of the tab; nothing in this app ever reads or writes a token directly.
 */
const oidcConfig = {
  authority: AUTHORITY,
  client_id: CLIENT_ID,
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: window.location.origin,
  response_type: "code",
  scope: "openid profile",
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  onSigninCallback: () => {
    // Strip the OIDC response (code/state) from the URL bar after a successful callback so a
    // refresh doesn't attempt to re-process a spent authorization code.
    window.history.replaceState(
      {},
      document.title,
      window.location.pathname === "/callback" ? "/" : window.location.pathname,
    );
  },
};

interface SentinelOpsAuthContextValue {
  isAuthenticated: boolean;
  isLoading: boolean;
  sessionExpired: boolean;
  username: string | null;
  roles: Role[];
  accessToken: string | undefined;
  signIn: (returnTo?: string) => void;
  signOut: () => void;
  dismissSessionExpired: () => void;
}

const SentinelOpsAuthContext = createContext<SentinelOpsAuthContextValue | null>(null);

function InnerAuthProvider({ children }: { children: ReactNode }) {
  const auth = useOidcAuth();
  const [sessionExpired, setSessionExpired] = useState(false);

  useEffect(() => {
    // Clear all client state (React Query cache callers should also react to this) when the
    // session genuinely expires — never silently keep showing stale authenticated UI.
    return auth.events.addAccessTokenExpired(() => {
      setSessionExpired(true);
    });
  }, [auth.events]);

  useEffect(() => {
    return auth.events.addSilentRenewError(() => {
      setSessionExpired(true);
    });
  }, [auth.events]);

  const value = useMemo<SentinelOpsAuthContextValue>(() => {
    const claims = auth.user?.profile as Record<string, unknown> | undefined;
    return {
      isAuthenticated: auth.isAuthenticated && !sessionExpired,
      isLoading: auth.isLoading,
      sessionExpired,
      username: (claims?.["preferred_username"] as string | undefined) ?? null,
      roles: rolesFromClaims(claims),
      accessToken: auth.user?.access_token,
      signIn: (returnTo) => {
        void auth.signinRedirect({ state: { returnTo: returnTo ?? window.location.pathname } });
      },
      signOut: () => {
        setSessionExpired(false);
        void auth.removeUser();
      },
      dismissSessionExpired: () => setSessionExpired(false),
    };
  }, [auth, sessionExpired]);

  return (
    <SentinelOpsAuthContext.Provider value={value}>{children}</SentinelOpsAuthContext.Provider>
  );
}

export function SentinelOpsAuthProvider({ children }: { children: ReactNode }) {
  return (
    <OidcAuthProvider {...oidcConfig}>
      <InnerAuthProvider>{children}</InnerAuthProvider>
    </OidcAuthProvider>
  );
}

export function useAuth(): SentinelOpsAuthContextValue {
  const ctx = useContext(SentinelOpsAuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within SentinelOpsAuthProvider");
  }
  return ctx;
}

/** The route the user was on before being sent to sign in, restored after a successful callback. */
export function useReturnToAfterSignin(): string {
  const auth = useOidcAuth();
  const state = auth.user?.state as { returnTo?: string } | undefined;
  return state?.returnTo ?? "/";
}
