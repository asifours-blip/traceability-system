#!/usr/bin/env bash
# 真实环境的文件存储端到端：本地隔离链 + 真实 kubo（Windows 上的 ipfs.exe，离线、独立 repo）+ 完整后端。
# 在 WSL 里执行：bash scripts/local-chain/files-e2e.sh [--fresh]
# 前置：setup.sh 已执行过；scripts/ipfs/setup.sh 已执行过。
# --fresh：先重建隔离链（setup.sh --clean）并跑一遍冒烟（跳过共识停滞场景，冒烟记录只写到 $E2E_HOME/smoke-out，不覆盖 docs/artifacts 里的核验记录），
#          RealChainBusinessFlowTest 依赖新链（outsider 账户尚无角色）；不加时沿用上次冒烟部署的合约。
# 过程：启动链 → 启动 kubo → 启动 WSL→Windows 的 stdio 桥 → 跑 RealChainBusinessFlowTest + RealChainFilesE2ETest
#       → 记录写到 docs/artifacts/local-chain-files-e2e-<日期时间>.txt → 停桥、停 kubo、停链。
set -euo pipefail
dir="$(cd "$(dirname "$0")" && pwd)"
source "$dir/env.sh"
ipfs_dir="$REPO_ROOT/scripts/ipfs"

cleanup() {
  bash "$ipfs_dir/wsl-bridge.sh" stop || true
  bash "$ipfs_dir/stop.sh" || true
  bash "$dir/stop.sh" || true
}
trap cleanup EXIT

if [[ "${1:-}" == "--fresh" ]]; then
  bash "$dir/setup.sh" --clean
  bash "$dir/start.sh"
  mkdir -p "$E2E_HOME/smoke-out"
  bash "$dir/smoke.sh" --skip-stall --out-dir "$E2E_HOME/smoke-out"
fi
bash "$dir/start.sh"
bash "$ipfs_dir/start.sh"
bash "$ipfs_dir/status.sh"
bash "$ipfs_dir/wsl-bridge.sh" start

source "$ipfs_dir/env.sh"
export E2E_IPFS_API_URL="http://127.0.0.1:${IPFS_API_PORT}"
# 测试中途重启 kubo 用；桥按连接转发，kubo 重启后无需重启桥
export E2E_IPFS_RESTART="bash '$ipfs_dir/stop.sh' && bash '$ipfs_dir/start.sh'"
export E2E_TESTS="RealChainBusinessFlowTest RealChainFilesE2ETest"
export E2E_OUT_PREFIX="local-chain-files-e2e"
bash "$dir/java-real-chain-test.sh"
