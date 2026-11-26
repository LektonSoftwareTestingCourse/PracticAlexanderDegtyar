#!/bin/sh
set -eu

cd -- "$(dirname -- "$0")/.."

GATEWAY_PORT=${API_GATEWAY_PORT:-18080}
export GATEWAY_PORT

compose() {
    docker compose --env-file .env.example \
        -f docker-compose.yaml -f services/api-tests/compose.override.yaml \
        -p practic-api-test "$@"
}

run_tests() {
    mvn -B -ntp -f services/pom.xml -Papi-tests \
        "-Dapi.gateway=http://localhost:$GATEWAY_PORT" "$@" test
}

test_service_outage() (
    service=$1
    test_class=$2
    trap 'compose up -d --no-deps --wait --wait-timeout 90 "$service"' EXIT
    compose stop "$service"
    run_tests "-Dtest=$test_class"
)

compose up -d --build --wait --wait-timeout 240 \
    gateway terminal-simulator merchant-acquirer bin-lookup
run_tests
test_service_outage rabbitmq BrokerRollbackIT
test_service_outage switch SwitchUnavailableIT
