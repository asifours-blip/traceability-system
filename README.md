# 基于 FISCO BCOS 的农产品溯源系统

毕业设计项目：用 **FISCO BCOS 联盟链** 记录农产品「生产者 → 分销商 → 零售商」流转，证书/检测报告等大文件走 **IPFS**（链上只存 CID），消费者扫码查看。后端经 **WeBASE-Front** 调合约，不直连 Java SDK。

本仓库当前定位是 **可核查的测试实践底稿**，不是生产系统，也不是「我部署了一条公链」：所有测试与 CI 声明都能回到仓库文件复现。

## 功能特性（以代码为准）

- **三角色流转**：生产者 `newAgroFood` → 分销商 `addTraceInfoByDistributor` → 零售商 `addTraceInfoByRetailer` → 消费者查询详情
- **联盟链存证**：关键字段写入 `Trace` 合约；角色由合约 `onlyProducer` / `onlyDistributor` / `onlyRetailer` 校验。后端 `/add/user` 额外要求请求头地址等于 `contract.owner`
- **IPFS**：证书、检测报告先上传，链上存 CID（`IPFSServiceImpl` 返回 Base58 hash）
- **IoT 看板**：`IotDataSimulatorTask` **每 5 分钟随机写入** 温湿度/光照到 MySQL，**不是真实传感器**
- **二维码**：前端生成溯源号二维码，扫码进详情（详情接口在鉴权白名单）
- **地址头鉴权**：请求头 `address`（`AddressInterceptor` + 前端 `src/utils/request.js`）。**不是 JWT**，仓库里没有 token 签发
- **离线测试门禁**：Mock `WeBaseClient`，Maven 单测不连链；前端 ESLint
- **代码评审 workflow**：`.github/workflows/code-review.yml` 仅手动触发，不是编译/测试门禁

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 2.6.13 · MyBatis-Plus 3.5.3 · MySQL · Springfox 3.0 · Hutool · FastJSON |
| 前端 | Vue 2.6 · Element UI 2.15 · Vuex · Vue Router · ECharts 6 · qrcode · axios |
| 区块链 | FISCO BCOS · WeBASE-Front · Solidity `^0.4.25` 合约 `Trace` |
| 存储 | IPFS（kubo）；链下表 `iot_sensor_data` |
| 工具链 | Maven（编译目标 14，CI 用 JDK 21）· Vue CLI 5 · GitHub Actions（`ci.yml` + `code-review.yml`） |

## 系统架构

```
┌──────────────────────────────────┐
│   前端 front-me (Vue 2.6)        │
│  生产者/分销商/零售商/消费者/IoT  │
└───────────────┬──────────────────┘
                │ HTTP + 请求头 address
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
│       ├── interceptor/           # AddressInterceptor（地址头，不是 JWT）
│       ├── task/IotDataSimulatorTask.java
│       └── resources/
│           ├── application.yml            # 模板 + 内嵌 ABI
│           └── application-local.yml.example
├── front-me/                      # Vue 2.6
├── contracts/                     # Solidity 源码（见该目录 README）
│   └── abi/Trace.json             # 从 application.yml 抽出的运行时 ABI
├── docs/                          # 证据、架构、设计说明
└── .github/workflows/             # ci.yml（测试）+ code-review.yml（手动评审）
```

## 快速开始

### 环境

- JDK 8+（`pom.xml` 编译目标 **14**）、Maven 3.6+
- Node.js 16+、npm
- MySQL 5.7+（仅 IoT 表；无库时后端仍能起，定时任务会写失败）
- FISCO BCOS + WeBASE-Front（链上读写）
- IPFS kubo（默认 `127.0.0.1:5001`）

### 配置（不要提交密钥）

`application.yml` 已脱敏。本地复制：

```bash
cd back-me/src/main/resources
cp application-local.yml.example application-local.yml
```

`application-local.yml` 已在 `.gitignore`，**永远不要 commit**。不要把数据库密码、合约私钥、WeBASE 密钥写进仓库。

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

用例清单与最近一次本地结果见 [docs/test_report.md](docs/test_report.md)。Postman 手工集合 11 条：[docs/artifacts/](docs/artifacts/)。**不是 Pytest，不是 60+。**

## 智能合约

源码在 [`contracts/`](contracts/README.md)。`code1.1.3` 原先没有 `.sol`；现有文件来自同课题早期目录 `code1.1/contracts-me`，函数名已与运行时 ABI（`contracts/abi/Trace.json`）对齐。

- **角色**：`addProducer` / `addDistributor` / `addRetailer` 在合约里是 `onlyProducer` 等（已有该角色的人可再添加），不是独立的 `onlyOwner` modifier。部署时构造函数把 `msg.sender` 加进三个角色
- **生产**：`newAgroFood(...)`，`onlyProducer`；溯源号不可重复
- **流转**：分销/零售追加信息；溯源号必须已存在
- **查询**：`getAgroFoodInfo` / `getAgroFoodInfoByDistributor` / `getAgroFoodInfoByRetailer` / `getAgroFoodList`
- **没有** `getAgroFoodListDetail`：`GET /trace/list` 对每个编号再打 3 次链查询（N+1），这是已知限制，不是「只查 3 条」的优化

## 鉴权方式（address 请求头，非 JWT）

| 事实 | 位置 |
|------|------|
| 写接口读请求头 `address` | `AddressInterceptor` |
| 空/非法地址拒绝，HTTP `401`，body `code=401` | 同上 |
| 白名单：`/login,/register,/getContractOwner,/getSystemInfo,/trace/detail` | `application.yml` → `allow.paths` |
| 前端把 `userInfo.address` 塞进 header | `front-me/src/utils/request.js` |
| 登录只调合约 `isProducer` 等，**不签发 token** | `UserController.login` |

## IoT 数据（定时模拟任务，非真实传感器）

`IotDataSimulatorTask` 固定批次 `SY60202600001~3`，在量程内随机生成温度 15–35℃、湿度 30–90%、光照 0–1000 lux，每 5 分钟调用 `IotSensorDataService.save`。看板读这张 MySQL 表。

写入校验在 `IotSensorDataServiceImpl.save`（`IotSensorValidator`）：缺 `batchId`、空单位、温度/湿度/光照超量程会拒绝。模拟任务本身只生成合法数据，**不是**「所有 MyBatis-Plus 写入入口」的全局闸（`saveBatch` / `mapper.insert` 不走 `save()`）。

## CI

| Workflow | 作用 | 注意 |
|----------|------|------|
| `.github/workflows/ci.yml` | `mvn -B test` + `npm run lint` | 不读密钥、不连 FISCO/IPFS；**GitHub 上是否绿要等 push 后的 Actions run** |
| `.github/workflows/code-review.yml` | 仅 `workflow_dispatch` 手动评审 | 非门禁；自动触发已停用 |

## 已知限制

1. `/trace/list` N+1 链查询（合约没有批量详情接口）
2. 空/非法 `address` 已返回 HTTP 401 + body `code=401`；身份仍只依赖可伪造的地址头，不是 JWT
3. 地址合法性只检查 `0x` + 长度 42，无 checksum、无 EIP-55
4. Solidity `^0.4.25`，未接 Foundry CI
5. CORS 只在 `WebConfig` 放行 `localhost` / `127.0.0.1`（已去掉 `*` + Credentials；拦截器不再写 CORS 头）

生产边界、为何 `address` 请求头不能作为生产鉴权、以及身份认证/依赖/合约测试的整改优先级见 [docs/production-boundaries.md](docs/production-boundaries.md)。
