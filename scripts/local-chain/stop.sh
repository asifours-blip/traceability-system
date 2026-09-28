#!/usr/bin/env bash
# 停止本地隔离链的 WeBASE-Front 副本与节点（只按 $E2E_HOME 下的路径匹配进程，不影响原链）。
set -uo pipefail
source "$(dirname "$0")/env.sh"

[[ -f "$FRONT_DIR/stop.sh" ]] && (cd "$FRONT_DIR" && bash stop.sh >/dev/null 2>&1)
[[ -f "$NODES_DIR/127.0.0.1/stop_all.sh" ]] && bash "$NODES_DIR/127.0.0.1/stop_all.sh"
log "已停止"
