# 基于 FISCO BCOS 的农产品溯源系统

毕业设计项目：用 **FISCO BCOS 联盟链** 记录农产品「生产者 → 分销商 → 零售商」流转，证书/检测报告等大文件走 **IPFS**（链上只存 CID），消费者扫码查看。后端经 **WeBASE-Front** 调合约，不直连 Java SDK。

本仓库当前定位是 **可核查的测试实践底稿**，不是生产系统，也不是「我部署了一条公链」：所有测试与 CI 声明都能回到仓库文件复现。

## 功能特性（以代码为准）

- **三角色流转**：生产者 `newAgroFood` → 分销商 `addTraceInfoByDistributor` → 零售商 `addTraceInfoByRetailer` → 消费者查询详情
- **联盟链存证**：关键字段写入 `Trace` 合约；角色由合约 `onlyProducer` / `onlyDistributor` / `onlyRetailer` 校验。后端另按账号角色鉴权（见「鉴权方式」）
- **IPFS**：证书、检测报告先上传，链上存 CID（`IPFSServiceImpl` 返回 Base58 hash）
- **IoT 看板**：`IotDataSimulatorTask` **每 5 分钟随机写入** 温湿度/光照到 MySQL，**不是真实传感器**
- **二维码**：前端生成溯源号二维码，扫码进详情（详情接口在鉴权白名单）
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

### IPFS 本地 JAR（非 Maven Central）

后端用 `systemPath` 引用 `back-me/libs/` 下的 IPFS Java HTTP 客户端及其传递依赖，**不是** Maven Central 上的正式坐标，CI/本地编译都依赖这些文件存在。

| 文件 | SHA-256 |
|---|---|
| `ipfs.jar` | `89F3F534FAFCCEBB7BB6853C8EF41DF449CF95F17E8549F284C602859DE891B3` |
| `cid.jar` | `7FA2B60290B6ACDCEEA650EBEB3EE3E43D9B90F037D82C39830397C60D56B362` |
| `multiaddr.jar` | `2B16FEF9280CE61A561100F06229B7903864660912F5D08EF9F4B8CF9A37FD5F` |
| `multibase.jar` | `D459DBB9B2C2CBD19092A19C3BD417F8E3DA9A6D3B0169B413CCC8CACD60E3DA` |
| `multihash.jar` | `42D15EC293C0B0BD51F405B90A138DB06791C5304251AEBB833A61B14D426ABD` |

来源：毕业设计当时纳入的 IPFS Java HTTP client 本地包；本轮只删除了未被 POM 引用的 `junit-4.12.jar` 与 `hamcrest-core-1.3.jar`。不要把上述五个 JAR 当成可替换的 Maven 依赖，除非先做兼容性验证。

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
├── contracts/                     # Solidity 源码（见该目录 README）
│   └── abi/Trace.json             # 从 application.yml 抽出的运行时 ABI
├── docs/                          # 证据、架构、设计说明
└── .github/workflows/             # ci.yml（测试）
```

## 快速开始

### 环境

- JDK 8+（`pom.xml` 编译目标 **14**）、Maven 3.6+
- Node.js 16+、npm
- MySQL 5.7+（IoT 表 + 账号/会话表；无库时后端仍能起，但无法登录，定时任务会写失败）
- FISCO BCOS + WeBASE-Front（链上读写）
- IPFS kubo（默认 `127.0.0.1:5001`）

### 配置（不要提交密钥）

`application.yml` 已脱敏。本地复制：

```bash
cd back-me/src/main/resources
cp application-local.yml.example application-local.yml
```

`application-local.yml` 已在 `.gitignore`，**永远不要 commit**。不要把数据库密码、合约私钥、WeBASE 密钥写进仓库。

### 账号初始化

```bash
# 1. 建账号表、会话表与交易记录表（库名与 MYSQL_DB 一致，默认 mysql；可重复执行）
mysql -u root -p mysql < back-me/src/main/resources/db/auth-schema.sql
mysql -u root -p mysql < back-me/src/main/resources/db/chain-tx-schema.sql

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

cd front-me
npm install
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

用例清单与最近一次本地结果见 [docs/test_report.md](docs/test_report.md)。

### 本地隔离链（真实 FISCO BCOS + WeBASE-Front，可选）

在 WSL Ubuntu 中执行 `bash scripts/local-chain/run-all.sh`：搭 4 节点 FISCO BCOS 2.7.2 与 WeBASE-Front v1.5.5 副本（独立目录与端口，不碰原有链），部署 v2 合约，跑完整三阶段与越权/乱序/重复反例、共识停滞场景，并用后端代码直连同一条链。WeBASE-Front 接口契约与运行记录见 [docs/webase-front-contract.md](docs/webase-front-contract.md) 与 [docs/artifacts/](docs/artifacts/)。Postman 手工集合 11 条：[docs/artifacts/](docs/artifacts/)。**不是 Pytest，不是 60+。**

## 智能合约

源码在 [`contracts/`](contracts/README.md)。`code1.1.3` 原先没有 `.sol`；现有文件来自同课题早期目录 `code1.1/contracts-me`，函数名已与运行时 ABI（`contracts/abi/Trace.json`）对齐。

- **角色**：部署者为 owner，只有 owner 能 `addProducer` / `addDistributor` / `addRetailer` 与 `removeProducer` / `removeDistributor` / `removeRetailer`；owner 自身不持有业务角色，持有者只能 `renounce*` 放弃自己的角色
- **生产**：`newAgroFood(...)`，`onlyProducer`；溯源号非空且不可重复
- **流转**：严格按 生产 → 分销 → 零售 的顺序，每个阶段只能写一次；条目合约 `AgroFoodInfoItem` 只接受 `Trace` 写入，无法绕过角色与阶段检查
- **旧合约**：早期部署（v1）的 setter 无访问控制、角色可自我扩散，这些漏洞在已部署的旧合约上仍然存在；v2 需要重新部署，旧数据不迁移。详见 [contracts/README.md](contracts/README.md)
- **查询**：`getAgroFoodInfo` / `getAgroFoodInfoByDistributor` / `getAgroFoodInfoByRetailer` / `getAgroFoodList`
- **没有** `getAgroFoodListDetail`：`GET /trace/list` 对每个编号再打 3 次链查询（N+1），这是已知限制，不是「只查 3 条」的优化

## 鉴权方式（服务端账号 + Bearer token）

| 事实 | 位置 |
|------|------|
| `POST /login` 用户名 + 密码（BCrypt），返回随机 token；`POST /logout` 撤销当前 token | `UserController` / `AuthServiceImpl` |
| 表 `user_account`（角色、绑定链上地址、启用状态）与 `user_session`（token 的 sha256、过期时间、是否撤销） | `db/auth-schema.sql` |
| 拦截器按 `Authorization: Bearer` 查会话，把**账号绑定的地址**写入 `AddressContext`；客户端 `address` 头忽略 | `AddressInterceptor` |
| `WeBaseClient.sendTransaction` 不接受签名地址参数，只用 `AddressContext` | `HttpUtil` |
| 交易先写 `chain_tx` 再发；回执 `status=0x0` 才算成功，超时/中断/5xx 记为 UNKNOWN 不自动重发，`POST /chain-tx/{id}/verify` 查证 | `ChainTxService`，见 [docs/tx-lifecycle.md](docs/tx-lifecycle.md) |
| 无 token / 过期 / 已撤销 / 账号停用 → HTTP 401；角色不符 → HTTP 403 | `AddressInterceptor` + `@RequireRole` |
| 生产/分销/零售写接口只允许对应角色；用户管理、系统信息写入只允许 ADMIN；合约 `onlyProducer` 等保留为第二道防线 | `TraceController` / `UserController` / `SystemInfoController` |
| 免登录：`/login,/getSystemInfo,/trace/detail/**`（Ant 精确匹配） | `application.yml` → `allow.paths` |
| 管理员新建账号时由**管理员地址**签名调用 `addX(address)`；停用账号时撤销其全部 token，并由管理员签名调用 `removeX(address)` | `UserAccountServiceImpl` |
| 新用户的链上地址须是**已在 WeBASE-Front 托管私钥**的地址，由管理员填写（仓库未对接 WeBASE 私钥管理接口） | 用户管理页 |

## IoT 数据（定时模拟任务，非真实传感器）

`IotDataSimulatorTask` 固定批次 `SY60202600001~3`，在量程内随机生成温度 15–35℃、湿度 30–90%、光照 0–1000 lux，每 5 分钟调用 `IotSensorDataService.save`。看板读这张 MySQL 表。

写入校验在 `IotSensorDataServiceImpl.save`（`IotSensorValidator`）：缺 `batchId`、空单位、温度/湿度/光照超量程会拒绝。模拟任务本身只生成合法数据，**不是**「所有 MyBatis-Plus 写入入口」的全局闸（`saveBatch` / `mapper.insert` 不走 `save()`）。

## CI

| Workflow | 作用 | 注意 |
|----------|------|------|
| `.github/workflows/ci.yml` | `mvn -B test` + `npm run lint` | 离线 `mvn -B test` + `npm run lint`；不连 FISCO/IPFS。默认分支 CI 以 Actions 为准。 |

## 仓库历史与命名

这是江西农业大学软件工程专业毕业设计的完整入库；`back-me`、`front-me` 与 `com.qhx` 是当时的课程项目命名，为避免破坏构建与既有说明而保留。首个公开提交是完整项目导入，不应被理解为线上迭代节奏。

## 已知限制

1. `/trace/list` N+1 链查询（合约没有批量详情接口）
2. 登录没有限流/锁定，token 为服务端会话（非 JWT），没有刷新机制；过期会话不会自动清理
3. 地址合法性检查 `0x` + 40 位十六进制，无 EIP-55 checksum
4. Solidity `^0.4.25`，未接 Foundry CI
5. CORS 只在 `WebConfig` 放行 `localhost` / `127.0.0.1`（已去掉 `*` + Credentials；拦截器不再写 CORS 头）

生产边界、身份认证剩余缺口、以及依赖/合约测试的整改优先级见 [docs/production-boundaries.md](docs/production-boundaries.md)。
