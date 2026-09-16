import { useAuth } from "../auth/AuthProvider";

export function SignedOut() {
  const auth = useAuth();
  return (
    <div className="signed-out" role="main">
      <h1>{auth.sessionExpired ? "Session expired" : "Signed out"}</h1>
      <p>
        {auth.sessionExpired
          ? "Your session has expired. Sign in again to continue."
          : "You have been signed out of SentinelOps."}
      </p>
      <button
        type="button"
        onClick={() => {
          auth.dismissSessionExpired();
          auth.signIn(window.location.pathname === "/signed-out" ? "/" : window.location.pathname);
        }}
      >
        Sign in
      </button>
    </div>
  );
}
