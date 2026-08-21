# 架构说明

## 数据流

1. 前端 Vue 把 `localStorage.userInfo.address` 放进请求头 `address`（`front-me/src/utils/request.js`）
2. `AddressInterceptor` 校验白名单或地址格式，写入 `AddressContext`
3. Controller 组合约参数，经 `WeBaseClient` / `HttpUtil` POST 到 WeBASE-Front `/trans/handle`
4. WeBASE 用请求里的 `user` 作为链上 `msg.sender` 调 `Trace`
5. 证书/报告：前端上传 → `IPFSServiceImpl` → CID 字符串进合约字段
6. IoT：`IotDataSimulatorTask` 每 5 分钟生成量程内数据，经 `IotSensorDataService.save` 写 MySQL（此处做字段校验），看板读 `/api/iot/data`，**不上链**

## 链上 / 链下

| 放链上 | 放链下 |
|--------|--------|
| 生产/分销/零售字段、角色、溯源号列表 | 登录会话（其实没有 token）、IoT 时序、IPFS 文件本体 |
| CID | MySQL `iot_sensor_data` |

## 为什么 WeBASE 而不是 Java SDK

课程/毕业设计环境已经有 WeBASE-Front 控制台。后端只当 HTTP 网关，合约 ABI 配在 `application.yml`。代价：

- 强依赖 WeBASE JSON 形状（例如 `getAgroFoodList` 外包一层数组）
- 难做本地链测试 → 用 `WeBaseClient` Mock
- `/trace/list` 无法一次拉详情（合约没有批量接口）

不是「不会 SDK」，是环境约束下的集成选择。

## 鉴权分层

- 合约：`onlyProducer` 等，已有角色可再添加同类角色
- Java 管理接口：`AddressContext` 必须等于 `contract.owner`
- Java 写接口：只要地址格式合法，角色对不对交给链

没有 JWT。

## CORS

只在 `WebConfig`：放行 `http://localhost:*` / `http://127.0.0.1:*`，允许 Credentials。拦截器不再写 CORS 头。
