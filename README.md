# 基于 FISCO BCOS 的农产品溯源系统

一个以**联盟链为核心**的农产品全链路溯源平台：覆盖「生产者 → 分销商 → 零售商 → 消费者」四个环节，关键溯源信息上链存证、大文件（证书/检测报告）经 IPFS 去中心化存储，并接入 IoT 传感器数据采集，消费者扫码即可查看不可篡改的完整溯源记录。

## 功能特性

- **四角色全链路溯源**：生产者录入产地/批次/证书 → 分销商录入仓储/运输/检测报告 → 零售商录入销售/保质期 → 消费者扫码查询全流程
- **区块链存证**：基于 FISCO BCOS 联盟链，溯源数据写入智能合约，链上可验、不可篡改
- **IPFS 去中心化存储**：证书、检测报告等大文件上链前先存 IPFS（kubo），链上只存 CID，兼顾可信与成本
- **IoT 数据采集**：采集温湿度等传感器数据并在看板可视化（ECharts）
- **角色权限管理**：合约级角色控制（Producer / Distributor / Retailer），由合约 Owner 分配与撤销
- **二维码溯源**：一键生成溯源二维码，扫码直达溯源详情
- **JWT 鉴权**：后端拦截器统一鉴权，白名单接口（登录/注册/溯源详情等）放行
- **AI 代码评审**：PR 自动触发 Claude Code（经 DeepSeek 兼容接口）做中文代码审查

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 2.6.13 · MyBatis-Plus 3.5.3 · MySQL · Springfox Swagger 3.0 · Hutool · FastJSON |
| 前端 | Vue 2.6 · Element UI 2.15 · Vuex · Vue Router · ECharts 6 · qrcode · axios |
| 区块链 | FISCO BCOS 联盟链 · WeBASE-Front（合约调用网关） · Solidity 智能合约 `Trace` |
| 去中心化存储 | IPFS（kubo） |
| 工具链 | Maven · Vue CLI 5 · GitHub Actions（AI PR Review） |

## 系统架构

```
┌──────────────────────────────────┐
│   前端 front-me (Vue 2.6 + EUI)  │
│  生产者/分销商/零售商/消费者/IoT  │
└───────────────┬──────────────────┘
                │ HTTP / JWT
┌───────────────▼──────────────────┐
│  后端 back-me (Spring Boot 8010) │
│  Controller → Service → Mapper    │
│  JWT 拦截器 · 全局异常 · Swagger  │
└───┬──────────┬──────────┬────────┘
    │          │          │
┌───▼───┐ ┌───▼────────┐ ┌▼──────────┐
│ MySQL │ │ WeBASE-Front│ │  IPFS     │
│用户/IoT│ │→FISCO BCOS │ │ 大文件存证 │
└───────┘ │  链上溯源存证│ └───────────┘
          └─────────────┘
```

## 项目结构

```
code1.1.3/
├── back-me/                       # 后端 Spring Boot
│   ├── pom.xml
│   ├── libs/                      # IPFS 相关 jar（ipfs/multihash/multibase/cid…）
│   └── src/main/java/com/qhx/back/
│       ├── controller/            # Block/IPFS/IotSensorData/Owner/SystemInfo/Trace/User
│       ├── service/               # 业务逻辑 + impl
│       ├── mapper/                # MyBatis-Plus 数据访问
│       ├── model/                 # 实体
│       ├── interceptor/           # JWT 鉴权拦截器
│       ├── handler/  exception/   # 全局异常处理
│       ├── config/  common/  util/ context/ enums/ task/
│       └── resources/
│           ├── application.yml            # 配置模板（敏感字段占位符）
│           └── application-local.yml.example  # 本地真实配置示例
├── front-me/                      # 前端 Vue 2.6
│   ├── package.json
│   └── src/
│       ├── views/front/           # Producer/Distributor/Retailer/Trace/QrcodeTrace/IotDashboard…
│       ├── views/admin/           # Block/Role/Setting 管理后台
│       ├── apis/                  # block/iot/ipfs/owner/systemInfo/trace/user 接口封装
│       ├── router/  store/  components/  utils/  mixins/
└── .github/workflows/ai-review.yml   # AI PR 代码评审
```

## 快速开始

### 环境依赖

- **JDK** 8+（编译目标 14）、**Maven** 3.6+
- **Node.js** 16+、npm
- **MySQL** 5.7+
- **FISCO BCOS** 联盟链节点 + **WeBASE-Front**（合约调用网关）
- **IPFS（kubo）** 守护进程（默认 `127.0.0.1:5001`）

### 配置

`application.yml` 为配置模板（敏感字段用占位符，已提交）；本地真实配置放在 `application-local.yml`（已 gitignore，不提交）：

```bash
cd back-me/src/main/resources
cp application-local.yml.example application-local.yml   # 复制模板并填入真实值
```

需配置项：

- `spring.datasource` —— MySQL 连接（IoT 数据表 `iot_sensor_data`）
- `webase-front.url` —— WeBASE-Front 地址（用于调用链上合约）
- `contract.address` / `contract.owner` —— 已部署的 `Trace` 合约地址与 owner
- `ipfs.host` / `ipfs.port` —— IPFS 节点地址

### 后端

```bash
cd back-me
mvn spring-boot:run     # 启动，端口 8010
# Swagger 文档：http://localhost:8010/swagger-ui/index.html
```

### 前端

```bash
cd front-me
npm install
npm run serve           # 开发服务器
npm run build           # 生产构建
npm run lint            # ESLint
```

## 智能合约

Solidity 合约 `Trace` 实现链上溯源与角色管理：

- **角色管理**：`addProducer` / `addDistributor` / `addRetailer`（由合约 Owner 分配），`renounceXxx` 主动退出，`isXxx` 角色校验
- **生产上链**：`newAgroFood(traceNumber, 企业/产品/产地/品种/批次/证书/生产时间)`
- **流转上链**：`addTraceInfoByDistributor(...)` 分销环节、`addTraceInfoByRetailer(...)` 零售环节
- **链上查询**：`getAgroFoodInfo` / `getAgroFoodInfoByDistributor` / `getAgroFoodInfoByRetailer` / `getAgroFoodList`
- **事件**：`ProducerAdded/Removed`、`DistributorAdded/Removed`、`RetailerAdded/Removed`

## CI

`.github/workflows/ai-review.yml`：PR 打开/更新时，在 Ubuntu runner 安装 Claude Code，经 DeepSeek 兼容接口对 PR diff 做安全与风格的中文评审，结果以评论回写到 PR。
