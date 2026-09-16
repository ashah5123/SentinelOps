import { Route, Routes } from "react-router-dom";
import { SentinelOpsAuthProvider } from "./auth/AuthProvider";
import { RequireAuth } from "./auth/RequireAuth";
import { RequireRole } from "./auth/RequireRole";
import { ApiClientProvider } from "./api/ApiClientProvider";
import { AnnouncerProvider } from "./components/Announcer";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { Layout } from "./components/Layout";
import { Dashboard } from "./pages/Dashboard";
import { IncidentQueue } from "./pages/IncidentQueue";
import { IncidentDetail } from "./pages/IncidentDetail";
import { AdminRecovery } from "./pages/AdminRecovery";
import { Callback } from "./pages/Callback";
import { SignedOut } from "./pages/SignedOut";

export function App() {
  return (
    <ErrorBoundary>
      <SentinelOpsAuthProvider>
        <AnnouncerProvider>
          <Routes>
            <Route path="/callback" element={<Callback />} />
            <Route path="/signed-out" element={<SignedOut />} />
            <Route
              path="/*"
              element={
                <RequireAuth>
                  <ApiClientProvider>
                    <Layout>
                      <Routes>
                        <Route path="/" element={<Dashboard />} />
                        <Route path="/incidents" element={<IncidentQueue />} />
                        <Route path="/incidents/:id" element={<IncidentDetail />} />
                        <Route
                          path="/admin/recovery"
                          element={
                            <RequireRole roles={["ADMIN"]}>
                              <AdminRecovery />
                            </RequireRole>
                          }
                        />
                      </Routes>
                    </Layout>
                  </ApiClientProvider>
                </RequireAuth>
              }
            />
          </Routes>
        </AnnouncerProvider>
      </SentinelOpsAuthProvider>
    </ErrorBoundary>
  );
}
