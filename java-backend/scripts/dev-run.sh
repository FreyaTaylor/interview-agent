#!/usr/bin/env bash
set -euo pipefail

# 一键本地启动 Java 后端：停掉旧 8080 监听进程，再用标准 Maven 命令启动。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_BACKEND_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ROOT_DIR="$(cd "${JAVA_BACKEND_DIR}/.." && pwd)"

if [[ -f "${ROOT_DIR}/.env" ]]; then
  echo "[dev-run] load env from ${ROOT_DIR}/.env"
  set -a
  source "${ROOT_DIR}/.env"
  set +a
fi

pids="$(lsof -t -iTCP:8080 -sTCP:LISTEN || true)"
if [[ -n "${pids}" ]]; then
  pid_list="$(echo "${pids}" | tr '\n' ' ' | xargs)"
  echo "[dev-run] stop existing process on :8080 (pid=${pid_list})"
  for pid in ${pids}; do
    kill -15 "${pid}"
  done
fi

echo "[dev-run] start java backend on :8080"
cd "${JAVA_BACKEND_DIR}"
exec mvn spring-boot:run
