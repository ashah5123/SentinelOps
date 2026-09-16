import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SignedOut } from "./SignedOut";

const mockUseAuth = vi.fn();
vi.mock("../auth/AuthProvider", () => ({ useAuth: () => mockUseAuth() }));

describe("SignedOut", () => {
  it("shows a distinct 'session expired' message when the session expired (vs. a plain sign-out)", () => {
    mockUseAuth.mockReturnValue({
      sessionExpired: true,
      signIn: vi.fn(),
      dismissSessionExpired: vi.fn(),
    });
    render(<SignedOut />);
    expect(screen.getByRole("heading", { name: "Session expired" })).toBeInTheDocument();
  });

  it("shows a plain signed-out message otherwise", () => {
    mockUseAuth.mockReturnValue({
      sessionExpired: false,
      signIn: vi.fn(),
      dismissSessionExpired: vi.fn(),
    });
    render(<SignedOut />);
    expect(screen.getByRole("heading", { name: "Signed out" })).toBeInTheDocument();
  });

  it("clears the session-expired flag and re-initiates sign-in when the user clicks Sign in", async () => {
    const dismissSessionExpired = vi.fn();
    const signIn = vi.fn();
    mockUseAuth.mockReturnValue({ sessionExpired: true, signIn, dismissSessionExpired });
    render(<SignedOut />);

    await userEvent.setup().click(screen.getByRole("button", { name: "Sign in" }));

    expect(dismissSessionExpired).toHaveBeenCalledOnce();
    expect(signIn).toHaveBeenCalledOnce();
  });
});
