#!/usr/bin/env bash
# 一键：重建隔离链 -> 启动 -> 冒烟（含共识停滞场景）-> 后端代码直连真实链测试。
# 在 WSL Ubuntu 中执行：bash scripts/local-chain/run-all.sh
# USE_OFFICIAL=1：不读本机原链目录，先用 fetch-official.sh 从官方源取得并校验 FISCO BCOS / WeBASE-Front（CI 用这种方式）。
# 结束后链保持运行，用 stop.sh 停止。
set -euo pipefail
dir="$(dirname "$0")"
if [[ "${USE_OFFICIAL:-0}" == "1" ]]; then
  source "$dir/env.sh"
  bash "$dir/fetch-official.sh"
  export FISCO_SRC="${OFFICIAL_DIR:-$E2E_HOME/official}"
fi
bash "$dir/setup.sh" --clean
bash "$dir/start.sh"
bash "$dir/smoke.sh" "$@"
bash "$dir/java-real-chain-test.sh"
