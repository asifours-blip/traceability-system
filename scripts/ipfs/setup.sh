#!/usr/bin/env bash
# 初始化本地 kubo 节点：复制 ipfs.exe 到自建目录并校验 sha256，建独立 repo，改成离线、只监听回环的配置。
# 可重复执行：已复制 / 已初始化的会跳过，配置每次都重写一遍。
set -euo pipefail
source "$(dirname "$0")/env.sh"

mkdir -p "$TRACE_IPFS_HOME/bin" "$TRACE_IPFS_HOME/logs"
if [[ ! -f "$KUBO_BIN" ]]; then
  [[ -f "$KUBO_SRC" ]] || die "找不到 kubo 来源：$KUBO_SRC（可用 KUBO_SRC_WIN 指定）"
  cp "$KUBO_SRC" "$KUBO_BIN"
fi
echo "$KUBO_SHA256  $KUBO_BIN" | sha256sum -c --quiet - || die "ipfs.exe sha256 与固定值不符"
log "$(kubo version) · sha256 已核对"

if api_up; then
  die "守护进程正在运行，先执行 stop.sh 再改配置"
fi

if [[ ! -f "$TRACE_IPFS_HOME/repo/config" ]]; then
  # --empty-repo：不写入默认的帮助文档对象
  kubo init --empty-repo >/dev/null
  log "已初始化 repo：$REPO_WIN"
fi

# 只监听回环上的 API；网关、swarm 都不监听；不连引导节点、不做 mDNS、不做内容路由
kubo config Addresses.API "$API_MADDR"
kubo config --json Addresses.Gateway '[]'
kubo config --json Addresses.Swarm '[]'
kubo config --json Addresses.Announce '[]'
kubo config --json Bootstrap '[]'
kubo config --json Discovery.MDNS.Enabled false
kubo config Routing.Type none
kubo config --json Swarm.DisableNatPortMap true
log "配置完成：API $API_MADDR，网关/swarm 关闭，离线运行（start.sh 以 --offline 启动）"
