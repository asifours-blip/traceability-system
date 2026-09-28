# 运行、配置与验证详细参考

本文承接提交 [61a7d17](https://github.com/asifours-blip/traceability-system/commit/61a7d17dcd5b2b72bd8ab9ffa2637d2b8848418f) 的原首页详细说明。下列历史实验数字和本地运行记录属于各自标注的时间与环境，不代表本次重新执行。当前入口见 [项目首页](../README.md)。

毕业设计项目：用 **FISCO BCOS 联盟链** 记录农产品「生产者 → 分销商 → 零售商」流转，证书/检测报告等大文件走 **IPFS**（链上只存 CID），消费者扫码查看。后端经 **WeBASE-Front** 调合约，不直连 Java SDK。

本仓库当前定位是 **可核查的测试实践底稿**，不是生产系统，也不是「我部署了一条公链」：所有测试与 CI 声明都能回到仓库文件复现。

## 功能特性（以代码为准）

- **三角色流转**：生产者 `newAgroFood` → 分销商 `addTraceInfoByDistributor` → 零售商 `addTraceInfoByRetailer` → 消费者查询详情
- **联盟链存证**：关键字段写入 `Trace` 合约；角色由合约 `onlyProducer` / `onlyDistributor` / `onlyRetailer` 校验。后端另按账号角色鉴权（见「鉴权方式」）
- **IPFS**：证书、检测报告经 `/upload` 流式上传到本地 kubo（按内容判定类型、限大小、服务端算 SHA-256 并读回核对 CID），链上存 CID；交易 CONFIRMED 后才绑定、才可公开读取，超期未绑定的清理为孤儿。详见 [docs/files.md](../docs/files.md)
- **分页查询与读模型**：批次列表、消费者查询分页取 MySQL 读模型，不逐条读链；读模型可由管理员从链上幂等重建，并为本功能上线前的旧批次按链上写入者回填归属。详见 [docs/read-model.md](../docs/read-model.md)
- **IoT 看板**：`IotDataSimulatorTask` **每 5 分钟随机写入** 温湿度/光照到 MySQL，**不是真实传感器**
- **二维码**：前端生成溯源号二维码，扫码进详情（详情接口在鉴权白名单，只返回公开字段）
- **批次归属与交接**：生产商建档时指定分销商，分销商指定零售商；v3 合约按链上指定地址强制下一阶段写入，旧 v2 批次仍只有后端指定校验。字段校验、归属、链下更正仍在后端，详见 [docs/business-flow.md](../docs/business-flow.md)
- **账号 + Bearer token**：用户名/密码（BCrypt）登录，服务端签发 256 位随机 token（库里只存 sha256）；交易签名地址取自服务端账号绑定的地址，客户端 `address` 头一律忽略
- **离线测试门禁**：Mock `WeBaseClient`，Maven 单测不连链；前端 ESLint

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 2.6.13 · MyBatis-Plus 3.5.3 · MySQL · Springfox 3.0 · Hutool |
| 前端 | Vue 2.6 · Element UI 2.15 · Vuex · Vue Router · ECharts 6 · qrcode · axios |
| 区块链 | FISCO BCOS · WeBASE-Front · Solidity `^0.4.25` 合约 `Trace` |
| 存储 | IPFS（kubo）；链下表 `iot_sensor_data` |
| 工具链 | Maven（编译目标 14，CI 用 JDK 21）· Vue CLI 5 · GitHub Actions（`ci.yml`） |

### IPFS 客户端

原来 `back-me/libs/` 下以 `systemPath` 引用的五个 IPFS Java 客户端 JAR 已移除（来源不明，`cat` 只能整块读入内存，构造时就连守护进程）。现在用 JDK `HttpURLConnection` 直连 kubo HTTP RPC（`com.qhx.back.file.KuboClient`），上传与读取都是流式的，启动时不连节点。

## 系统架构

```
┌──────────────────────────────────┐
│   前端 front-me (Vue 2.6)        │
│  生产者/分销商/零售商/消费者/IoT  │
└───────────────┬──────────────────┘
                │ HTTP + Bearer token
┌───────────────▼──────────────────┐
│  后端 back-me (Spring Boot 8010) │
│  Controller 直调 WeBASE HttpUtil │
│  AddressInterceptor · WeBaseClient│
└───┬──────────┬──────────┬────────┘
    │          │          │
┌───▼───┐ ┌───▼────────┐ ┌▼──────────┐
│ MySQL │ │ WeBASE-Front│ │  IPFS     │
│ IoT模拟│ │→FISCO BCOS │ │ 大文件 CID │
└───────┘ │  合约 Trace │ └───────────┘
          └─────────────┘
```

链上/链下边界：流转与角色在合约；用户会话不在链上；IoT 时序数据在 MySQL；大文件在 IPFS。

## 项目结构

```
code1.1.3/
├── back-me/                       # Spring Boot
│   └── src/main/java/com/qhx/back/
│       ├── controller/            # Trace / User / Owner / IPFS / IoT / Block …
│       ├── interceptor/           # AddressInterceptor（token → 账号 → 绑定地址 + 角色）
│       ├── task/IotDataSimulatorTask.java
│       └── resources/
│           ├── application.yml            # 模板 + 内嵌 ABI
│           └── application-local.yml.example
├── front-me/                      # Vue 2.6
├── contracts/                     # v2 与 v3 Solidity 源码（见该目录 README）
│   └── abi/Trace.json、TraceV3.json  # 分版本生成的运行时 ABI
├── docs/                          # 证据、架构、设计说明
└── .github/workflows/             # ci.yml（测试）
```

## 快速开始

### 环境

- JDK 21（与现有 CI 一致；`pom.xml` 编译目标 **14**）、Maven 3.6+
- Node.js 16+、npm
- MySQL 5.7+（IoT 表 + 账号/会话表；无库时后端仍能起，但无法登录，定时任务会写失败）
- FISCO BCOS + WeBASE-Front（链上读写）
- IPFS kubo：`bash scripts/ipfs/setup.sh && bash scripts/ipfs/start.sh`（复制本机已有的 kubo 0.29.0 到 `D:\trace-ipfs`，独立 repo，离线，只监听 `127.0.0.1:5201`）

### 配置（不要提交密钥）

`application.yml` 已脱敏。本地复制：

```bash
cd back-me/src/main/resources
cp application-local.yml.example application-local.yml
```

`application-local.yml` 已在 `.gitignore`，**永远不要 commit**。请勿把数据库密码、合约私钥、WeBASE 密钥写进仓库。

### 账号初始化

```bash
# 1. 建账号表、会话表、交易记录表与业务表（库名与 MYSQL_DB 一致，默认 mysql；可重复执行）
mysql -u root -p mysql < back-me/src/main/resources/db/auth-schema.sql
mysql -u root -p mysql < back-me/src/main/resources/db/chain-tx-schema.sql
mysql -u root -p mysql < back-me/src/main/resources/db/business-schema.sql   # 批次归属、交接历史、更正、授权状态

# 2. 首次启动时注入管理员（库里已有 ADMIN 时不会覆盖）
export ADMIN_INITIAL_PASSWORD='<至少 8 位，自行生成>'
export ADMIN_USERNAME=admin                 # 可选，默认 admin
export ADMIN_ADDRESS=0x...                  # 可选，默认 contract.owner
```

仓库不内置默认密码：缺少 `ADMIN_INITIAL_PASSWORD` 时后端照常启动，但跳过管理员创建并打 ERROR 日志。之后由管理员在「用户管理」页新建生产商/分销商/零售商账号；没有公开注册入口。

### 后端 / 前端

```bash
cd back-me
mvn spring-boot:run     # 8010；Swagger: http://localhost:8010/swagger-ui/index.html

cd ../front-me
npm ci
npm run serve
npm run lint
```

### 测试（离线，不连链）

```bash
cd back-me
mvn -B test          # 不连 FISCO / WeBASE / MySQL / IPFS

cd front-me
npm run lint
```

用例清单与最近一次本地结果见 [docs/test_report.md](../docs/test_report.md)。

### 本地隔离链（真实 FISCO BCOS + WeBASE-Front，可选）

在 WSL Ubuntu 中执行 `bash scripts/local-chain/run-all.sh`（加 `USE_OFFICIAL=1` 则不读本机原链目录，改从官方源下载并校验哈希，CI 手动 workflow 用的就是这种方式）：搭 4 节点 FISCO BCOS 2.7.2 与 WeBASE-Front v1.5.5 副本（独立目录与端口，不碰原有链），部署 v2 合约，跑完整三阶段与越权/乱序/重复反例、共识停滞场景，并用后端代码直连同一条链。WeBASE-Front 接口契约与运行记录见 [docs/webase-front-contract.md](../docs/webase-front-contract.md) 与 [docs/artifacts/](../docs/artifacts/)。Postman 手工集合 11 条：[docs/artifacts/](../docs/artifacts/)。该集合是请求模板，没有导出的执行结果；后端测试使用 Maven。

## 智能合约

源码在 [`contracts/`](../contracts/README.md)。`code1.1.3` 原先没有 `.sol`；现有文件来自同课题早期目录 `code1.1/contracts-me`，函数名已与运行时 ABI（`contracts/abi/Trace.json`）对齐。

- **角色**：部署者为 owner，只有 owner 能 `addProducer` / `addDistributor` / `addRetailer` 与 `removeProducer` / `removeDistributor` / `removeRetailer`；owner 自身不持有业务角色，持有者只能 `renounce*` 放弃自己的角色
- **生产**：`newAgroFood(...)`，`onlyProducer`；溯源号非空且不可重复
- **流转**：v2/v3 均强制 生产 → 分销 → 零售、每阶段只写一次；v3 另按链上指定地址强制交接。条目合约只接受所属 Trace 写入
- **版本迁移**：v1 已部署漏洞不因源码更新消失；v3 是新地址与破坏性 ABI，旧 v2 批次固定其原部署地址继续流转，不迁入 v3。详见 [contracts/README.md](../contracts/README.md)
- **查询**：`getAgroFoodInfo` / `getAgroFoodInfoByDistributor` / `getAgroFoodInfoByRetailer` / `getAgroFoodList`
- **没有** `getAgroFoodListDetail`：原来的 `GET /trace/list` 对每个编号再打 3 次链查询（N+1）；现已删除，批次列表 `GET /batches` 分页读数据库与读模型，详情再读链

## 鉴权方式（服务端账号 + Bearer token）

| 事实 | 位置 |
|------|------|
| `POST /login` 用户名 + 密码（BCrypt），返回随机 token；`POST /logout` 撤销当前 token | `UserController` / `AuthServiceImpl` |
| 表 `user_account`（角色、绑定链上地址、启用状态）与 `user_session`（token 的 sha256、过期时间、是否撤销） | `db/auth-schema.sql` |
| 拦截器按 `Authorization: Bearer` 查会话，把**账号绑定的地址**写入 `AddressContext`；客户端 `address` 头忽略 | `AddressInterceptor` |
| `WeBaseClient.sendTransaction` 不接受签名地址参数，只用 `AddressContext` | `HttpUtil` |
| 交易先写 `chain_tx` 再发；回执 `status=0x0` 才算成功，超时/中断/5xx 记为 UNKNOWN 不自动重发，`POST /chain-tx/{id}/verify` 查证 | `ChainTxService`，见 [docs/tx-lifecycle.md](../docs/tx-lifecycle.md) |
| 无 token / 过期 / 已撤销 / 账号停用 → HTTP 401；角色不符 → HTTP 403 | `AddressInterceptor` + `@RequireRole` |
| 生产/分销/零售写接口只允许对应角色；用户管理、系统信息写入只允许 ADMIN；合约 `onlyProducer` 等保留为第二道防线 | `TraceController` / `UserController` / `SystemInfoController` |
| 免登录：`/login,/getSystemInfo,/trace/detail/*,/trace/*/file/*`（Ant 精确匹配；详情只含公开字段，文件只能按链上绑定读取） | `application.yml` → `allow.paths` |
| 批次读写按账号与批次的关系鉴权（生产商只碰自己建档的，分销商/零售商只碰指定给自己的），不靠前端隐藏按钮 | `BatchServiceImpl`，见 [docs/business-flow.md](../docs/business-flow.md) |
| 管理员新建账号：先读链上 `isX`，已有角色则不发交易；否则由**管理员地址**签名调用 `addX(address)`，结果未知时账号保持停用、查证后启用。停用账号时撤销其全部 token，并由管理员签名调用 `removeX(address)` | `UserAccountServiceImpl` |
| 新用户的链上地址须是**已在 WeBASE-Front 托管私钥**的地址，由管理员填写（仓库未对接 WeBASE 私钥管理接口） | 用户管理页 |
| 登录限流：按「账号 + IP」统计失败次数，滑动窗口达到阈值即锁定，返回 429 + `Retry-After`；状态仅在当前 JVM 内，多实例需共享计数 | `LoginRateLimiterImpl`，见 [docs/production-boundaries.md](../docs/production-boundaries.md) |

## IoT 数据（定时模拟任务，非真实传感器）

`IotDataSimulatorTask` 固定批次 `SY60202600001~3`，在量程内随机生成温度 15–35℃、湿度 30–90%、光照 0–1000 lux，每 5 分钟调用 `IotSensorDataService.save`。看板读这张 MySQL 表。

写入校验在 `IotSensorDataServiceImpl.save`（`IotSensorValidator`）：缺 `batchId`、空单位、温度/湿度/光照超量程会拒绝。模拟任务本身只生成合法数据，**不是**「所有 MyBatis-Plus 写入入口」的全局闸（`saveBatch` / `mapper.insert` 不走 `save()`）。

## 验证状态（三档）

**第一档：离线验证通过。** `contracts/` 的 `npm test` 为 37/37（v2 + v3，Hardhat 进程内 EVM）；`npm run test:legacy` 是 v1 对比，4 通过、8 个 v3 用例跳过、25 个**预期失败**，退出码 25。后端当前 `mvn -B test` 共 164 项，0 失败、31 项条件跳过（实际执行 133 项）；前端 Node 16 的 `npm ci`、`npm run lint`、`npm run build` 均通过。离线测试使用 Mock WeBASE/H2/FakeKubo，不能替代真实链与生产部署。

**第二档：本地隔离链验证通过。** 旧 v2 链、文件与备份恢复记录见 [产物索引](../docs/artifacts/README.md)。v3 裸合约两次直接绕过后端的非指定分销商交易，在块 42 与 59 均回执 `status=0x16`、revert 为 `Trace: caller is not the designated distributor`；后端按批次分流的六笔交易在块 77–82 回执 `0x0`、`to` 地址匹配 v2/v3，读模型与消费者查询通过。后端这次连接真实四节点 FISCO BCOS 和 WeBASE-Front，**业务库是 H2 内存、文件服务是 FakeKubo**；另有真实 MySQL 的一次旧批次地址迁移小验证，但没有做 v3 + 真实 MySQL/IPFS 完整业务联调。以上为本机手工运行，不等于默认 CI 或生产环境。

**第三档：尚未验证。** 多机构真实联盟链、跨实例登录限流、生产规模 MySQL/存储、真实身份与数据真实性、生产部署与运维恢复目标。v2 旧批次仍不具备指定交接对象的链上强制；配置仅支持一份 v2 和一份 v3 地址，历史多地址自动路由尚未实现。

## CI

| Workflow | 作用 | 注意 |
|----------|------|------|
| `.github/workflows/ci.yml` | 后端 `mvn -B test`、前端 `npm run lint`、合约 `npm test` 与双 ABI 新鲜度检查 | 离线运行，不连 FISCO/IPFS；前端 production build 只在本地验收，默认分支 CI 以 Actions 为准。 |
| `.github/workflows/local-chain-smoke.yml` | 手动触发（`workflow_dispatch`）：ubuntu 上 `USE_OFFICIAL=1 run-all.sh` | 下载只来自官方源并校验哈希；本地用 actionlint、`act -n` 与 WSL 里逐步执行核对过，**尚未在 GitHub Actions 上实际跑过**（此处仅保留该手动工作流的历史验证边界） |

## 仓库历史与命名

这是江西农业大学软件工程专业毕业设计的完整入库；`back-me`、`front-me` 与 `com.qhx` 是当时的课程项目命名，为避免破坏构建与既有说明而保留。首个公开提交是完整项目导入，并非为线上迭代节奏。

## 已知限制

1. v3 批次由链上强制指定交接；v2 旧批次仍只靠后端校验，持角色账户直调 v2 合约可绕过。上线前直接写链的历史批次可能缺少归属记录，业务角色列表里看不到。这些旧文件在重建读模型时凭读链结果直接标记为已绑定（`file_object.bind_source=LEGACY_CHAIN_READ`），和走正常交易绑定的文件（`TX_CONFIRMED`）区分开，批次详情页会标出来
2. 登录已按账号 + IP 限流（见上表），但 token 仍是服务端会话（非 JWT），没有刷新机制；过期会话不会自动清理；没有 MFA、密码修改与找回
3. 地址合法性检查 `0x` + 40 位十六进制，无 EIP-55 checksum
4. Solidity `^0.4.25`，未接 Foundry CI
5. CORS 只在 `WebConfig` 放行 `localhost` / `127.0.0.1`（已去掉 `*` + Credentials；拦截器不再写 CORS 头）
6. 备份/恢复：MySQL 是单次快照（`mysqldump --single-transaction`），没有 binlog 增量，恢复点是最近一次备份完成的时刻；kubo 物理备份要求备份前停止守护进程，短暂不可写；详见 [docs/backup-restore.md](../docs/backup-restore.md)

生产边界、身份认证剩余缺口、以及依赖/合约测试的整改优先级见 [docs/production-boundaries.md](../docs/production-boundaries.md)。
