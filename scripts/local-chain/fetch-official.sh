#!/usr/bin/env bash
# 不依赖本机原链目录：从官方源取得 FISCO BCOS 2.7.2 与 WeBASE-Front v1.5.5，整理成 setup.sh 需要的 FISCO_SRC 目录结构：
#   $OFFICIAL_DIR/fisco-bcos、$OFFICIAL_DIR/build_chain.sh、$OFFICIAL_DIR/webase-front/dist/
# 用法：bash fetch-official.sh，然后 FISCO_SRC="$E2E_HOME/official" bash setup.sh --clean（run-all.sh 里设 USE_OFFICIAL=1 自动完成）
#
# 来源与校验（任何一项不符都中止）：
# - fisco-bcos.tar.gz、build_chain.sh：GitHub FISCO-BCOS/FISCO-BCOS Release v2.7.2，sha256 固定
# - WeBASE-Front v1.5.5 的程序（apps/、lib/、conf/）：WeBASE 官方 Docker Hub 镜像 webasepro/webase-front，按镜像摘要拉取，
#   取出的文件再按清单 sha256 核对。没有用预编译 zip / 源码构建的原因：
#     * 官方文档给出的预编译包地址（腾讯云 COS）2026-09-28 实测返回 403；
#     * v1.5.5 源码构建依赖 Sonatype 上的 fisco-bcos-java-sdk 2.9.3-SNAPSHOT，快照可变，无法固定哈希
# - start.sh / stop.sh / status.sh：GitHub WeBankBlockchain/WeBASE-Front 标签 v1.5.5，核对提交哈希后取用
#   （与本机原链目录里的三个脚本内容相同，2026-09-28 核对）
# - 没有 docker 的环境（如本机 WSL）可以先在别处按同一摘要导出镜像的 /dist，用 FRONT_DIST_DIR 指过来，仍按清单哈希核对
set -euo pipefail
source "$(dirname "$0")/env.sh"

OFFICIAL_DIR="${OFFICIAL_DIR:-$E2E_HOME/official}"
FISCO_RELEASE="https://github.com/FISCO-BCOS/FISCO-BCOS/releases/download/v2.7.2"
FISCO_TGZ_SHA256="8adf23ae91e7b311a553d433a1106037988a7800741c7330864ce87c0c2178c7"
# 解压出的二进制；与本机原链目录里的 fisco-bcos 逐字节相同（2026-09-28 核对）
FISCO_BIN_SHA256="81c7b1b56fa9433147ca220a2a29d234901b5283b17021bf0b67820da31d0dbe"
BUILD_CHAIN_SHA256="75e0c9082540cc5130a46135b54b790f6ddc7b278bb288653bfa8eefb396f9cd"
FRONT_IMAGE="webasepro/webase-front@sha256:19d7d98ebc7942258459e060ab84c23068f09b56608d362b1b62cc4d3a9b3dd8"
# 镜像 /dist 下 apps、lib、conf 全部文件的「sha256sum 清单」（按路径 C 排序）的 sha256
FRONT_MANIFEST_SHA256="09ffa325c1cad7d2b2eaa01fc88083d27456d5eff7f46ce163497e949bf0f430"
FRONT_REPO="https://github.com/WeBankBlockchain/WeBASE-Front.git"
FRONT_TAG="v1.5.5"
FRONT_COMMIT="b9267e7c0698bb69c9f1cab511daf974d9d405ce"

sha_check() { echo "$2  $1" | sha256sum -c --quiet - || die "$1 校验失败"; }
fetch() { [[ -f "$2" ]] || curl -sSfL --retry 3 --max-time 900 -o "$2" "$1"; }

mkdir -p "$OFFICIAL_DIR/dl"
cd "$OFFICIAL_DIR/dl"

# ---------- 1. FISCO BCOS 2.7.2 ----------
fetch "$FISCO_RELEASE/fisco-bcos.tar.gz" fisco-bcos.tar.gz
sha_check fisco-bcos.tar.gz "$FISCO_TGZ_SHA256"
tar -xzf fisco-bcos.tar.gz -C "$OFFICIAL_DIR" fisco-bcos
(cd "$OFFICIAL_DIR" && sha_check fisco-bcos "$FISCO_BIN_SHA256")
fetch "$FISCO_RELEASE/build_chain.sh" build_chain.sh
sha_check build_chain.sh "$BUILD_CHAIN_SHA256"
cp -f build_chain.sh "$OFFICIAL_DIR/build_chain.sh"
log "FISCO BCOS 二进制与 build_chain.sh 已校验"

# ---------- 2. WeBASE-Front v1.5.5 程序：按摘要固定的官方镜像 ----------
front_src="${FRONT_DIST_DIR:-}"
if [[ -z "$front_src" ]]; then
  command -v docker >/dev/null || die "需要 docker 拉取 $FRONT_IMAGE，或用 FRONT_DIST_DIR 指定已按同一摘要导出的 /dist"
  docker pull -q "$FRONT_IMAGE" >/dev/null
  cid="$(docker create "$FRONT_IMAGE")"
  rm -rf image-dist && mkdir image-dist
  docker cp "$cid:/dist/." image-dist/ && docker rm "$cid" >/dev/null
  front_src="$OFFICIAL_DIR/dl/image-dist"
fi
manifest="$( (cd "$front_src" && find apps lib conf -type f | LC_ALL=C sort | xargs sha256sum) | sha256sum | cut -d' ' -f1)"
[[ "$manifest" == "$FRONT_MANIFEST_SHA256" ]] || die "WeBASE-Front 文件清单哈希不符：$manifest"

# ---------- 3. 启动脚本：官方仓库标签 v1.5.5 ----------
rm -rf WeBASE-Front
git -c advice.detachedHead=false clone -q --depth 1 --branch "$FRONT_TAG" "$FRONT_REPO" WeBASE-Front
actual="$(git -C WeBASE-Front rev-parse HEAD)"
[[ "$actual" == "$FRONT_COMMIT" ]] || die "WeBASE-Front $FRONT_TAG 提交哈希不符：$actual"

# 组装成与官方发行包相同的布局（setup.sh 只读这些）
dist="$OFFICIAL_DIR/webase-front/dist"
rm -rf "$OFFICIAL_DIR/webase-front" && mkdir -p "$dist"
cp -r "$front_src/apps" "$front_src/lib" "$dist/"
cp -r "$front_src/conf" "$dist/conf_template"
cp WeBASE-Front/start.sh WeBASE-Front/stop.sh WeBASE-Front/status.sh "$dist/"
echo "$FRONT_TAG" >"$dist/release_note.txt"
log "WeBASE-Front $FRONT_TAG：程序来自 $FRONT_IMAGE（清单 $manifest），脚本来自 $FRONT_COMMIT"
log "fetch-official 完成：FISCO_SRC=$OFFICIAL_DIR"
