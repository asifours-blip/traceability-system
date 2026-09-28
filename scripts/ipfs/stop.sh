#!/usr/bin/env bash
# 停止本地 kubo 守护进程：只通过本节点 API 的 shutdown 命令，不按进程名杀进程，不影响其他 IPFS 实例。
set -uo pipefail
source "$(dirname "$0")/env.sh"

if ! api_up; then
  log "未在运行"
  exit 0
fi
kubo_api shutdown >/dev/null 2>&1
for _ in $(seq 1 20); do
  api_up || { log "已停止"; exit 0; }
  sleep 1
done
die "20 秒内未停止"
