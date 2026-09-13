-- Records the outcome of client requests made with an Idempotency-Key header
-- so a safe repeat returns the original response instead of re-executing it.
CREATE TABLE incidents.idempotent_requests (
    idempotency_key  VARCHAR(128) PRIMARY KEY,
    request_hash     VARCHAR(128) NOT NULL,
    response_status  INT NOT NULL,
    response_body    JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL
);

COMMENT ON COLUMN incidents.idempotent_requests.request_hash IS
    'Hash of the normalized request payload. A reused idempotency key with a '
    'different hash is rejected as a conflicting request.';
