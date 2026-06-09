#!/usr/bin/env bash
set -euo pipefail

# 后台启动 Java 后端，避免前台终端会话结束导致进程退出。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_BACKEND_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ROOT_DIR="$(cd "${JAVA_BACKEND_DIR}/.." && pwd)"
LOG_DIR="${JAVA_BACKEND_DIR}/logs"
LOG_FILE="${LOG_DIR}/java-backend.log"
PID_FILE="${JAVA_BACKEND_DIR}/.java-backend.pid"

if [[ -f "${ROOT_DIR}/.env" ]]; then
  echo "[dev-run-bg] load env from ${ROOT_DIR}/.env"
  set -a
  source "${ROOT_DIR}/.env"
  set +a
fi

mkdir -p "${LOG_DIR}"

pids="$(lsof -t -iTCP:8080 -sTCP:LISTEN || true)"
if [[ -n "${pids}" ]]; then
  pid_list="$(echo "${pids}" | tr '\n' ' ' | xargs)"
  echo "[dev-run-bg] stop existing process on :8080 (pid=${pid_list})"
  for pid in ${pids}; do
    kill -15 "${pid}"
  done
fi

echo "[dev-run-bg] start java backend in background on :8080"
cd "${JAVA_BACKEND_DIR}"
nohup mvn spring-boot:run > "${LOG_FILE}" 2>&1 &
new_pid=$!
echo "${new_pid}" > "${PID_FILE}"

echo "[dev-run-bg] pid=${new_pid}"
echo "[dev-run-bg] log=${LOG_FILE}"
