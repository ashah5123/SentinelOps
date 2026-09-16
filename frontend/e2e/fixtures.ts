import { type Page, expect } from "@playwright/test";

export type DemoRole = "viewer-demo" | "responder-demo" | "admin-demo";

/**
 * Logs in via the real Keycloak login form (Authorization Code + PKCE redirect) using one of the
 * synthetic demo users from infrastructure/docker/keycloak/realm-export.json. Never bypasses
 * authentication — this drives the actual redirect/login/callback flow a real operator would use.
 */
export async function loginAs(page: Page, username: DemoRole) {
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL(/realms\/sentinelops\/protocol\/openid-connect\/auth/);
  await page.getByLabel("Username or email").fill(username);
  await page.getByLabel("Password").fill(`${username}-local-only`);
  await page.getByRole("button", { name: "Sign In" }).click();
  await page.waitForURL(/^http:\/\/localhost:5173\/?$/);
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
}

export async function waitForNoPendingRequest(page: Page) {
  await page.waitForLoadState("networkidle");
}
