# Chaos engineering (Phase 15)

## Scope note: no real Kubernetes cluster exists in this repository

This repository has no Kubernetes manifests, no `kind`/`minikube` configuration, and no cluster
of any kind — the entire local environment is Docker Compose (see
`docs/development/local-platform.md`). Every chaos experiment therefore targets a Compose
container as the local stand-in for a Kubernetes pod/node, the same substitution principle
Phase 14 used for its "no real Kubernetes cluster" remediation adapters (see
`docs/development/remediation.md`'s "local implementations" section). If this platform is ever
deployed to a real cluster, the experiment *concepts* (pod termination, resource pressure,
network faults, etc.) carry over directly to tools like Chaos Mesh or Litmus — only the
execution mechanism (`docker compose kill/pause/restart` vs. a Kubernetes API call) would change.

## Catalog

See `infrastructure/docker/scripts/chaos/chaos-experiments.yaml` for the full catalog: 11
experiments, each with an explicit target, duration, expected behavior, safety limits, abort
condition, and recovery validation, covering:

- Pod termination / repeated restarts
- CPU/memory pressure
- Network latency, packet loss, and isolation
- Kafka/Redpanda broker interruption
- PostgreSQL connection exhaustion and unavailability
- Redis failure
- Object-storage (MinIO) interruption
- Slow/unavailable/malformed LLM responses
- Duplicate/delayed/reordered/malformed telemetry events
- Notification-provider failures
- Remediation execution failure and rollback

## Running an experiment

```bash
# One-time opt-in, per shell/CI job — never on by default:
echo "CHAOS_EXPERIMENTS_ENABLED=true" >> .env

infrastructure/docker/scripts/chaos-experiment.sh list
infrastructure/docker/scripts/chaos-experiment.sh <experiment-id> [--dry-run]
```

`--dry-run` logs every command it would run without executing anything — useful for reviewing a
new experiment before it touches a running container, and the only mode available without
Docker (see "Verification" below).

## Safety mechanisms (enforced in code, not just documented)

1. **Container labeling.** Every target container in `docker-compose.yml` carries
   `sentinelops.chaos-target: "true"`. `chaos-experiment.sh` refuses to act on any container
   missing that label — this is the literal mechanism behind "restrict experiments to explicitly
   labeled development resources."
2. **Explicit opt-in.** `CHAOS_EXPERIMENTS_ENABLED=true` must be set independently of the label —
   a second gate, so a mistyped command against a normally-running dev stack still requires
   deliberate action to actually do anything.
3. **Wall-clock bounds.** Every experiment has a hard duration ceiling (10s–300s depending on the
   experiment) checked in code, not just documented in the catalog.
4. **Unconditional recovery.** Every experiment registers a `trap ... EXIT INT TERM` recovery
   step before doing anything disruptive (the same pattern established by Phase 6's
   `reliability-fault-test.sh`) — a failure, an abort, or Ctrl-C still restores normal operation.
5. **Recovery validation.** Every experiment polls a health endpoint (or an equivalent check)
   after restoring, and aborts loudly rather than silently if recovery does not happen within a
   documented timeout.

## Verification performed for this phase

This sandbox has no Docker available (a pre-existing, unchanging environment limitation
documented in every prior phase's own verification section). Every chaos experiment was
therefore verified with `--dry-run` against the full catalog (all 11 experiment ids), confirming:
correct command construction, correct label-check invocation, correct trap registration, and
correct control flow — see the phase completion report for the exact commands and their output.
`infrastructure/docker/scripts/chaos-experiment.sh` was **not** exercised against a live
container in this environment. CI (`.github/workflows/ci.yml`'s `chaos-smoke` job) runs three of
the fastest, safest experiments (`kafka-broker-fault`, `redis-fault`, `pod-termination`) against
the real Compose stack on every push, which is the actual live-execution proof this phase
provides.
