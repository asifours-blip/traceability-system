# shellcheck shell=bash
# 本地隔离链的公共配置。被 setup.sh / start.sh / stop.sh / smoke.sh 引用，不单独执行。
# 所有路径都可以用同名环境变量覆盖。

# 工作目录：节点、WeBASE-Front 副本、便携工具、私钥库都只放在这里
E2E_HOME="${E2E_HOME:-$HOME/trace-e2e}"

# 只读来源：用户原有链目录里的 fisco-bcos / build_chain.sh / WeBASE-Front 发行包，只复制、不修改
FISCO_SRC="${FISCO_SRC:-/mnt/d/Desktop/GraduationDesign/fisco-chain}"

# 仓库根目录（本脚本位于 scripts/local-chain/）
REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

# 端口：避开原链（p2p 30300-30303 / channel 20200-20203 / rpc 8545-8548 / WeBASE-Front 5002）
P2P_PORT="${P2P_PORT:-30800}"          # 30800-30803
CHANNEL_PORT="${CHANNEL_PORT:-20800}"  # 20800-20803
RPC_PORT="${RPC_PORT:-8845}"           # 8845-8848
FRONT_PORT="${FRONT_PORT:-5102}"
NODE_COUNT="${NODE_COUNT:-4}"
GROUP_ID="${GROUP_ID:-1}"

FRONT_URL="http://127.0.0.1:${FRONT_PORT}/WeBASE-Front"

TOOLS_DIR="$E2E_HOME/tools"
NODES_DIR="$E2E_HOME/nodes"
FRONT_DIR="$E2E_HOME/webase-front"
JAVA_HOME_E2E="$TOOLS_DIR/jdk11"

# build_chain.sh（2.7.2）只接受 OpenSSL 1.0.2/1.1，WSL 自带的是 3.x：
# 用 Ubuntu 20.04 官方源的 1.1.1f 包，解压到 tools/ 下使用，不装进系统
OPENSSL_DEB_BASE="http://archive.ubuntu.com/ubuntu/pool/main/o/openssl"
OPENSSL_DEBS=(
  "libssl1.1_1.1.1f-1ubuntu2.24_amd64.deb 7cf39d70a639017d1dd7c8d36daa2258063608688e449fddf40ffdd46f992a78"
  "openssl_1.1.1f-1ubuntu2.24_amd64.deb a595ba61adb73a39076720776767dc0edb9cb7fdbe5407c08343ef27fe493c4a"
)

# WeBASE-Front v1.5.5 需要 JDK 8~14：Eclipse Temurin 11 便携包
JDK_URL="https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.32.1%2B1/OpenJDK11U-jdk_x64_linux_hotspot_11.0.32.1_1.tar.gz"
JDK_TGZ="OpenJDK11U-jdk_x64_linux_hotspot_11.0.32.1_1.tar.gz"
JDK_SHA256="5c3f68887c325d36d852ba534303e1f5f1f5cae7d6cc1e951d73e0d8e98a058d"

log() { printf '[local-chain] %s\n' "$*"; }
die() { printf '[local-chain] 错误：%s\n' "$*" >&2; exit 1; }
