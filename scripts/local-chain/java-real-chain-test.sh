#!/usr/bin/env bash
# 在 WSL 里用后端自己的 HttpUtil / StageProbe 打本地隔离链（RealChainSmokeTest）。
# 之所以在 WSL 里跑：本机 Windows 访问不到 WSL 内的服务（localhost 转发不可用），这里不改系统网络设置。
# 前置：start.sh、smoke.sh 已执行（需要 $E2E_HOME/last-smoke.json）。
# Maven 默认用 Windows 上已有的发行包（纯 Java，可在 Linux 下直接运行）；本地仓库放在 $E2E_HOME/m2，与 Windows 的 ~/.m2 隔离。
set -euo pipefail
source "$(dirname "$0")/env.sh"

MAVEN_HOME_E2E="${MAVEN_HOME_E2E:-/mnt/d/Software/apache-maven-3.9.16}"
JDK21_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"
JDK21_TGZ="OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"
JDK21_SHA256="ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94"
JDK21_HOME="$TOOLS_DIR/jdk21"

[[ -f "$E2E_HOME/last-smoke.json" ]] || die "缺少 $E2E_HOME/last-smoke.json，请先运行 smoke.sh"
[[ -x "$MAVEN_HOME_E2E/bin/mvn" ]] || die "找不到 Maven：$MAVEN_HOME_E2E（可用 MAVEN_HOME_E2E 指定）"

if [[ ! -x "$JDK21_HOME/bin/java" ]]; then
  [[ -f "$TOOLS_DIR/$JDK21_TGZ" ]] || curl -sSfL --max-time 900 -o "$TOOLS_DIR/$JDK21_TGZ" "$JDK21_URL"
  (cd "$TOOLS_DIR" && echo "$JDK21_SHA256  $JDK21_TGZ" | sha256sum -c --quiet -) || die "JDK 21 校验失败"
  mkdir -p "$JDK21_HOME"
  tar -xzf "$TOOLS_DIR/$JDK21_TGZ" -C "$JDK21_HOME" --strip-components=1
fi

# 编译产物放到 WSL 目录，不覆盖 Windows 侧的 back-me/target
build_dir="$E2E_HOME/back-me-build"
rm -rf "$build_dir" && mkdir -p "$build_dir"
tar -C "$REPO_ROOT" --exclude=back-me/target -cf - back-me | tar -C "$build_dir" -xf -
mkdir -p "$build_dir/contracts/abi" && cp "$REPO_ROOT/contracts/abi/Trace.json" "$build_dir/contracts/abi/"

cd "$build_dir/back-me"
report=target/surefire-reports/TEST-com.qhx.back.chain.RealChainSmokeTest.xml
out="$REPO_ROOT/docs/artifacts/local-chain-java-test-$(date +%F).txt"
{
  echo "# RealChainSmokeTest $(date '+%F %T %z')：后端 HttpUtil / StageProbe 直连本地隔离链"
  echo "# JDK: $("$JDK21_HOME/bin/java" -version 2>&1 | head -1)；合约地址: $(python3 -c "import json;print(json.load(open('$E2E_HOME/last-smoke.json'))['contractAddress'])")"
  JAVA_HOME="$JDK21_HOME" E2E_SMOKE_FILE="$E2E_HOME/last-smoke.json" \
    "$MAVEN_HOME_E2E/bin/mvn" -B -q -Dmaven.repo.local="$E2E_HOME/m2" -Dtest=RealChainSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test \
    | grep -E '^\[real-chain\]|Tests run|FAIL|ERROR' || true
  [[ -f "$report" ]] && grep -o 'tests="[0-9]*"\|failures="[0-9]*"\|errors="[0-9]*"\|skipped="[0-9]*"' "$report" | head -4 | tr '\n' ' '
  echo
} | tee "$out"
[[ -f "$report" ]] && grep -q 'failures="0"' "$report" && grep -q 'errors="0"' "$report" && grep -q 'skipped="0"' "$report" \
  || die "RealChainSmokeTest 未通过（见 $out）"
log "RealChainSmokeTest 在真实链上通过，记录：$out"
