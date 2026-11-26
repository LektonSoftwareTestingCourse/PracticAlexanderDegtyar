# Переменные
DOCKER_COMPOSE = docker compose
MVN = mvn -f services/pom.xml
NPM_DIR = services/dashboard
SERVICE ?= gateway

ifeq ($(OS),Windows_NT)
    COPY_CMD = @if not exist .env cmd /c "copy /Y .env.example .env >nul"
    SMOKE_CMD = pwsh -ExecutionPolicy Bypass -File scripts/smoke-test.ps1
else
    COPY_CMD = test -f .env || cp .env.example .env
    SMOKE_CMD = ./scripts/smoke-test.sh
endif

# Специальные цели
.PHONY: run stop clean build status logs smoke smoke-win test lint
.PHONY: mvn-all mvn-clean mvn-test mvn-lint mvn-service
.PHONY: npm-install npm-lint npm-test npm-build

help:
	@echo "Available commands:"
	@echo ""
	@echo "Docker:"
	@echo "  make run           - Up all containers"
	@echo "  make stop          - Stop all containers"
	@echo "  make clean         - Remove containers, images and volumes"
	@echo "  make build         - Rebuild all images"
	@echo "  make status        - Containers status"
	@echo "  make logs          - Show logs (SERVICE=gateway)"
	@echo "  make smoke         - Run smoke tests"
	@echo ""
	@echo "General"
	@echo "  make test          - Run all tests (Maven + NPM)"
	@echo "  make lint          - Run all linters (Maven + NPM)"
	@echo ""
	@echo "Maven (all modules):"
	@echo "  make mvn-all       - Build all modules"
	@echo "  make mvn-clean     - Clean all modules"
	@echo "  make mvn-test      - Run all tests"
	@echo "  make mvn-lint      - Run checkstyle"
	@echo ""
	@echo "Maven (single service, SERVICE=gateway):"
	@echo "  make mvn-service   - Build one service + deps"
	@echo ""
	@echo "Frontend (dashboard):"
	@echo "  make npm-install   - Install npm dependencies"
	@echo "  make npm-lint      - Run ESLint"
	@echo "  make npm-test      - Run tests"
	@echo "  make npm-build     - Build production bundle"


# Docker
.env:
	$(COPY_CMD)

run: .env
	$(DOCKER_COMPOSE) up -d

stop:
	$(DOCKER_COMPOSE) down

clean:
	$(DOCKER_COMPOSE) down -v --remove-orphans

build:
	$(DOCKER_COMPOSE) build

status:
	$(DOCKER_COMPOSE) ps

logs:
	$(DOCKER_COMPOSE) logs -f $(SERVICE)

smoke:
	$(SMOKE_CMD)

# Общее
test: mvn-test npm-test

lint: mvn-lint npm-lint

# Maven
mvn-all:
	$(MVN) package

mvn-clean:
	$(MVN) clean

mvn-test:
	$(MVN) test

mvn-lint:
	$(MVN) checkstyle:check

mvn-service:
	$(MVN) -P$(SERVICE) package

# Frontend
npm-install:
	cd $(NPM_DIR) && npm install

npm-lint:
	cd $(NPM_DIR) && npm run lint

npm-test:
	cd $(NPM_DIR) && npm test -- --run

npm-build:
	cd $(NPM_DIR) && npm run build

API_GATEWAY_PORT ?= 18080
API_COMPOSE = GATEWAY_PORT=$(API_GATEWAY_PORT) docker compose --env-file .env.example -f docker-compose.yaml -f services/api-tests/compose.override.yaml -p practic-api-test
API_MAVEN = mvn -B -ntp -f services/pom.xml -Papi-tests -Dapi.gateway=http://localhost:$(API_GATEWAY_PORT)

.PHONY: api-test api-test-down
api-test:
	$(API_COMPOSE) up -d --build --wait --wait-timeout 240 gateway terminal-simulator merchant-acquirer bin-lookup
	$(API_MAVEN) test
	@set -e; \
	  trap '$(API_COMPOSE) up -d --no-deps --wait --wait-timeout 90 rabbitmq' EXIT; \
	  $(API_COMPOSE) stop rabbitmq; \
	  $(API_MAVEN) -Dtest=BrokerRollbackIT test
	@set -e; \
	  trap '$(API_COMPOSE) up -d --no-deps --wait --wait-timeout 90 switch' EXIT; \
	  $(API_COMPOSE) stop switch; \
	  $(API_MAVEN) -Dtest=SwitchUnavailableIT test

api-test-down:
	$(API_COMPOSE) down
