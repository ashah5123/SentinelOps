import { getConnectorHealth } from "../api/alerts";
import { useApiClient } from "../api/ApiClientProvider";
import { useAsyncData } from "../hooks/useAsyncData";
import { AsyncBoundary } from "../components/AsyncBoundary";

/**
 * Administrator-only connector health view (Phase 12, section 13). Never displays secrets, HMAC
 * values, authorization headers, or raw payloads — see {@code ConnectorHealthController} on the
 * backend, which only ever computes/returns the bounded summary fields shown here.
 */
export function AdminConnectorHealth() {
  const client = useApiClient();
  const state = useAsyncData((signal) => getConnectorHealth(client, signal), [client]);

  return (
    <div className="admin-connector-health">
      <h1>Connector Health</h1>
      <AsyncBoundary state={state} onRetry={state.refetch} loadingLabel="Loading connector health…">
        {(connectors) => (
          <table>
            <thead>
              <tr>
                <th>Connector</th>
                <th>Enabled</th>
                <th>Last successful ingestion</th>
                <th>Recent failures</th>
                <th>Last successful notification</th>
                <th>Dead-lettered</th>
                <th>Configuration</th>
              </tr>
            </thead>
            <tbody>
              {connectors.map((connector) => (
                <tr key={connector.name}>
                  <td>{connector.name}</td>
                  <td>{connector.enabled ? "Yes" : "No"}</td>
                  <td>
                    {connector.lastSuccessfulIngestion
                      ? new Date(connector.lastSuccessfulIngestion).toLocaleString()
                      : "Never"}
                  </td>
                  <td>{connector.recentFailureCount}</td>
                  <td>
                    {connector.lastSuccessfulNotification
                      ? new Date(connector.lastSuccessfulNotification).toLocaleString()
                      : "Never"}
                  </td>
                  <td>{connector.deadLetterCount}</td>
                  <td>{connector.configurationValid ? "Valid" : "Invalid"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </AsyncBoundary>
    </div>
  );
}
