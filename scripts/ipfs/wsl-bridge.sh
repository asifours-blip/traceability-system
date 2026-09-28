#!/usr/bin/env bash
# 在 WSL 里启停 wsl-bridge.py：让 WSL 内的后端测试经 127.0.0.1:$IPFS_API_PORT 访问 Windows 上的 kubo API。
# 用法：bash scripts/ipfs/wsl-bridge.sh start|stop|status
# WIN_PYTHON：Windows 版 python.exe 的 WSL 路径；缺省时用 `where.exe python` 找第一个非 WindowsApps 的。
set -euo pipefail
source "$(dirname "$0")/env.sh"
[[ "$IPFS_ENV" == "wsl" ]] || die "只在 WSL 里使用；Windows 上直接连 127.0.0.1:$IPFS_API_PORT"

state_dir="${E2E_HOME:-$HOME/trace-e2e}"
pid_file="$state_dir/ipfs-bridge.pid"
log_file="$state_dir/ipfs-bridge.log"
mkdir -p "$state_dir"

running() { [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null; }

case "${1:-}" in
  start)
    running && { log "桥已在运行（pid $(cat "$pid_file")）"; exit 0; }
    if [[ -z "${WIN_PYTHON:-}" ]]; then
      win_path="$(cd /mnt/c && cmd.exe /c where python 2>/dev/null | tr -d '\r' | grep -vi WindowsApps | head -1)"
      [[ -n "$win_path" ]] || die "找不到 Windows 版 python.exe，请用 WIN_PYTHON 指定"
      WIN_PYTHON="$(wslpath -u "$win_path")"
    fi
    nohup python3 "$(dirname "$0")/wsl-bridge.py" "$IPFS_API_PORT" "$WIN_PYTHON" >"$log_file" 2>&1 &
    echo $! >"$pid_file"
    for _ in $(seq 1 20); do
      if curl -s --max-time 10 -X POST "http://127.0.0.1:$IPFS_API_PORT/api/v0/version" | grep -q Version; then
        log "桥就绪：WSL 127.0.0.1:$IPFS_API_PORT -> Windows kubo（pid $(cat "$pid_file")）"
        exit 0
      fi
      sleep 1
    done
    die "桥启动后 20 秒内连不上 kubo（kubo 是否已用 start.sh 启动？见 $log_file）"
    ;;
  stop)
    if running; then
      kill "$(cat "$pid_file")" && rm -f "$pid_file"
      log "桥已停止"
    else
      rm -f "$pid_file"
      log "桥未在运行"
    fi
    ;;
  status)
    running && log "桥运行中（pid $(cat "$pid_file")）" || { log "桥未在运行"; exit 1; }
    ;;
  *)
    die "用法：wsl-bridge.sh start|stop|status"
    ;;
esac
