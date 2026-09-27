# 架构说明

## 数据流

1. 前端用用户名 + 密码调 `POST /login`，拿到服务端签发的 token，之后请求带 `Authorization: Bearer <token>`（`front-me/src/utils/request.js`）
2. `AddressInterceptor` 对非白名单路径按 token 的 sha256 查 `user_session`，再查 `user_account`，把账号**绑定的链上地址**写入 `AddressContext`，并按 `@RequireRole` 检查角色；客户端 `address` 头不参与
3. Controller 组合约参数，经 `WeBaseClient` / `HttpUtil` POST 到 WeBASE-Front `/trans/handle`；`sendTransaction` 只从 `AddressContext` 取签名地址
4. WeBASE 用请求里的 `user` 作为链上 `msg.sender` 调 `Trace`（私钥托管在 WeBASE-Front）
5. 证书/报告：前端上传 → `IPFSServiceImpl` → CID 字符串进合约字段
6. IoT：`IotDataSimulatorTask` 每 5 分钟生成量程内数据，经 `IotSensorDataService.save` 写 MySQL（此处做字段校验），看板读 `/api/iot/data`，**不上链**

## 链上 / 链下

| 放链上 | 放链下 |
|--------|--------|
| 生产/分销/零售字段、角色、溯源号列表 | 账号、登录会话、IoT 时序、IPFS 文件本体 |
| CID | MySQL `user_account` / `user_session` / `iot_sensor_data` |

## 为什么 WeBASE 而不是 Java SDK

课程/毕业设计环境已经有 WeBASE-Front 控制台。后端只当 HTTP 网关，合约 ABI 配在 `application.yml`。代价：

- 强依赖 WeBASE JSON 形状（例如 `getAgroFoodList` 外包一层数组）
- 难做本地链测试 → 用 `WeBaseClient` Mock
- `/trace/list` 无法一次拉详情（合约没有批量接口）

不是「不会 SDK」，是环境约束下的集成选择。

## 鉴权分层

- 身份：服务端账号表 + 不透明随机 token（服务端会话，不是 JWT）；登出或停用账号即撤销
- Java 接口：`@RequireRole` 按账号角色放行，不符返回 403；用户管理与系统信息写入只允许 ADMIN
- 签名地址：只来自当前账号绑定的地址；管理员增删业务角色用管理员自己的地址签名，不再冒充用户签 `renounceX`
- 合约：`onlyProducer` 等保留，作为第二道防线；增删角色的 onlyOwner 约束由合约线实现

## CORS

只在 `WebConfig`：放行 `http://localhost:*` / `http://127.0.0.1:*`，允许 Credentials。拦截器不再写 CORS 头。
