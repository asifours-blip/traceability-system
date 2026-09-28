#!/usr/bin/env bash
# 备份 MySQL：对 $MYSQL_CONTAINER 里的 $MYSQL_DB 库做逻辑备份（mysqldump），gzip 后落盘到 $BACKUP_DIR/mysql/。
# 前置：容器必须在运行（docker start "$MYSQL_CONTAINER"）。
# 用法：backup-mysql.sh [备注]
set -euo pipefail
source "$(dirname "$0")/env.sh"

mysql_running || die "$MYSQL_CONTAINER 未在运行，请先 docker start $MYSQL_CONTAINER"

mkdir -p "$BACKUP_DIR/mysql"
stamp="$(date +%Y%m%d-%H%M%S)"
note="${1:-}"
[[ -n "$note" ]] && note="-$note"
out="$BACKUP_DIR/mysql/${MYSQL_DB}-${stamp}${note}.sql.gz"

pass="$(mysql_root_password)"
[[ -n "$pass" ]] || die "取不到 MySQL root 密码：容器环境变量里没有 MYSQL_ROOT_PASSWORD，也没设 MYSQL_ROOT_PASSWORD"

# --single-transaction：InnoDB 一致性快照，不锁表；--routines --triggers --events：以防以后加了存储过程/触发器/事件；
# --set-gtid-purged=OFF：避免 GTID 相关的恢复告警（本地单机不需要）
docker exec -e MYSQL_PWD="$pass" "$MYSQL_CONTAINER" \
  mysqldump --single-transaction --routines --triggers --events --set-gtid-purged=OFF \
  -u root "$MYSQL_DB" | gzip > "$out"

size=$(du -h "$out" | cut -f1)
rows=$(zcat "$out" | grep -c '^INSERT INTO' || true)
log "已备份 $MYSQL_DB → $out（$size，$rows 条 INSERT 语句）"
