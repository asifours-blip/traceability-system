# shellcheck shell=bash
# 本地 kubo（IPFS）节点的公共配置，被 setup.sh / start.sh / stop.sh / status.sh 引用，不单独执行。
# 可在 Windows 的 Git Bash 或 WSL 里运行；kubo 本身是 Windows 版 ipfs.exe（WSL 下经 interop 调用）。
# 所有值都可用同名环境变量覆盖。

# 只读来源：用户原有目录里的 kubo 0.29.0 Windows 版，只复制、不修改、不在原位置运行
KUBO_SRC_WIN="${KUBO_SRC_WIN:-D:\\Desktop\\GraduationDesign\\kubo\\ipfs.exe}"
KUBO_SHA256="${KUBO_SHA256:-e75ace625d2f3dde79bb5357b1ccf3477dd6021ce629b9de43340ca59b3967c7}"

# 自建目录：bin/ 放复制来的 ipfs.exe，repo/ 是独立的 IPFS_PATH，logs/ 放守护进程日志
TRACE_IPFS_HOME_WIN="${TRACE_IPFS_HOME_WIN:-D:\\trace-ipfs}"

# 端口：避开 kubo 默认的 API 5001 / 网关 8080 / swarm 4001；网关与 swarm 不监听
IPFS_API_PORT="${IPFS_API_PORT:-5201}"

log() { printf '[ipfs] %s\n' "$*"; }
die() { printf '[ipfs] 错误：%s\n' "$*" >&2; exit 1; }

if grep -qi microsoft /proc/version 2>/dev/null; then
  IPFS_ENV=wsl
  to_unix() { wslpath -u "$1"; }
else
  IPFS_ENV=gitbash
  to_unix() { cygpath -u "$1"; }
  # Git Bash 会把 /ip4/... 这样的参数当成路径改写成 C:/Program Files/Git/ip4/...，这里关掉
  export MSYS_NO_PATHCONV=1
fi

TRACE_IPFS_HOME="$(to_unix "$TRACE_IPFS_HOME_WIN")"
KUBO_SRC="$(to_unix "$KUBO_SRC_WIN")"
KUBO_BIN="$TRACE_IPFS_HOME/bin/ipfs.exe"
REPO_WIN="$TRACE_IPFS_HOME_WIN\\repo"
LOG_DIR_WIN="$TRACE_IPFS_HOME_WIN\\logs"
API_MADDR="/ip4/127.0.0.1/tcp/${IPFS_API_PORT}"

# 所有 CLI 调用都显式指定 repo 与 API 地址，不读写默认的 ~/.ipfs
kubo() { "$KUBO_BIN" --repo-dir "$REPO_WIN" "$@"; }
kubo_api() { "$KUBO_BIN" --repo-dir "$REPO_WIN" --api "$API_MADDR" "$@"; }

# 守护进程是否在应答（Windows 回环上的 API，经 ipfs.exe 自己访问，WSL 下同样可用）
api_up() { kubo_api --timeout 3s id >/dev/null 2>&1; }
