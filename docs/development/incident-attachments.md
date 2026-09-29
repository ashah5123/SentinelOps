# Incident evidence attachments

Phase 17 adds durable binary evidence to the incident investigation workflow. Responders and
administrators can upload a file from the incident detail page; every authenticated role can list
and download attachments. The REST API applies the same OIDC role boundary as the rest of the
incident API.

Objects are stored privately in the `incident-artifacts` MinIO bucket locally (S3 in the AWS
deployment). PostgreSQL stores only metadata: the incident and object identifiers, original safe
file name, media type, byte size, uploader, timestamp, and a SHA-256 digest. Object keys are
server-generated and never derived from an untrusted file name. Downloads are always mediated by
the API and include `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff`.

Uploads default to disabled for a host-run service and are enabled by Docker Compose. Configure
`ATTACHMENTS_ENABLED`, `OBJECT_STORAGE_ENDPOINT`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`,
`ATTACHMENTS_BUCKET`, and `ATTACHMENTS_MAX_BYTES`.

The API exposes list, multipart upload, and mediated-content-download endpoints below
`/api/v1/incidents/{incidentId}/attachments`. Successful uploads append an
`ATTACHMENT_UPLOADED` immutable audit event. If metadata persistence fails after object upload,
the service attempts compensating object deletion before surfacing the failure.
