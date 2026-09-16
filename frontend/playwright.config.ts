import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright config (Phase 10). Specs under e2e/ are written and statically valid but could not
 * be executed in the environment this phase was authored in — see docs/development/operations.md
 * and the frontend README's "known limitations" — Docker (Postgres/Keycloak/incident-service) is
 * unavailable there, and Playwright's browser binaries were deliberately not installed to avoid
 * consuming disk space for tests that cannot run against a live backend anyway.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: "list",
  timeout: 30_000,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:5173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run dev",
    url: "http://localhost:5173",
    reuseExistingServer: !process.env.CI,
  },
});
