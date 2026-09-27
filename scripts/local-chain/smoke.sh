#!/usr/bin/env bash
# 在已启动的本地隔离链上跑冒烟（部署 v2 Trace、完整三阶段、反例、契约核验、共识停滞）。
# 前置：setup.sh、start.sh 已执行；contracts/build/Trace.json 已由 `cd contracts && npm ci && npm run compile` 生成。
# 额外参数透传给 smoke.py，例如 --skip-stall。
set -euo pipefail
source "$(dirname "$0")/env.sh"

artifact="$REPO_ROOT/contracts/build/Trace.json"
[[ -f "$artifact" ]] || die "缺少 $artifact，请先在 contracts/ 下执行 npm ci && npm run compile"
curl -sf --max-time 3 "$FRONT_URL/${GROUP_ID}/web3/blockNumber" >/dev/null || die "WeBASE-Front 未就绪，请先运行 start.sh"

python3 "$(dirname "$0")/smoke.py" \
  --front-url "$FRONT_URL" \
  --front-version "$(tr -d '\r\n' <"$FRONT_DIR/release_note.txt")" \
  --group "$GROUP_ID" \
  --artifact "$artifact" \
  --out-dir "$REPO_ROOT/docs/artifacts" \
  --e2e-home "$E2E_HOME" \
  --nodes-dir "$NODES_DIR" \
  "$@"
