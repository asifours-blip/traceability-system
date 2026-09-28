# shellcheck shell=bash
# 备份/恢复脚本的公共配置。被 backup-*.sh / restore-*.sh / restore-drill.sh 引用，不单独执行。
# 在 Git Bash（Windows）下运行，需要 docker 在 PATH 里。所有值可用同名环境变量覆盖。

# 备份文件存放目录（不进 git，见 .gitignore）
BACKUP_DIR="${BACKUP_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/backups}"

# 仓库根目录
REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

# --- MySQL（生产环境要备份的：trace_it 库，含账号/会话/交易台账/文件对象/读模型）---
MYSQL_CONTAINER="${MYSQL_CONTAINER:-trace-mysql-test}"
MYSQL_DB="${MYSQL_DB:-trace_it}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-}"   # 留空则从运行中的容器自己的环境变量读取

# --- kubo（IPFS）仓库：物理备份整个 repo 目录，备份/恢复前后都必须先停止守护进程 ---
# 默认值与 scripts/ipfs/env.sh 一致；恢复演练会另外指定一个全新目录，见 restore-drill.sh
TRACE_IPFS_HOME_WIN="${TRACE_IPFS_HOME_WIN:-D:\\trace-ipfs}"

log() { printf '[backup] %s\n' "$*"; }
die() { printf '[backup] 错误：%s\n' "$*" >&2; exit 1; }

# 容器是否在运行
mysql_running() { docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; }

# 取容器自己的 root 密码（备份/恢复默认不要求调用方知道密码，只要容器在跑）
mysql_root_password() {
  if [[ -n "$MYSQL_ROOT_PASSWORD" ]]; then
    printf '%s' "$MYSQL_ROOT_PASSWORD"
    return
  fi
  docker exec "$MYSQL_CONTAINER" printenv MYSQL_ROOT_PASSWORD
}
