# Architecture diagrams

Every diagram here is Mermaid (source-controlled, renders directly on GitHub, updatable in the
same commit as the code it describes). See `docs/architecture/system-overview.md` for prose
architecture detail this page doesn't repeat.

## 1. System context

```mermaid
flowchart TB
    operator[Operator / Responder]
    aiClient[AI agent<br/>Claude Code, Codex, etc.]
    external[External alert sources<br/>Alertmanager, custom monitors]
    subgraph sentinelops[SentinelOps]
        console[Operator console]
        incidentSvc[incident-service]
        telemetrySvc[telemetry-correlation-service]
    end
    keycloak[Keycloak<br/>OIDC/RBAC]
    postgres[(PostgreSQL)]
    kafka[[Kafka-compatible streaming]]

    operator --> console --> incidentSvc
    aiClient -->|MCP| incidentSvc
    external -->|webhooks| incidentSvc
    incidentSvc <--> telemetrySvc
    incidentSvc --> postgres
    telemetrySvc --> postgres
    incidentSvc <--> kafka
    telemetrySvc <--> kafka
    incidentSvc <-->|token validation| keycloak
```

## 2. Service and data-flow architecture

```mermaid
flowchart LR
    subgraph incidentSvc[incident-service]
        api[REST API]
        cmd[IncidentCommandService]
        outbox[Transactional outbox]
    end
    subgraph telemetrySvc[telemetry-correlation-service]
        listeners[Kafka listeners]
        correlation[Correlation engine]
    end
    db[(PostgreSQL)]
    kafka[[Kafka-compatible broker]]

    api --> cmd --> db
    cmd --> outbox --> db
    outbox -->|publisher poll| kafka
    kafka --> listeners --> correlation --> db
    correlation -->|incident.evidence.correlated.v1| kafka
    kafka --> api
```

## 3. Alert-to-incident processing

```mermaid
sequenceDiagram
    participant Src as Alert source
    participant Web as Webhook controller
    participant Ing as AlertIngestionService
    participant Proc as AlertProcessingListener
    participant Corr as CorrelationEngine
    participant Route as RoutingEngine
    participant Notif as NotificationDispatcher

    Src->>Web: POST /alerts/webhooks/*
    Web->>Ing: validate + fingerprint
    Ing->>Ing: insert (dedup_key UNIQUE)
    Ing-->>Proc: alert.ingested.v1 (outbox)
    Proc->>Proc: semantic dedup (fingerprint lock)
    Proc->>Corr: correlate with open incidents
    Corr-->>Proc: matched or new incident
    Proc->>Route: evaluate routing rules
    Route->>Notif: enqueue notification(s)
```

## 4. AI-triage and RAG flow

```mermaid
flowchart LR
    incident[Incident] --> ctx[TriageContext<br/>redacted]
    ctx --> retrieve[RunbookRetriever<br/>pgvector similarity search]
    retrieve --> passages[Retrieved passages]
    ctx --> provider[AiProvider<br/>deterministic / Ollama]
    passages --> provider
    provider --> validate[StructuredOutputValidator]
    validate -->|valid| suggestion[AiSuggestion<br/>with citations]
    validate -->|invalid/unavailable| fallback[Graceful fallback<br/>no suggestion, incident unaffected]
    provider -.->|chaos: slow/unavailable/malformed| chaos[ChaosInjectingAiProvider]
```

## 5. MCP proposal and approval flow

```mermaid
sequenceDiagram
    participant Agent as AI agent (MCP client)
    participant MCP as MCP server
    participant Prop as AgentProposalService
    participant Human as Human approver
    participant Appr as AgentApprovalService
    participant Cmd as IncidentCommandService

    Agent->>MCP: propose_action tool call
    MCP->>Prop: validate + rate-limit + risk-classify
    Prop-->>MCP: proposal (PENDING)
    Human->>Appr: approve (console or REST)
    Appr->>Appr: reject self-approval, check expiry,<br/>re-verify content hash, check incident version
    Appr->>Cmd: execute via real command path
    Cmd-->>Appr: result
    Appr-->>Human: EXECUTED / EXECUTION_FAILED
```

## 6. Remediation state machine

```mermaid
stateDiagram-v2
    [*] --> PROPOSED
    PROPOSED --> APPROVED
    PROPOSED --> DENIED
    PROPOSED --> CANCELLED
    APPROVED --> SCHEDULED
    APPROVED --> CANCELLED
    SCHEDULED --> RUNNING
    SCHEDULED --> CANCELLED
    RUNNING --> SUCCEEDED
    RUNNING --> FAILED
    RUNNING --> CANCELLED
    SUCCEEDED --> ROLLED_BACK
    FAILED --> ROLLED_BACK
    DENIED --> [*]
    CANCELLED --> [*]
    ROLLED_BACK --> [*]
```

## 7. Observability pipeline

```mermaid
flowchart LR
    app1[incident-service] -->|metrics| prom[Prometheus]
    app2[telemetry-correlation-service] -->|metrics| prom
    app1 -->|traces, OTLP| otel[OTel Collector] --> tempo[Tempo]
    app2 -->|traces, OTLP| otel
    app1 -->|logs| loki[Loki]
    app2 -->|logs| loki
    prom --> alertmgr[Alertmanager]
    prom --> grafana[Grafana]
    tempo --> grafana
    loki --> grafana
    alertmgr -->|routes| notif[Notification channels]
```

## 8. Deployment topology (production)

```mermaid
flowchart TB
    subgraph aws[AWS]
        subgraph vpc[VPC]
            subgraph public[Public subnets]
                nat[NAT gateways]
                alb[Load balancer / ingress]
            end
            subgraph private[Private subnets]
                subgraph eks[EKS cluster]
                    pods1[incident-service pods<br/>3-12 replicas, HPA]
                    pods2[telemetry-correlation-service pods<br/>2-8 replicas, HPA]
                end
                rds[(RDS PostgreSQL<br/>Multi-AZ)]
                redis[(ElastiCache Redis)]
                msk[[MSK]]
            end
        end
        s3[(S3: runbooks, artifacts, postmortems)]
        ecr[ECR]
        secrets[Secrets Manager]
    end
    argocd[Argo CD] -->|GitOps sync| eks
    alb --> pods1
    pods1 --> rds
    pods1 --> redis
    pods1 --> msk
    pods2 --> msk
    pods1 --> s3
    pods1 -.->|IRSA| secrets
```
