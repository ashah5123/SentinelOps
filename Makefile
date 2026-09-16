.PHONY: help doctor validate infra-config infra-pull infra-up infra-down infra-status infra-logs infra-smoke infra-clean \
	incident-build incident-test incident-image incident-up incident-down incident-logs \
	observability-config observability-up observability-down observability-status observability-logs observability-smoke \
	correlation-build correlation-test correlation-image correlation-up correlation-down correlation-status correlation-logs correlation-smoke \
	reliability-test \
	benchmark-env-info benchmark-seed benchmark-cleanup benchmark-smoke benchmark-unauthorized benchmark-reads benchmark-create benchmark-lifecycle benchmark-mixed benchmark-burst

ENV_FILE := .env
COMPOSE_FILE := infrastructure/docker/docker-compose.yml
COMPOSE := docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE)
CORE_SERVICES := postgres redis redpanda minio
INCIDENT_SERVICE_DIR := services/incident-service
TELEMETRY_CORRELATION_SERVICE_DIR := services/telemetry-correlation-service

help: ## Show available targets
	@echo "SentinelOps — available targets:"
	@echo "  make doctor          Check local prerequisites (read-only, installs nothing)"
	@echo "  make validate        Run formatting/safety checks against the repository"
	@echo "  make infra-config    Validate the local platform Compose configuration"
	@echo "  make infra-pull      Pull pinned platform images"
	@echo "  make infra-up        Start the local platform and wait for healthy services"
	@echo "  make infra-down      Stop the local platform, keeping persistent volumes"
	@echo "  make infra-status    Show local platform service status/health"
	@echo "  make infra-logs      Show recent local platform logs"
	@echo "  make infra-smoke     Run the full local platform smoke test"
	@echo "  make infra-clean     Permanently delete local platform containers AND volumes (asks first)"
	@echo "  make incident-build  Build and test the incident-service JAR (mvnw verify)"
	@echo "  make incident-test   Run only the incident-service test suite"
	@echo "  make incident-image  Build the incident-service Docker image"
	@echo "  make incident-up     Start infra + the incident service (app profile)"
	@echo "  make incident-down   Stop the incident-service container (infra keeps running)"
	@echo "  make incident-logs   Tail incident-service logs"
	@echo "  make observability-config  Validate the observability Compose configuration"
	@echo "  make observability-up      Start the observability stack (observability profile)"
	@echo "  make observability-down    Stop the observability stack, keeping persistent volumes"
	@echo "  make observability-status  Show observability service status/health"
	@echo "  make observability-logs    Show recent observability stack logs"
	@echo "  make observability-smoke   Run the observability smoke test"
	@echo "  make correlation-build     Build and test the telemetry-correlation-service JAR (mvnw verify)"
	@echo "  make correlation-test      Run only the telemetry-correlation-service test suite"
	@echo "  make correlation-image     Build the telemetry-correlation-service Docker image"
	@echo "  make correlation-up        Start infra + the telemetry-correlation service (app profile)"
	@echo "  make correlation-down      Stop the telemetry-correlation-service container (infra keeps running)"
	@echo "  make correlation-logs      Tail telemetry-correlation-service logs"
	@echo "  make correlation-smoke     Run the Phase 5 correlation smoke test"
	@echo "  make reliability-test      Run the Phase 6 fault-injection workflow (SCENARIO=...)"
	@echo "  make benchmark-env-info    Print environment/config info for a benchmark run"
	@echo "  make benchmark-seed        Seed deterministic synthetic incidents (RUN_ID=, SIZE=, SEED=)"
	@echo "  make benchmark-cleanup     Delete a benchmark run's data (RUN_ID=... or ALL=1)"
	@echo "  make benchmark-smoke       Run the bounded functional smoke scenario"
	@echo "  make benchmark-unauthorized Run the intentional unauthorized-request scenario"
	@echo "  make benchmark-reads       Run the paginated-reads scenario (RUN_ID= required)"
	@echo "  make benchmark-create      Run the incident-creation scenario"
	@echo "  make benchmark-lifecycle   Run the lifecycle-transitions scenario"
	@echo "  make benchmark-mixed       Run the mixed read/write workload scenario"
	@echo "  make benchmark-burst       Run the short bounded burst + recovery scenario"

doctor: ## Check prerequisites without installing or modifying anything
	@echo "== SentinelOps environment check =="
	@printf "%-18s" "git:";        command -v git        >/dev/null 2>&1 && git --version                       || echo "MISSING"
	@printf "%-18s" "gh:";         command -v gh         >/dev/null 2>&1 && gh --version | head -1               || echo "MISSING"
	@printf "%-18s" "docker:";     command -v docker     >/dev/null 2>&1 && docker --version                     || echo "MISSING"
	@printf "%-18s" "compose:";    command -v docker     >/dev/null 2>&1 && docker compose version 2>/dev/null   || echo "MISSING"
	@printf "%-18s" "java:";       command -v java       >/dev/null 2>&1 && java -version 2>&1 | head -1         || echo "MISSING"
	@printf "%-18s" "maven:";      command -v mvn        >/dev/null 2>&1 && mvn -version 2>&1 | head -1          || echo "MISSING (optional if using gradle)"
	@printf "%-18s" "gradle:";     command -v gradle     >/dev/null 2>&1 && gradle --version 2>&1 | grep Gradle  || echo "MISSING (optional if using maven)"
	@printf "%-18s" "node:";       command -v node       >/dev/null 2>&1 && node --version                      || echo "MISSING"
	@printf "%-18s" "pnpm:";       command -v pnpm       >/dev/null 2>&1 && pnpm --version                       || echo "MISSING"
	@printf "%-18s" "python:";     command -v python3    >/dev/null 2>&1 && python3 --version                   || echo "MISSING"
	@printf "%-18s" "uv:";         command -v uv         >/dev/null 2>&1 && uv --version                         || echo "MISSING"
	@printf "%-18s" "kubectl:";    command -v kubectl    >/dev/null 2>&1 && kubectl version --client 2>&1 | head -1 || echo "MISSING"
	@printf "%-18s" "kind:";       command -v kind       >/dev/null 2>&1 && kind --version                       || echo "MISSING"
	@printf "%-18s" "helm:";       command -v helm       >/dev/null 2>&1 && helm version --short                 || echo "MISSING"
	@printf "%-18s" "terraform:";  command -v terraform  >/dev/null 2>&1 && terraform --version | head -1        || echo "MISSING"
	@printf "%-18s" "ollama:";     command -v ollama     >/dev/null 2>&1 && ollama --version 2>&1 | head -1      || echo "MISSING"
	@echo "== end of check =="

validate: ## Run formatting/safety checks that require no downloaded tooling
	@echo "== SentinelOps validation =="
	@echo "-- checking for whitespace/diff errors --"
	@git diff --check || (echo "git diff --check reported issues" && exit 1)
	@echo "-- confirming no tracked .env file --"
	@if git ls-files | grep -E '(^|/)\.env$$' >/dev/null 2>&1; then \
		echo "ERROR: a tracked .env file was found"; \
		git ls-files | grep -E '(^|/)\.env$$'; \
		exit 1; \
	else \
		echo "OK: no tracked .env file"; \
	fi
	@echo "-- scanning tracked filenames for obvious secret files --"
	@if git ls-files | grep -Ei '\.(pem|key|crt|p12|pfx|jks)$$|secret|credential' >/dev/null 2>&1; then \
		echo "ERROR: possible secret-like filenames found"; \
		git ls-files | grep -Ei '\.(pem|key|crt|p12|pfx|jks)$$|secret|credential'; \
		exit 1; \
	else \
		echo "OK: no obvious secret filenames tracked"; \
	fi
	@echo "-- checking for trailing whitespace in tracked text files --"
	@if git grep -lI ' $$' -- . ':(exclude)*.md' >/dev/null 2>&1; then \
		echo "WARNING: trailing whitespace found in:"; \
		git grep -lI ' $$' -- . ':(exclude)*.md'; \
	else \
		echo "OK: no trailing whitespace found"; \
	fi
	@echo "== validation complete =="

## ---- Phase 2: local platform infrastructure lifecycle ----
##
## All targets below operate only on the SentinelOps Compose project
## (named "sentinelops", network "sentinelops-net", volumes prefixed
## "sentinelops-"). They never touch unrelated Docker resources.

infra-config: ## Validate the Compose configuration
	@test -f $(ENV_FILE) || (echo "ERROR: $(ENV_FILE) not found. Run: cp .env.example .env" && exit 1)
	$(COMPOSE) config --quiet
	@echo "OK: compose configuration is valid"

infra-pull: infra-config ## Pull pinned platform images
	$(COMPOSE) pull

infra-up: infra-config ## Start the core local platform and wait for healthy services
	$(COMPOSE) up -d --wait $(CORE_SERVICES)
	$(COMPOSE) up --exit-code-from redpanda-topics-init redpanda-topics-init
	$(COMPOSE) up --exit-code-from minio-bucket-init minio-bucket-init
	@if [ -z "$${COMPOSE_PROFILES+x}" ] || [ -n "$${COMPOSE_PROFILES}" ]; then \
		echo "starting optional Redpanda Console..."; \
		$(COMPOSE) --profile console up -d --wait redpanda-console; \
	fi
	@echo "SentinelOps local platform is up."

infra-down: infra-config ## Stop the platform without deleting persistent volumes
	$(COMPOSE) --profile console down
	@echo "SentinelOps local platform stopped. Volumes were preserved."

infra-status: infra-config ## Show service status and health
	$(COMPOSE) --profile console ps

infra-logs: infra-config ## Show recent logs (review before sharing externally)
	$(COMPOSE) --profile console logs --tail=100

infra-smoke: ## Run the complete platform smoke test
	@bash infrastructure/docker/scripts/smoke-test.sh

infra-clean: infra-config ## DESTROYS local platform containers and volumes (interactive confirmation required)
	@echo "This will permanently delete the SentinelOps local platform containers"
	@echo "AND its persistent volumes (sentinelops-postgres-data, sentinelops-redis-data,"
	@echo "sentinelops-redpanda-data, sentinelops-minio-data). This cannot be undone."
	@printf "Type 'yes' to continue: "; \
	read -r confirm; \
	if [ "$$confirm" = "yes" ]; then \
		$(COMPOSE) --profile console down --volumes; \
		echo "SentinelOps local platform containers and volumes removed."; \
	else \
		echo "Aborted. Nothing was changed."; \
	fi

## ---- Phase 3: incident-service ----

incident-build: ## Build and test the incident-service JAR (format check, tests, coverage, package)
	cd $(INCIDENT_SERVICE_DIR) && ./mvnw -q verify

incident-test: ## Run only the incident-service test suite
	cd $(INCIDENT_SERVICE_DIR) && ./mvnw -q test

incident-image: infra-config ## Build the incident-service Docker image
	$(COMPOSE) --profile app build incident-service

incident-up: infra-up ## Start required infra, then the incident service (app profile)
	$(COMPOSE) --profile app up -d --wait incident-service
	@echo "Incident service is up on the port configured by INCIDENT_SERVICE_PORT."

incident-down: infra-config ## Stop only the incident-service container; infra keeps running
	$(COMPOSE) --profile app stop incident-service

incident-logs: infra-config ## Tail incident-service logs
	$(COMPOSE) --profile app logs -f incident-service

## ---- Phase 4: observability stack ----
##
## All targets below operate only on the "observability" Compose profile
## (OpenTelemetry Collector, Prometheus, Grafana, Loki, Tempo, Alertmanager).
## They never touch the Phase 2/3 core services or their volumes.

OBSERVABILITY_SERVICES := otel-collector prometheus loki tempo alertmanager grafana

observability-config: ## Validate the observability Compose configuration
	@test -f $(ENV_FILE) || (echo "ERROR: $(ENV_FILE) not found. Run: cp .env.example .env" && exit 1)
	$(COMPOSE) --profile observability config --quiet
	@echo "OK: observability compose configuration is valid"

observability-up: observability-config ## Start the observability stack and wait for healthy services
	$(COMPOSE) --profile observability up -d --wait $(OBSERVABILITY_SERVICES)
	@echo "SentinelOps observability stack is up."

observability-down: observability-config ## Stop the observability stack without deleting persistent volumes
	$(COMPOSE) --profile observability stop $(OBSERVABILITY_SERVICES)
	@echo "SentinelOps observability stack stopped. Volumes were preserved."

observability-status: observability-config ## Show observability service status/health
	$(COMPOSE) --profile observability ps $(OBSERVABILITY_SERVICES)

observability-logs: observability-config ## Show recent observability stack logs
	$(COMPOSE) --profile observability logs --tail=100 $(OBSERVABILITY_SERVICES)

observability-smoke: ## Run the observability smoke test
	@bash infrastructure/docker/scripts/observability-smoke-test.sh

## ---- Phase 5: telemetry-correlation-service ----

correlation-build: ## Build and test the telemetry-correlation-service JAR (format check, tests, coverage, package)
	cd $(TELEMETRY_CORRELATION_SERVICE_DIR) && ./mvnw -q verify

correlation-test: ## Run only the telemetry-correlation-service test suite
	cd $(TELEMETRY_CORRELATION_SERVICE_DIR) && ./mvnw -q test

correlation-image: infra-config ## Build the telemetry-correlation-service Docker image
	$(COMPOSE) --profile app build telemetry-correlation-service

correlation-up: infra-up ## Start required infra, then the telemetry-correlation service (app profile)
	$(COMPOSE) --profile app up -d --wait telemetry-correlation-service
	@echo "Telemetry correlation service is up on the port configured by TELEMETRY_CORRELATION_SERVICE_PORT."

correlation-down: infra-config ## Stop only the telemetry-correlation-service container; infra keeps running
	$(COMPOSE) --profile app stop telemetry-correlation-service

correlation-logs: infra-config ## Tail telemetry-correlation-service logs
	$(COMPOSE) --profile app logs -f telemetry-correlation-service

correlation-smoke: ## Run the Phase 5 correlation smoke test
	@bash infrastructure/docker/scripts/correlation-smoke-test.sh

## ---- Phase 6: reliability and failure recovery ----

reliability-test: ## Run the Phase 6 fault-injection workflow (SCENARIO=duplicate-delivery|broker-outage|app-restart|invalid-event|retry-exhaustion|all, default: all)
	@bash infrastructure/docker/scripts/reliability-fault-test.sh $${SCENARIO:-all}

## ---- Phase 8: performance testing and reproducible benchmarks ----
##
## Requires the "app" Compose profile already running (make incident-up). See
## docs/development/performance.md for the full guide. RUN_ID ties a seed run to the k6 runs
## that read it; if you don't set one, benchmark-seed prints the generated value to reuse.

benchmark-env-info: ## Print environment/config info that would be captured for a benchmark run
	@bash infrastructure/docker/scripts/benchmark-env-info.sh

benchmark-seed: ## Seed deterministic synthetic incidents (RUN_ID=, SIZE=200, SEED=42)
	@bash infrastructure/docker/scripts/benchmark-seed.sh "$${RUN_ID:-}" "$${SIZE:-200}" "$${SEED:-42}"

benchmark-cleanup: ## Delete a benchmark run's data (RUN_ID=... required, or ALL=1 for every run)
	@if [ -n "$${ALL:-}" ]; then \
		bash infrastructure/docker/scripts/benchmark-cleanup.sh --all-bench; \
	else \
		test -n "$${RUN_ID:-}" || (echo "ERROR: set RUN_ID=<id> or ALL=1" && exit 1); \
		bash infrastructure/docker/scripts/benchmark-cleanup.sh "$$RUN_ID"; \
	fi

benchmark-smoke: ## Run the bounded functional smoke scenario (also used in CI)
	@bash infrastructure/docker/scripts/benchmark-run.sh smoke

benchmark-unauthorized: ## Run the intentional unauthorized-request scenario (security checks, not perf)
	@bash infrastructure/docker/scripts/benchmark-run.sh unauthorized-access

benchmark-reads: ## Run the paginated-reads scenario against a seeded dataset (RUN_ID= required)
	@test -n "$${RUN_ID:-}" || (echo "ERROR: set RUN_ID=<id from benchmark-seed>" && exit 1)
	@BENCHMARK_RUN_ID="$$RUN_ID" bash infrastructure/docker/scripts/benchmark-run.sh paginated-reads

benchmark-create: ## Run the incident-creation scenario (downstream outbox/event processing)
	@bash infrastructure/docker/scripts/benchmark-run.sh incident-creation

benchmark-lifecycle: ## Run the lifecycle-transitions scenario
	@bash infrastructure/docker/scripts/benchmark-run.sh lifecycle-transitions

benchmark-mixed: ## Run the mixed read/write workload scenario (RUN_ID= optional, uses seeded data if set)
	@BENCHMARK_RUN_ID="$${RUN_ID:-}" bash infrastructure/docker/scripts/benchmark-run.sh mixed-workload

benchmark-burst: ## Run the short bounded burst + recovery scenario
	@bash infrastructure/docker/scripts/benchmark-run.sh burst-recovery
