#!/usr/bin/env bash
set -euo pipefail

# 根目录快捷入口：后台启动 Java 后端
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/java-backend/scripts/dev-run-bg.sh"
