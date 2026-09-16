import type { ProblemDetail } from "./types";

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail | null;

  constructor(status: number, problem: ProblemDetail | null, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }
}

/** Thrown by request() when the caller's AbortSignal fired — callers should ignore, not display, this. */
export class RequestCancelledError extends Error {
  constructor() {
    super("Request was cancelled");
    this.name = "RequestCancelledError";
  }
}

export interface ApiClientDeps {
  baseUrl: string;
  getAccessToken: () => string | undefined;
  onUnauthorized: () => void;
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  idempotencyKey?: string;
}

/**
 * A thin fetch wrapper: attaches the current bearer token (never persisted by this module —
 * see src/auth/AuthProvider.tsx for where the token actually lives), maps a 401 to
 * onUnauthorized() (never expiry-guessing on the client — the backend is authoritative), and
 * maps every non-2xx response to a typed ApiError carrying the RFC 9457 problem detail body so
 * callers can render errorCode-specific messages without parsing responses themselves.
 */
export function createApiClient({ baseUrl, getAccessToken, onUnauthorized }: ApiClientDeps) {
  async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const token = getAccessToken();
    const headers: Record<string, string> = {
      Accept: "application/json",
      ...(options.body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.idempotencyKey ? { "Idempotency-Key": options.idempotencyKey } : {}),
      ...options.headers,
    };

    let response: Response;
    try {
      response = await fetch(`${baseUrl}${path}`, {
        method: options.method ?? "GET",
        headers,
        body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
        signal: options.signal,
      });
    } catch {
      if (options.signal?.aborted) {
        throw new RequestCancelledError();
      }
      throw new ApiError(0, null, "Network error — the backend may be unreachable.");
    }

    if (response.status === 401) {
      onUnauthorized();
      throw new ApiError(401, null, "Your session has expired. Please sign in again.");
    }

    if (!response.ok) {
      let problem: ProblemDetail | null = null;
      try {
        problem = (await response.json()) as ProblemDetail;
      } catch {
        // body wasn't JSON (or was empty) — fall through with problem = null
      }
      throw new ApiError(
        response.status,
        problem,
        problem?.detail ?? `Request failed with status ${response.status}`,
      );
    }

    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }

  return {
    get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { method: "GET", signal }),
    post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
      request<T>(path, { ...options, method: "POST", body }),
    put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
      request<T>(path, { ...options, method: "PUT", body }),
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
