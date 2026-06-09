#!/usr/bin/env bash
set -euo pipefail

# 根目录快捷入口：转发到 java-backend 启动脚本
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/java-backend/scripts/dev-run.sh"
