#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose-test.yml"
TARGET_DIR="${ROOT_DIR}/target/integration-test"
APP_LOG="${TARGET_DIR}/easyexcel-demo.log"
APP_PORT="${INTEGRATION_APP_PORT:-18088}"
BASE_URL="http://127.0.0.1:${APP_PORT}"
MAVEN_BIN="${MAVEN_BIN:-/Users/dingli/Dependent/apache-maven-3.6.3/bin/mvn}"
JAVA_HOME="${JAVA_HOME:-/Users/dingli/Dependent/JDK/jdk8u482-b08/Contents/Home}"
JAVA_BIN="${JAVA_BIN:-${JAVA_HOME}/bin/java}"
APP_PID=""

mkdir -p "${TARGET_DIR}"

cleanup() {
  if [[ -n "${APP_PID}" ]] && kill -0 "${APP_PID}" >/dev/null 2>&1; then
    kill "${APP_PID}" >/dev/null 2>&1 || true
    wait "${APP_PID}" >/dev/null 2>&1 || true
  fi
  if [[ "${KEEP_INTEGRATION_DEPS:-0}" != "1" ]]; then
    docker compose -f "${COMPOSE_FILE}" down -v >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_http() {
  local url="$1"
  local seconds="$2"
  local end=$((SECONDS + seconds))
  while (( SECONDS < end )); do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_mysql() {
  local seconds="$1"
  local end=$((SECONDS + seconds))
  while (( SECONDS < end )); do
    if docker exec easyexcel-mysql-it mysqladmin ping -h 127.0.0.1 -uroot -peasyexcel_test_root --silent >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_redis() {
  local seconds="$1"
  local end=$((SECONDS + seconds))
  while (( SECONDS < end )); do
    if docker exec easyexcel-redis-it redis-cli ping >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

echo "==> Starting integration middleware"
docker compose -f "${COMPOSE_FILE}" up -d mysql-it redis-it minio-it

echo "==> Waiting for integration middleware"
if ! wait_mysql 120; then
  echo "MySQL test container did not become ready"
  docker logs easyexcel-mysql-it --tail 120 || true
  exit 1
fi
if ! wait_redis 60; then
  echo "Redis test container did not become ready"
  docker logs easyexcel-redis-it --tail 120 || true
  exit 1
fi
if ! wait_http "http://127.0.0.1:${INTEGRATION_MINIO_API_PORT:-29000}/minio/health/live" 60; then
  echo "MinIO test container did not become ready"
  docker logs easyexcel-minio-it --tail 120 || true
  exit 1
fi

echo "==> Packaging application"
JAVA_HOME="${JAVA_HOME}" "${MAVEN_BIN}" -q -DskipTests package

echo "==> Starting application on ${BASE_URL}"
export SERVER_PORT="${APP_PORT}"
export MYSQL_URL="127.0.0.1:${INTEGRATION_MYSQL_PORT:-23306}"
export MYSQL_USERNAME="root"
export MYSQL_PASSWORD="easyexcel_test_root"
export HIKARI_MAXIMUM_POOL_SIZE="${HIKARI_MAXIMUM_POOL_SIZE:-10}"
export FLYWAY_ENABLED="${FLYWAY_ENABLED:-true}"
export REDIS_HOST="127.0.0.1"
export REDIS_PORT="${INTEGRATION_REDIS_PORT:-26379}"
export REDIS_DATABASE="${REDIS_DATABASE:-1}"
export REDIS_PASSWORD="${REDIS_PASSWORD:-}"
export MINIO_ENDPOINT="http://127.0.0.1:${INTEGRATION_MINIO_API_PORT:-29000}"
export MINIO_PUBLIC_ENDPOINT="${MINIO_ENDPOINT}"
export MINIO_ACCESS_KEY="easyexcel_test_access"
export MINIO_SECRET_KEY="easyexcel_test_secret"
export MINIO_BUCKET_NAME="${MINIO_BUCKET_NAME:-student-excel-it}"
export API_SECURITY_DEMO_MODE="${API_SECURITY_DEMO_MODE:-false}"
export API_SECURITY_DEMO_USER_TOKEN="${API_SECURITY_DEMO_USER_TOKEN:-it-user-token}"
export API_SECURITY_DEMO_ADMIN_TOKEN="${API_SECURITY_DEMO_ADMIN_TOKEN:-it-admin-token}"
export TASK_WORKER_ID="${TASK_WORKER_ID:-it-local-worker}"
export TASK_RECOVERY_INITIAL_DELAY_MILLIS="${TASK_RECOVERY_INITIAL_DELAY_MILLIS:-5000}"
export TASK_RECOVERY_FIXED_DELAY_MILLIS="${TASK_RECOVERY_FIXED_DELAY_MILLIS:-10000}"
export IMPORT_MAX_ROWS_PER_TASK="${IMPORT_MAX_ROWS_PER_TASK:-200000}"
export IMPORT_AUTO_RECOVERY_ENABLED="${IMPORT_AUTO_RECOVERY_ENABLED:-false}"
export FILE_CENTER_SECURITY_SCAN_ENABLED="${FILE_CENTER_SECURITY_SCAN_ENABLED:-true}"

nohup "${JAVA_BIN}" -jar "${ROOT_DIR}/target/demo-0.0.1-SNAPSHOT.jar" >"${APP_LOG}" 2>&1 &
APP_PID="$!"

if ! wait_http "${BASE_URL}/actuator/health" 120; then
  echo "Application did not become healthy. Recent log:"
  tail -n 120 "${APP_LOG}" || true
  exit 1
fi

echo "==> Running flat HTTP integration tests"
BASE_URL="${BASE_URL}" \
API_SECURITY_DEMO_USER_TOKEN="${API_SECURITY_DEMO_USER_TOKEN}" \
API_SECURITY_DEMO_ADMIN_TOKEN="${API_SECURITY_DEMO_ADMIN_TOKEN}" \
python3 "${ROOT_DIR}/scripts/run_flat_tests.py"

echo "==> Integration tests finished"
