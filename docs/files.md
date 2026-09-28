# 文件存储：上传、绑定、清理与公开读取

生产认证、质检报告存在本地 IPFS（kubo），链上只存 CID。原则：**文件必须能真实存取；链上数据以回执和读链结果为准**。

## 本地 kubo 节点

| 项 | 值 |
|----|----|
| 版本 | kubo 0.29.0 Windows 版，sha256 `e75ace62…b3967c7`（`scripts/ipfs/env.sh` 固定校验） |
| 来源 | 用户原有目录 `GraduationDesign\kubo\ipfs.exe`，**只复制**，原位置不运行、不修改 |
| 自建目录 | Windows `D:\trace-ipfs\`：`bin\ipfs.exe`、`repo\`（独立 IPFS_PATH，经 `--repo-dir` 传入，不碰 `~/.ipfs`）、`logs\` |
| 端口 | 只有 RPC API `127.0.0.1:5201`；网关、swarm 都不监听（`Addresses.Gateway/Swarm = []`） |
| 网络 | `daemon --offline`，`Bootstrap=[]`、`Routing.Type=none`、mDNS 关；实测已连接节点 0，进程只监听 5201 |
| 启停 | `bash scripts/ipfs/setup.sh`（复制 + 初始化 + 配置，可重复）、`start.sh`、`stop.sh`（只经本节点 API `shutdown`，不按进程名杀）、`status.sh` |

WSL 里的后端测试访问不到 Windows 回环（WSL2 NAT 模式，本机不改防火墙），所以另有 `scripts/ipfs/wsl-bridge.sh`：WSL 侧监听 `127.0.0.1:5201`，每个连接经 WSL interop 启动一个 Windows `python.exe`，由它连 Windows 的 kubo，两边按字节原样转发。只有本机 WSL 内的进程能连到这个端口。

### kubo 接口核验（0.29.0 实测）

| 调用 | 结果 |
|------|------|
| `POST /api/v0/add?pin=true` | 200 `{"Name","Hash","Size"}`，`Hash` 为 CIDv0 |
| `POST /api/v0/cat?offline=true` | 200 原始字节；本地没有该块 → **500** `{"Message":"block was not found locally (offline): ipld: could not find <cid>"}` |
| 非法 CID | 500 `{"Message":"invalid path …"}` |
| `pin/rm` | 200 `{"Pins":[cid]}`；已不在 pin 集合 → 500 `not pinned or pinned indirectly` |
| `pin/rm` 后 `repo/gc` 再 `cat` | 500 同上「not found locally」 |

`KuboClient`（`back-me/.../file/KuboClient.java`）只用 JDK `HttpURLConnection`：上传用分块传输边读边发，读取直接返回响应流；按 Message 区分 `MISSING`（块确定不在本地）与 `UNAVAILABLE`（连不上 / 超时）。原来 `libs/` 下来源不明的 IPFS jar 已从 POM 与仓库移除。

## 上传（`POST /upload`，生产商 / 分销商）

`UploadPipeline` 全程流式，内存占用与文件大小无关：

1. **检查**：读一遍临时文件（Spring multipart `file-size-threshold: 0`，上传内容一律先落盘），计字节数（超过 `file.max-bytes`，默认 10 MB，立即停止）、算 SHA-256、取前 16 字节按文件头判定类型。白名单：PNG、JPEG、WEBP、PDF；GIF / SVG / HTML 等拒绝。扩展名只用来和内容核对：内容不在白名单 → 415 `FILE_TYPE_NOT_ALLOWED`，扩展名与内容不符 → 415 `FILE_EXTENSION_MISMATCH`，没有扩展名按内容补上。空文件 400 `FILE_EMPTY`，超限 413 `FILE_TOO_LARGE`（容器层 `max-file-size: 11MB` 兜底，同样返回 413）。**不合格的文件不会写进 IPFS。**
2. **写入**：重新打开临时文件分块发给 kubo `add`（pin），同时再算一次 SHA-256，防止两次读取之间文件被换掉（409 `FILE_CHANGED`）。
3. **核对**：用拿到的 CID `cat` 回来逐字节算 SHA-256 与长度，必须与第 1 步一致；否则 502 `IPFS_VERIFY_FAILED`，不登记，并在没有其他记录引用该 CID 时取消 pin。

文件名清洗：去掉路径部分、控制字符、`<>:"/\|?*;`、Unicode 格式字符（如 RLO），折叠空白，去掉开头的点，限长 100 并保留扩展名。文件名只用于展示和下载头，存取都按 CID。

`/uploadBase64` 已删除：它要把整个文件放进 JSON 字符串再整体解码，内存约为文件的 2~3 倍，且前端已改为 multipart。按任意 CID 读文件的 `/file/{hash}`、`/fileBase64/{hash}` 也已删除。

## 状态机（`file_object`）

```
UPLOADED ──阶段交易 CONFIRMED，且读链得到的该阶段 CID 与记录一致──> BOUND
    │
    └──超过 file.orphan.ttl-hours（默认 24h）仍未绑定，且没有被未决/已确认交易占用──> ORPHANED（取消 pin）
```

- **占用**：提交生产 / 分销交易前，校验 CID 必须是当前账号上传、状态 UPLOADED、未被其他未决或已确认交易占用的记录（否则 400 字段错误），然后记下 `claim_trace_number / claim_stage`。同一内容可以上传多次，每次一条记录，一条记录只能绑定一个阶段。
- **绑定**：`ChainTxService` 在交易进入 CONFIRMED 时（发交易拿到成功回执，或查证 `RECEIPT_CONFIRMED` / `STATE_CONFIRMED`）回调 `StageConfirmedHandler`：先读链刷新读模型，再用**读链得到的 CID**、签名账号、占用的溯源号与阶段找到那条 UPLOADED 记录，条件更新为 BOUND（`bound_key = 溯源号:阶段` 唯一）。`bindConfirmed` 自己再查一次数据库，交易不是 CONFIRMED 一律不绑定。
- **FAILED / UNKNOWN 不绑定**：FAILED 的占用立即失效（可以拿去重新提交）；UNKNOWN 期间占用有效、不会被当成孤儿，查证为 CONFIRMED 时才绑定。回调失败（例如确认瞬间链读不到）只记日志，不影响交易结果；管理员重建读模型时补绑。
- **旧文件**：本功能上线前写在链上的 CID 没有记录。重建读模型时从本地 IPFS 流式读出，按同样的类型与大小规则核对后登记为 BOUND（`bind_source=REBUILD`，`chain_tx_id` 为空，依据是读链结果）并 pin 住；本地没有的不登记。

## 清理策略

- `FileOrphanTask` 每 `file.orphan.cleanup-interval-ms`（默认 1 小时）执行一次；管理员也可 `POST /admin/files/cleanup-orphans` 手动触发。
- 选出 `UPLOADED` 且 `created_at` 早于「现在 − ttl」的记录；被未决或已确认交易占用的跳过（交易还可能确认，或已确认待绑定）。其余条件更新为 ORPHANED。
- 取消 pin：若同一 CID 还有 UPLOADED / BOUND 记录引用（同一内容被上传过多次），保留 pin 并在 `unpin_note` 说明；否则 `pin/rm`。失败的下轮重试（`unpinned=0` 的 ORPHANED 会再处理）。
- **只取消 pin，不自动 GC**：kubo 不以 `--enable-gc` 启动，空间回收由运维执行 `ipfs repo gc`（或按需开启 kubo 的定期 GC）。取消 pin 之前内容仍可读，所以 ORPHANED 后的「删除」是在 GC 时真正发生。
- ORPHANED 的文件不能再用于上链（400「已被清理，请重新上传」）；BOUND 永不清理。

## 公开读取（`GET /trace/{溯源号}/file/{production|distribution}`，免登录）

- CID 只从链上该阶段读出（调用方不能指定 CID），且必须存在 CID 一致的 BOUND 记录，否则 404 `FILE_NOT_BOUND`（链上有 CID、交易未确认，也是 404）。
- 本地 IPFS 里取不到 → **410 `FILE_MISSING`**（JSON，不返回 500，也不返回空图片）；IPFS 不可用 → 503 `IPFS_UNAVAILABLE`。
- 响应头：`Content-Type` 取上传时按内容判定并登记的类型；`Content-Length`；`X-Content-Type-Options: nosniff`；`Content-Security-Policy: default-src 'none'; sandbox`；`Content-Disposition`：图片 `inline`，其他类型（PDF）一律 `attachment`，带 RFC 5987 `filename*=UTF-8''…`。
- 边读边写，不把文件读进内存；登记长度与实际读出长度不符时记错误日志（响应头已发出，客户端会判定下载不完整）。

详情页（`GET /batches/{tn}` 的 `stages[].file`）与消费者详情（`fileState`）会顺带用 `block/stat --offline` 检查文件是否还在：缺失时前端显示「文件缺失」及 SHA-256，而不是一张打不开的图。

## 测试

| 测试 | 内容 |
|------|------|
| `FilesAndQueriesIntegrationTest` / `…MySqlSmokeTest` | 超大 / 空 / 伪造扩展名 / 非白名单被拒且不写 IPFS；SHA-256 与 CID 读回核对、核对失败取消 pin；只能引用本账号上传且未占用的文件；FAILED、UNKNOWN 不绑定，查证 CONFIRMED 后绑定；未绑定不能公开读取；PDF 附件、图片内联与响应头；GC 后 410 `FILE_MISSING`；孤儿清理（共享 CID 保留 pin、未决交易保护、幂等） |
| `UploadMemoryBoundTest` | 在 `-Xmx48m` 的独立 JVM 里上传 192 MB 文件（堆上限的 4 倍），三步完成且 SHA-256 一致；实测堆峰值约 18 MB |
| `UploadPipelineTest` / `KuboClientTest` / `FileRulesTest` | IPFS 不可用 503、读回不一致 502、两次读取之间文件被换；kubo 错误分类；文件名清洗与文件头判定 |
| `RealChainFilesE2ETest` | 真实隔离链 + 真实 kubo：上传 → 上链 → 绑定 → 消费者读回 → 重启 kubo 后读回 → `pin rm` + `repo gc` 后 410；记录见 `docs/artifacts/local-chain-files-e2e-*.txt` |
