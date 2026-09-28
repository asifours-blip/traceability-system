#!/usr/bin/env bash
# 以离线模式启动本地 kubo 守护进程（Windows 进程，经 PowerShell Start-Process 脱离当前终端），等待 API 就绪。
set -euo pipefail
source "$(dirname "$0")/env.sh"

[[ -f "$TRACE_IPFS_HOME/repo/config" ]] || die "请先运行 setup.sh"
if api_up; then
  log "已在运行：API $API_MADDR"
  exit 0
fi

bin_win="$TRACE_IPFS_HOME_WIN\\bin\\ipfs.exe"
stamp="$(date +%Y%m%d-%H%M%S)"
# --offline：不连任何节点；--enable-gc 不开，垃圾回收只按 docs/files.md 的策略显式执行
powershell.exe -NoProfile -NonInteractive -Command \
  "Start-Process -WindowStyle Hidden -FilePath '$bin_win' \
   -ArgumentList '--repo-dir','$REPO_WIN','daemon','--offline' \
   -RedirectStandardOutput '$LOG_DIR_WIN\\daemon-$stamp.out' -RedirectStandardError '$LOG_DIR_WIN\\daemon-$stamp.err'" \
  || die "启动 ipfs.exe 失败"

for i in $(seq 1 30); do
  if api_up; then
    log "就绪（${i}s）：API $API_MADDR，$(kubo_api version)，日志 $LOG_DIR_WIN\\daemon-$stamp.*"
    exit 0
  fi
  sleep 1
done
die "30 秒内未就绪，见 $LOG_DIR_WIN\\daemon-$stamp.err"
