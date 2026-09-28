#!/usr/bin/env bash
# 恢复 kubo 仓库：把 backup-ipfs.sh 的 tar 包解到一个全新目录（不覆盖任何已有 repo）。
# 恢复出来只是文件，不会自动启动守护进程——调用方决定拿哪个 TRACE_IPFS_HOME_WIN 去跑 scripts/ipfs/start.sh。
# 用法：restore-ipfs.sh <kubo-repo-*.tar.gz> <目标目录（Windows 路径）=D:\trace-ipfs-restore>
set -euo pipefail
source "$(dirname "$0")/env.sh"

tarball="${1:?用法：restore-ipfs.sh <kubo-repo-*.tar.gz> [目标目录]}"
[[ -f "$tarball" ]] || die "找不到 $tarball"
target_win="${2:-D:\\trace-ipfs-restore}"

# 复用 scripts/ipfs/env.sh 的 to_unix（不整体 source，避免它的默认 TRACE_IPFS_HOME_WIN 干扰）
if grep -qi microsoft /proc/version 2>/dev/null; then
  to_unix() { wslpath -u "$1"; }
else
  to_unix() { cygpath -u "$1"; }
fi
target_unix="$(to_unix "$target_win")"

[[ -e "$target_unix" ]] && die "$target_unix 已存在，恢复演练要求全新目录；换个路径或先手动删除确认不需要的旧目录"

mkdir -p "$target_unix"
tar -C "$target_unix" -xzf "$tarball"
[[ -f "$target_unix/repo/config" ]] || die "解出来的内容里没有 repo/config，tar 包可能不对"

log "已恢复到 $target_unix/repo"
log "接下来（换一个不冲突的端口，setup.sh 会把 bin/ipfs.exe 复制进来并把 config 里的监听地址改到新端口，repo 内容不受影响）："
log "  TRACE_IPFS_HOME_WIN='$target_win' IPFS_API_PORT=<换个端口> bash scripts/ipfs/setup.sh"
log "  TRACE_IPFS_HOME_WIN='$target_win' IPFS_API_PORT=<同上端口> bash scripts/ipfs/start.sh"
