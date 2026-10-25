#!/usr/bin/env bash
set -euo pipefail

print_logs() {
  status=$?
  if ((status != 0)); then
    echo "PoC execution failed. Docker Compose logs:" >&2
    docker compose logs --no-color >&2 || true
  fi
  exit "$status"
}
trap print_logs EXIT

command -v docker >/dev/null 2>&1 || {
  echo "Docker is required." >&2
  exit 1
}
command -v java >/dev/null 2>&1 || {
  echo "JDK 17 or newer is required." >&2
  exit 1
}
java_version=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')
[[ -n "$java_version" && "$java_version" -ge 17 ]] || {
  echo "JDK 17 or newer is required." >&2
  exit 1
}
docker info >/dev/null
docker compose version >/dev/null

echo "[1/4] Building and starting MySQL, RabbitMQ, and Ledger API..."
docker compose up --build -d --wait

echo "[2/4] Running unit and real-infrastructure integration tests..."
./mvnw -B -ntp verify

echo "[3/4] Verifying the running application..."
container_id=$(docker compose ps -q ledger-api | head -n 1)
[[ -n "$container_id" ]] || {
  echo "Ledger API container is not running." >&2
  exit 1
}
health=$(docker inspect --format '{{.State.Health.Status}}' "$container_id")
[[ "$health" == "healthy" ]] || {
  echo "Ledger API is not healthy: $health" >&2
  exit 1
}

port_mapping=$(docker compose port ledger-api 8080 | head -n 1)
api_port=${port_mapping##*:}
echo "[4/4] All checks passed."
echo
echo "Ledger API:            http://localhost:${api_port}"
echo "Health:                http://localhost:${api_port}/actuator/health/readiness"
echo "RabbitMQ Management:   http://localhost:15672"
echo "Sample payment:        ./scripts/send-payment-settled.sh PAYMENT_ID MERCHANT_UUID 125.50 EUR"
echo "Stop stack:            docker compose down"
echo "Stop and delete data:  docker compose down -v"
trap - EXIT
