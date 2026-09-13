.PHONY: help doctor validate

help: ## Show available targets
	@echo "SentinelOps — available targets:"
	@echo "  make doctor    Check local prerequisites (read-only, installs nothing)"
	@echo "  make validate  Run formatting/safety checks against the repository"

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
