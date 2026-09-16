import { type ReactNode, createContext, useContext, useMemo } from "react";
import { useAuth } from "../auth/AuthProvider";
import { createApiClient, type ApiClient } from "./client";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL as string;

const ApiClientContext = createContext<ApiClient | null>(null);

export function ApiClientProvider({ children }: { children: ReactNode }) {
  const auth = useAuth();

  const client = useMemo(
    () =>
      createApiClient({
        baseUrl: API_BASE_URL,
        getAccessToken: () => auth.accessToken,
        onUnauthorized: () => {
          // A 401 from the API is the backend's authoritative word that the session is no
          // longer valid — mirror that into the same "session expired" state a token-expiry
          // event would set, so the UI reacts identically either way.
          auth.dismissSessionExpired(); // reset first in case a stale flag is set
          auth.signOut();
        },
      }),
    [auth],
  );

  return <ApiClientContext.Provider value={client}>{children}</ApiClientContext.Provider>;
}

export function useApiClient(): ApiClient {
  const ctx = useContext(ApiClientContext);
  if (!ctx) {
    throw new Error("useApiClient must be used within ApiClientProvider");
  }
  return ctx;
}
