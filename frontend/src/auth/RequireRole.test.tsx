import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { RequireRole } from "./RequireRole";

const mockUseAuth = vi.fn();
vi.mock("./AuthProvider", () => ({
  useAuth: () => mockUseAuth(),
}));

describe("RequireRole", () => {
  it("renders children when the user has one of the required roles", () => {
    mockUseAuth.mockReturnValue({ roles: ["ADMIN"] });
    render(
      <RequireRole roles={["ADMIN"]}>
        <div>Secret admin content</div>
      </RequireRole>,
    );
    expect(screen.getByText("Secret admin content")).toBeInTheDocument();
  });

  it("shows a clear forbidden message, not the protected content, when the role is missing", () => {
    mockUseAuth.mockReturnValue({ roles: ["VIEWER"] });
    render(
      <RequireRole roles={["ADMIN"]}>
        <div>Secret admin content</div>
      </RequireRole>,
    );
    expect(screen.queryByText("Secret admin content")).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent(/does not include access/i);
  });

  it("never claims this is the security boundary — it is a usability guard only", () => {
    // Documented, not enforced by this component: the real check is server-side (backend
    // @PreAuthorize). This test exists to keep that assumption visible in the suite.
    mockUseAuth.mockReturnValue({ roles: [] });
    render(
      <RequireRole roles={["RESPONDER", "ADMIN"]}>
        <div>Write action</div>
      </RequireRole>,
    );
    expect(screen.queryByText("Write action")).not.toBeInTheDocument();
  });
});
