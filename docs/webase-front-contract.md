# WeBASE-Front 交易接口契约（真实链核验）

后端通过 WeBASE-Front 的 HTTP 接口发交易，不用 Java SDK。交易客户端能不能分清「成功 / 失败 / 未知」，取决于对这些接口响应的理解是否正确。本文所有结论都在**本地隔离的真实链**上逐条复现，不是根据文档推测。

## 依据版本

| 组件 | 版本 | 依据 |
|------|------|------|
| WeBASE-Front | **v1.5.5** | 发行包 `dist/release_note.txt`；内含 `fisco-bcos-java-sdk-2.9.3-SNAPSHOT.jar`、Spring Boot 2.7.10 |
| FISCO BCOS 节点 | **2.7.2**（commit `4c8a5bbe`） | `fisco-bcos -v`；节点 RPC `getClientVersion` |
| 合约 | v2 `Trace`，solc 0.4.25 编译 | `contracts/build/Trace.json`（`npm run compile` 生成） |

二进制与发行包复制自用户原有目录 `GraduationDesign/fisco-chain`（只读），原链未改动。搭链方法见文末「复现」。

核验记录：[`docs/artifacts/local-chain-smoke-2026-09-28.md`](artifacts/local-chain-smoke-2026-09-28.md)（27 步，含全部回执摘要）、[`local-chain-java-test-2026-09-28.txt`](artifacts/local-chain-java-test-2026-09-28.txt)（后端 `HttpUtil` / `StageProbe` 直连同一条链）。为理解响应来源，另外用 `javap` 只读查看了发行包 jar 的请求实体与异常处理（`ReqTransHandle`、`ExceptionsHandler`），但**判定规则只以真实响应为准**。

## `POST /WeBASE-Front/trans/handle`

请求体（`ReqTransHandle`）：`groupId`（缺省为 1）、`user`（签名地址，私钥须托管在该 WeBASE-Front）、`contractName`、`contractAddress`、`contractAbi`、`funcName`、`funcParam`（`List<String>`，JSON 数字会被转成字符串）。

### 非 constant 函数（发交易）

| 情况 | HTTP | 响应要点 | 交易是否上链 | 后端分类 |
|------|------|----------|--------------|----------|
| 成功 | 200 | 回执 JSON：`status:"0x0"`、`statusOK:true`、`message:"Success"`、`transactionHash`、`blockNumber:"9"` | 是 | CONFIRMED |
| 合约 revert | **200** | 回执 JSON：`status:"0x16"`、`statusMsg:"RevertInstruction"`、`statusOK:false`、`message` 为 revert 文本、`output` 为 `Error(string)` 编码；**有** `transactionHash` 与 `blockNumber` | 是（打包了但执行失败） | FAILED |
| 共识停滞（4 节点停 2 个） | **200** | 等满 `transMaxWait`（30 s）后返回：`transactionHash:null`、`blockNumber:"0"`、`status:"50001"`、`statusOK:false`、`message:"Transaction receipt timeout"` | **后来上链了**：节点恢复后 `getStageActors` 显示该阶段由本次签名地址写入 | **UNKNOWN** |
| 签名用户私钥不在 WeBASE | 422 | `{"code":201015,"data":null,"errorMessage":"user's privateKey is null"}` | 否（无法签名） | FAILED（未发出） |
| 参数个数/类型与 ABI 不符 | 422 | `{"code":201151,...,"errorMessage":" cannot encode in encodeMethodFromString ..."}` | 否（无法编码） | FAILED（未发出） |
| 函数名不存在 | 422 | `{"code":201151,...,"errorMessage":"Invalid method noSuchFunc , supported functions are: [...]"}` | 否 | FAILED（未发出） |

要点：

1. **HTTP 200 不代表成功**，revert 和回执超时都是 200。必须看回执 `status`。
2. **`statusOK:false` 不代表失败**。回执超时的那笔交易在节点恢复后被打包了；如果按 `statusOK` 记成失败并允许用户重试，就会出现「以为失败、其实成功」。区分依据是有没有 `transactionHash` 和块高：有哈希且有块高的非 0 状态才是确定的失败。
3. `status` 是字符串：`"0x0"` 成功、`"0x16"` revert（`RevertInstruction`）、`"50001"` 是 WeBASE 自己的回执超时码（不是链上状态）。
4. `blockNumber` 在 `/trans/handle` 与查回执接口的响应里都是**十进制字符串**（`"9"`），SDK 内部日志里是 `0x9`；解析时两种都接受。
5. revert 文本在 `message`（WeBASE 已解码）和 `output`（`0x08c379a0` + ABI 编码的字符串）里都有；后端优先自行解码 `output`，失败再用 `message`。
6. 422 是 WeBASE 的 `FrontException`，响应体 `{code, data, errorMessage}`。只有 `201015`、`201151` 两个码经实测确认发生在签名/发送之前；其他 422 码无法确认交易是否已发出，后端按 UNKNOWN 处理。

### constant 函数（只读调用）

- 成功：HTTP 200，JSON 数组，**所有返回值都是字符串**，包括 `uint`：`["仓配B","冷藏","冷链车","D001","济南","10","100","QmReport","1790529911979"]`。
- revert：HTTP 200，单元素数组 `["Call contract return error: Trace: traceNumber does not exist"]`。
- `getStageActors` 未写入的阶段返回 0 地址。

## `GET /WeBASE-Front/{groupId}/web3/transactionReceipt/{hash}`

| 情况 | HTTP | 响应 |
|------|------|------|
| 存在的交易 | 200 | 与 `/trans/handle` 返回的回执同构（同样的 `status` / `statusOK` / `message` / 十进制 `blockNumber`） |
| 不存在的哈希 | **500** | `{"code":500,"errorMessage":null}`（WeBASE 内部 `ReceiptParser` 空指针） |
| 格式错误的哈希 | 500 | 同上 |
| 不存在的 group | 422 | `{"code":101003,...,"errorMessage":"group: 9 of the connected node not exist!"}` |

「查不到」与其他服务端错误都是 500、无法区分，后端统一视为「暂时拿不到回执」，不据此下任何结论。另：`GET /{groupId}/web3/transaction/{hash}` 对不存在的哈希返回 HTTP 200 空响应体。

## 对后端的影响

- **回执超时时没有交易哈希**，所以「按哈希查回执」只在响应带了哈希但状态无法判定时才用得上；UNKNOWN 的主要恢复手段是读链查证（合约 v2 每个阶段只能写一次），见 [tx-lifecycle.md](tx-lifecycle.md)。
- 后端读超时必须**大于** WeBASE 的 `constant.transMaxWait`（默认 30 s），让 WeBASE 自己的回执超时先返回；默认 `webase-front.read-timeout-ms=40000`。
- WeBASE-Front 的 `GET /privateKey/localKeyStores` 会返回**明文私钥**，不要把它的响应写进日志或产物；冒烟脚本只记录地址。

## 未核验的情况

- WeBASE-Front 进程在收到请求后、返回前崩溃；节点全部停止时 `/trans/handle` 的具体响应。两者后端都按传输异常 / 5xx 归为 UNKNOWN。
- 除 `201015`、`201151`、`101003` 之外的其他 WeBASE 错误码。
- 国密链、WeBASE-Sign（`/trans/handleWithSign`）、多群组。

## 复现

在 WSL Ubuntu 中（本机为 Ubuntu 26.04，Docker 不可用，不依赖 Docker）：

```bash
bash scripts/local-chain/run-all.sh   # setup --clean → start → smoke（含共识停滞）→ 后端代码直连测试
bash scripts/local-chain/stop.sh
```

| 项 | 值 |
|----|----|
| 工作目录 | WSL `~/trace-e2e/`（`E2E_HOME` 可改）：`bin/` 复制的二进制，`nodes/` 4 节点，`webase-front/` 发行包副本，`tools/` 便携工具，`m2/` Maven 本地仓库 |
| 端口（新链） | p2p 30800–30803，channel 20800–20803，RPC 8845–8848，WeBASE-Front 5102；节点只监听 127.0.0.1 |
| 端口（原链，避开） | p2p 30300–30303，channel 20200–20203，RPC 8545–8548，WeBASE-Front 5002 |
| OpenSSL | 原版 `build_chain.sh`（2.7.2）只接受 OpenSSL 1.0.2/1.1，WSL 自带 3.5：使用 Ubuntu 20.04 官方源的 `openssl` / `libssl1.1` 1.1.1f 包，`dpkg-deb -x` 解压到 `tools/`，sha256 固定在 `env.sh` |
| JDK | WeBASE-Front 用 Temurin 11（`tools/jdk11`）；后端直连测试用 Temurin 21（`tools/jdk21`）；均为 Adoptium 便携包，校验 sha256 |
| 合约部署 | 用 `contracts/build/Trace.json` 的 bytecode + ABI，经 WeBASE-Front `POST /contract/deploy` 部署（构造参数三个 0 地址，角色由 owner 事后授予），**不**使用 WeBASE 内置编译 |
| 账户 | 每次运行在 WeBASE-Front 里新建 `e2e_<runId>_{owner,producer,distributor,retailer,outsider}` 托管账户，私钥只在 `webase-front/h2/` 里 |

后端直连测试放在 WSL 里跑，是因为本机 Windows 访问不到 WSL 内的服务（localhost 转发被拒、WSL 网段不可达），这里不修改系统网络设置。
