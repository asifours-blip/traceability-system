#!/usr/bin/env bash
# 恢复 MySQL：把 backup-mysql.sh 产出的 dump 灌进一个容器。
# 默认恢复到全新容器（恢复演练用）：不存在就用 mysql:8.0 新建一个，跟 $MYSQL_CONTAINER 完全隔离。
# 用法：restore-mysql.sh <dump.sql.gz> [容器名=trace-mysql-restore] [宿主机端口=13307] [库名=$MYSQL_DB]
set -euo pipefail
source "$(dirname "$0")/env.sh"

dump="${1:?用法：restore-mysql.sh <dump.sql.gz> [容器名] [宿主机端口] [库名]}"
[[ -f "$dump" ]] || die "找不到 $dump"
target_container="${2:-trace-mysql-restore}"
target_port="${3:-13307}"
target_db="${4:-$MYSQL_DB}"
target_password="restore-drill-$(date +%s)"

if docker ps -a --format '{{.Names}}' | grep -qx "$target_container"; then
  die "$target_container 已存在，恢复演练要求全新容器；先 docker rm -f $target_container（如果确认要丢弃它）或换个容器名"
fi

log "新建全新容器 $target_container（mysql:8.0，宿主机端口 $target_port，库 $target_db）"
docker run -d --name "$target_container" \
  -e MYSQL_ROOT_PASSWORD="$target_password" \
  -e MYSQL_DATABASE="$target_db" \
  -p "127.0.0.1:${target_port}:3306" \
  mysql:8.0 >/dev/null

log "等待 MySQL 就绪…"
for _ in $(seq 1 60); do
  if docker exec -e MYSQL_PWD="$target_password" "$target_container" mysqladmin ping -u root --silent >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
docker exec -e MYSQL_PWD="$target_password" "$target_container" mysqladmin ping -u root --silent >/dev/null 2>&1 \
  || die "$target_container 60 次轮询后仍未就绪"

log "灌入 $dump"
zcat "$dump" | docker exec -i -e MYSQL_PWD="$target_password" "$target_container" mysql -u root "$target_db"

rows=$(docker exec -e MYSQL_PWD="$target_password" "$target_container" \
  mysql -u root -N -e "SELECT table_name, table_rows FROM information_schema.tables WHERE table_schema='$target_db'" "$target_db")
log "恢复完成。表行数（information_schema 估算）："
echo "$rows" | sed 's/^/[backup]   /'

log "容器：$target_container  端口：127.0.0.1:${target_port}  库：$target_db  root 密码：$target_password"
log "用完后：docker rm -f $target_container（恢复演练只是验证，不是要长期保留的容器）"
