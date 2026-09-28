#!/usr/bin/env bash
# 查看本地 kubo 节点状态：是否运行、监听地址、pin 数量、repo 占用。
set -uo pipefail
source "$(dirname "$0")/env.sh"

if ! api_up; then
  log "未在运行（API $API_MADDR）"
  exit 1
fi
log "运行中：$(kubo_api version)"
log "API 监听：$(kubo_api config Addresses.API)；网关：$(kubo_api config Addresses.Gateway)；swarm：$(kubo_api config Addresses.Swarm)"
log "已连接节点数：$(kubo_api swarm peers 2>/dev/null | wc -l)"
log "递归 pin 数：$(kubo_api pin ls --type=recursive -q 2>/dev/null | wc -l)"
kubo_api repo stat --human 2>/dev/null | sed 's/^/[ipfs]   /'
