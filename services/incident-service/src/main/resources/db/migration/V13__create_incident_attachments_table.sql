CREATE TABLE incidents.incident_attachments (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incidents.incidents(id) ON DELETE CASCADE,
    object_key VARCHAR(500) NOT NULL UNIQUE,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 VARCHAR(64) NOT NULL,
    uploaded_by VARCHAR(255) NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_incident_attachments_incident_uploaded
    ON incidents.incident_attachments (incident_id, uploaded_at);
