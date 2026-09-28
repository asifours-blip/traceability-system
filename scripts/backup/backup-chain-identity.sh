#!/usr/bin/env bash
# 备份链上标识：合约地址、群组 ID、ABI 版本（sha256 + 当时的 git commit）。
# 这不是链数据本身的备份（区块账本不在本仓库管辖范围，节点自己的数据目录才是账本的备份对象）；
# 这里存的是「重新指向同一条链 / 同一份合约」所需要的最小元数据，MySQL 恢复后重建读模型要靠它。
# 用法：backup-chain-identity.sh [来源 smoke json，默认按 WSL 里的 $E2E_HOME/last-smoke.json 猜测]
set -euo pipefail
source "$(dirname "$0")/env.sh"

abi_file="$REPO_ROOT/contracts/abi/Trace.json"
[[ -f "$abi_file" ]] || die "找不到 $abi_file"
abi_sha256="$(sha256sum "$abi_file" | cut -d' ' -f1)"
git_commit="$(cd "$REPO_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"

smoke_json="${1:-}"
if [[ -z "$smoke_json" ]]; then
  # 常见位置：WSL 隔离链的 $E2E_HOME/last-smoke.json（本机 Git Bash 里通过 wsl.exe 读不到 WSL 路径，
  # 所以这里只在调用方显式传参，或者本机也保留了一份 docs/artifacts/local-chain-smoke-*.json 时才自动找）
  candidate="$(ls -t "$REPO_ROOT"/docs/artifacts/local-chain-smoke-*.json 2>/dev/null | head -1 || true)"
  [[ -n "$candidate" ]] && smoke_json="$candidate"
fi

mkdir -p "$BACKUP_DIR/chain-identity"
stamp="$(date +%Y%m%d-%H%M%S)"
out="$BACKUP_DIR/chain-identity/identity-${stamp}.json"

contract_address="null"
group_id="null"
front_url="null"
# 用 grep/sed 取值，不依赖 python3（Windows Git Bash 下未必有；本机 smoke.json 结构固定，字段名不重复出现在别处的风险很低）
json_str_field() { grep -m1 "\"$2\"[[:space:]]*:" "$1" | sed -E 's/.*"'"$2"'"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/'; }
json_num_field() { grep -m1 "\"$2\"[[:space:]]*:" "$1" | sed -E 's/.*"'"$2"'"[[:space:]]*:[[:space:]]*([0-9]+).*/\1/'; }
if [[ -n "$smoke_json" && -f "$smoke_json" ]]; then
  contract_address="$(json_str_field "$smoke_json" contractAddress)"
  group_id="$(json_num_field "$smoke_json" groupId)"
  front_url="$(json_str_field "$smoke_json" frontUrl)"
  [[ -n "$contract_address" ]] || contract_address="null"
  [[ -n "$group_id" ]] || group_id="null"
  [[ -n "$front_url" ]] || front_url="null"
else
  log "没有传入 smoke json，也没在 docs/artifacts 下找到 local-chain-smoke-*.json：contractAddress/groupId 记为 null，请手动补充"
fi

cat > "$out" <<JSON
{
  "capturedAt": "$(date -Is)",
  "contractAddress": $( [[ "$contract_address" == "null" ]] && echo null || echo "\"$contract_address\"" ),
  "groupId": $( [[ "$group_id" == "null" ]] && echo null || echo "$group_id" ),
  "frontUrl": $( [[ "$front_url" == "null" ]] && echo null || echo "\"$front_url\"" ),
  "abiFile": "contracts/abi/Trace.json",
  "abiSha256": "$abi_sha256",
  "repoCommit": "$git_commit",
  "sourceSmokeJson": $( [[ -n "$smoke_json" ]] && echo "\"$smoke_json\"" || echo null )
}
JSON

log "已写入 $out"
cat "$out" | sed 's/^/[backup]   /'
