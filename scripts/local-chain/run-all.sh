#!/usr/bin/env bash
# 一键：重建隔离链 -> 启动 -> 冒烟（含共识停滞场景）-> 后端代码直连真实链测试。
# 在 WSL Ubuntu 中执行：bash scripts/local-chain/run-all.sh
# 结束后链保持运行，用 stop.sh 停止。
set -euo pipefail
dir="$(dirname "$0")"
bash "$dir/setup.sh" --clean
bash "$dir/start.sh"
bash "$dir/smoke.sh" "$@"
bash "$dir/java-real-chain-test.sh"
