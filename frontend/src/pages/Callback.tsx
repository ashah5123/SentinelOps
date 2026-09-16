import { Navigate } from "react-router-dom";
import { useAuth, useReturnToAfterSignin } from "../auth/AuthProvider";

/** Landing page for the OIDC redirect_uri; returns the user to the route they came from. */
export function Callback() {
  const auth = useAuth();
  const returnTo = useReturnToAfterSignin();

  if (auth.isLoading) {
    return (
      <div role="status" aria-live="polite">
        Completing sign-in…
      </div>
    );
  }
  return <Navigate to={returnTo} replace />;
}
