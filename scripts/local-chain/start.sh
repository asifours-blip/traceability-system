#!/usr/bin/env bash
# 启动本地隔离链的节点和 WeBASE-Front 副本，并等待两者可用。
set -euo pipefail
source "$(dirname "$0")/env.sh"

[[ -d "$NODES_DIR/127.0.0.1" && -d "$FRONT_DIR/apps" ]] || die "请先运行 setup.sh"

if ! pgrep -f "$NODES_DIR/127.0.0.1/node0/" >/dev/null; then
  bash "$NODES_DIR/127.0.0.1/start_all.sh"
fi

rpc() { curl -s --max-time 3 -X POST --data "{\"jsonrpc\":\"2.0\",\"method\":\"$1\",\"params\":[${GROUP_ID}],\"id\":1}" "http://127.0.0.1:${RPC_PORT}"; }
for _ in $(seq 1 30); do rpc getBlockNumber | grep -q result && break; sleep 1; done
log "节点 RPC：$(rpc getBlockNumber)"

(cd "$FRONT_DIR" && JAVA_HOME="$JAVA_HOME_E2E" bash start.sh >/dev/null) || true
for i in $(seq 1 90); do
  if curl -sf --max-time 3 "$FRONT_URL/${GROUP_ID}/web3/blockNumber" >/dev/null; then
    log "WeBASE-Front 就绪（${i}s）：$FRONT_URL，块高 $(curl -s "$FRONT_URL/${GROUP_ID}/web3/blockNumber")"
    exit 0
  fi
  sleep 1
done
tail -n 60 "$FRONT_DIR/log/front.out" "$FRONT_DIR"/log/WeBASE-Front.log 2>/dev/null || true
die "WeBASE-Front 90 秒内未就绪"
