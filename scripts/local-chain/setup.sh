#!/usr/bin/env bash
# 在 WSL Ubuntu 里搭一条与原链完全隔离的 FISCO BCOS 2.7.2 本地链 + WeBASE-Front v1.5.5 副本。
# 可重复执行：已存在的工具/节点/WeBASE-Front 副本会跳过；加 --clean 先删除 $E2E_HOME 下的节点与 WeBASE-Front 副本。
#
# 只读取 $FISCO_SRC（原链目录）并复制文件，不在其中写任何东西。
# 需要联网下载：Ubuntu 官方源的 OpenSSL 1.1.1f 包、Adoptium 的 Temurin 11，均校验 sha256。
# FISCO_SRC 默认是本机原链目录；CI 里指向 fetch-official.sh 的输出（官方源 + 哈希校验）。
set -euo pipefail
source "$(dirname "$0")/env.sh"

if [[ "${1:-}" == "--clean" ]]; then
  [[ -x "$(dirname "$0")/stop.sh" ]] && bash "$(dirname "$0")/stop.sh" || true
  log "删除 $NODES_DIR 与 $FRONT_DIR"
  rm -rf "$NODES_DIR" "$FRONT_DIR"
fi

[[ -f "$FISCO_SRC/fisco-bcos" && -f "$FISCO_SRC/build_chain.sh" && -d "$FISCO_SRC/webase-front/dist" ]] \
  || die "找不到来源目录 $FISCO_SRC（需要 fisco-bcos、build_chain.sh、webase-front/dist）"

mkdir -p "$E2E_HOME/bin" "$TOOLS_DIR/debs" "$TOOLS_DIR/bin"

sha_check() { echo "$2  $1" | sha256sum -c --quiet - || die "$1 校验失败"; }

# ---------- 1. 便携 OpenSSL 1.1.1f ----------
if [[ ! -x "$TOOLS_DIR/openssl11/usr/bin/openssl" ]]; then
  for entry in "${OPENSSL_DEBS[@]}"; do
    read -r name sum <<<"$entry"
    [[ -f "$TOOLS_DIR/debs/$name" ]] || curl -sSfL --max-time 300 -o "$TOOLS_DIR/debs/$name" "$OPENSSL_DEB_BASE/$name"
    (cd "$TOOLS_DIR/debs" && sha_check "$name" "$sum")
    dpkg-deb -x "$TOOLS_DIR/debs/$name" "$TOOLS_DIR/openssl11"
  done
fi
cat >"$TOOLS_DIR/bin/openssl" <<'WRAP'
#!/bin/bash
# 便携 OpenSSL 1.1.1f，只给 build_chain.sh 用
O="$(cd "$(dirname "$0")/../openssl11" && pwd)"
export LD_LIBRARY_PATH="$O/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export OPENSSL_CONF="$O/etc/ssl/openssl.cnf"
exec "$O/usr/bin/openssl" "$@"
WRAP
chmod +x "$TOOLS_DIR/bin/openssl"
log "OpenSSL: $("$TOOLS_DIR/bin/openssl" version)"

# ---------- 2. 便携 JDK 11 ----------
if [[ ! -x "$JAVA_HOME_E2E/bin/java" ]]; then
  [[ -f "$TOOLS_DIR/$JDK_TGZ" ]] || curl -sSfL --max-time 900 -o "$TOOLS_DIR/$JDK_TGZ" "$JDK_URL"
  (cd "$TOOLS_DIR" && sha_check "$JDK_TGZ" "$JDK_SHA256")
  mkdir -p "$JAVA_HOME_E2E"
  tar -xzf "$TOOLS_DIR/$JDK_TGZ" -C "$JAVA_HOME_E2E" --strip-components=1
fi
log "Java: $("$JAVA_HOME_E2E/bin/java" -version 2>&1 | head -1)"

# ---------- 3. 复制链二进制并建链 ----------
cp -f "$FISCO_SRC/fisco-bcos" "$FISCO_SRC/build_chain.sh" "$E2E_HOME/bin/"
chmod +x "$E2E_HOME/bin/fisco-bcos"
log "$("$E2E_HOME/bin/fisco-bcos" -v | head -1)"

if [[ ! -d "$NODES_DIR/127.0.0.1" ]]; then
  (cd "$E2E_HOME" && PATH="$TOOLS_DIR/bin:$PATH" bash bin/build_chain.sh \
      -l "127.0.0.1:${NODE_COUNT}" -p "${P2P_PORT},${CHANNEL_PORT},${RPC_PORT}" \
      -e ./bin/fisco-bcos -o nodes >"$E2E_HOME/build_chain.log" 2>&1) \
    || { cat "$E2E_HOME/build_chain.log"; die "build_chain.sh 失败"; }
  # 只监听回环地址，不对局域网暴露
  sed -i -e 's/^\(\s*channel_listen_ip=\).*/\1127.0.0.1/' -e 's/^\(\s*listen_ip=\).*/\1127.0.0.1/' \
    "$NODES_DIR"/127.0.0.1/node*/config.ini
  log "已生成 ${NODE_COUNT} 节点：$NODES_DIR"
fi

# ---------- 4. 复制 WeBASE-Front 发行包并指向新链 ----------
if [[ ! -d "$FRONT_DIR/apps" ]]; then
  src="$FISCO_SRC/webase-front/dist"
  mkdir -p "$FRONT_DIR"
  # 只复制程序与模板配置；不复制原来的 conf 证书、h2 私钥库、exportedKey、日志
  cp -r "$src/apps" "$src/lib" "$src/start.sh" "$src/stop.sh" "$src/status.sh" "$src/release_note.txt" "$FRONT_DIR/"
  cp -r "$src/conf_template" "$FRONT_DIR/conf"
  cp "$NODES_DIR"/127.0.0.1/sdk/* "$FRONT_DIR/conf/"
  yml="$FRONT_DIR/conf/application.yml"
  sed -i \
    -e "s#^\(\s*url: jdbc:h2:file:\).*webasefront#\1./h2/webasefront#" \
    -e "/^server:/,/^[a-z]/ s#^\(\s*port:\).*#\1 ${FRONT_PORT}#" \
    -e "s#^\(\s*channelPort:\).*#\1 ${CHANNEL_PORT}#" \
    -e "s#^\(\s*nodePath:\).*#\1 ${NODES_DIR}/127.0.0.1/node0#" \
    -e "s#^\(\s*monitorEnabled:\).*#\1 false#" \
    "$yml"
  sed -i 's/\r$//' "$FRONT_DIR"/*.sh
  chmod +x "$FRONT_DIR"/*.sh
  log "WeBASE-Front $(tr -d '\r\n' <"$FRONT_DIR/release_note.txt") 副本：$FRONT_DIR"
fi
grep -nE '^\s*(port|channelPort|nodePath|url):' "$FRONT_DIR/conf/application.yml" | sed 's/^/[local-chain]   /'
log "setup 完成"
