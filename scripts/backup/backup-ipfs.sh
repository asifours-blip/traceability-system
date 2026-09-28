#!/usr/bin/env bash
# 备份 kubo 仓库：物理备份（整个 repo 目录打包），比逐个 CID 导出更简单可靠，
# 代价是必须先停止守护进程（一致性要求），恢复出来的是与备份时刻完全一致的仓库（含 pin 列表、配置、block 存储）。
# 要备份哪个 kubo：TRACE_IPFS_HOME_WIN 环境变量（与 scripts/ipfs/env.sh 同名同默认值 D:\trace-ipfs）
set -euo pipefail
source "$(dirname "$0")/../ipfs/env.sh"  # 复用 to_unix / api_up / kubo() 等，读 TRACE_IPFS_HOME_WIN
source "$(dirname "$0")/env.sh"          # 后 source：覆盖 log/die 的前缀，加上 BACKUP_DIR

repo_dir="$TRACE_IPFS_HOME/repo"
[[ -d "$repo_dir" ]] || die "找不到 kubo repo：$repo_dir"

if api_up; then
  die "kubo 守护进程还在跑（API $API_MADDR），先 bash scripts/ipfs/stop.sh 再备份，否则备份内容可能不一致"
fi

mkdir -p "$BACKUP_DIR/ipfs"
stamp="$(date +%Y%m%d-%H%M%S)"
out="$BACKUP_DIR/ipfs/kubo-repo-${stamp}.tar.gz"

# 守护进程已停：直接对本地 repo 只读操作（不经 --api），不会跟即将做的 tar 打包冲突
pins_before="$(kubo pin ls --type=recursive -q 2>/dev/null | wc -l || echo '?')"
tar -C "$TRACE_IPFS_HOME" -czf "$out" repo
size=$(du -h "$out" | cut -f1)
log "已备份 kubo repo（$repo_dir，递归 pin 数 $pins_before）→ $out（$size）"
