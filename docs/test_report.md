# 测试报告

日期：2026-09-28（本地）

## 命令

```bash
cd back-me && MYSQL_PASSWORD="" WEBASE_FRONT_URL="http://127.0.0.1:9/WeBASE-Front" CONTRACT_ADDRESS="0x0" CONTRACT_OWNER="0x0" mvn -B test
cd front-me && npm ci && npm run lint   # Node 16.20.2，与 CI 一致
```

环境：Windows 11，Temurin JDK 21.0.12，Maven 3.9.16。不连 FISCO、WeBASE、MySQL、IPFS：账号表与交易记录表用 H2（MySQL 模式）内存库，WeBASE-Front 用本地 JDK HttpServer 替身（记录签名地址与函数名；响应结构按真实链核验结果构造，见 [webase-front-contract.md](webase-front-contract.md)）；响应中途断连用原始 socket 替身。

## 结果

| 项 | 结果 |
|----|------|
| `mvn -B test` | **Tests run: 95, Failures: 0, Errors: 0, Skipped: 1**（跳过的是需要真实链的 `RealChainSmokeTest`） |
| `npm run lint` | **No lint errors found** |
| `npm run build` | 构建成功（5 条既有 no-console 警告） |

## 用例清单（95，其中 1 条默认跳过）

| 类 | 条数 | 覆盖 |
|----|------|------|
| `AuthIntegrationTest` | 14 | 伪造 address 头（无 token 401 / 有 token 仍用绑定地址签名）、请求体夹带地址、无/过期/登出撤销 token 401、token 只存 sha256、错误密码与不存在用户、生产商调分销 403、非 ADMIN 管理用户 403、公开注册已移除、管理员建号签名地址是管理员、停用后 token 失效且发 `removeX`、公开接口免登录 |
| `AdminBootstrapTest` | 6 | 缺初始密码跳过且不抛异常、密码过短、地址非法、已有管理员不覆盖、正常创建只存哈希 |
| `AddressInterceptorTest` | 11 | 白名单精确匹配（防 `;` 与前缀绕过）、OPTIONS、缺/非 Bearer/无效 token 401、伪造 address 头、上下文写绑定地址、角色不符 403、不写 CORS 头、ThreadLocal 清理 |
| `UserAddressUtilTest` | 4 | 长度、`0x` 前缀、十六进制（含全角数字）校验 |
| `TracePayloadParserTest` | 5 | 生产须 8 元组、分销/零售、缺失、组装详情 |
| `TraceControllerTest` | 7 | 详情、无此号、列表 N+1、生产/分销/零售经 `ChainTxService.submitStage` 上链、链上拒绝异常上抛（Mock 端口） |
| `HttpUtilSendTransactionTest` | 9 | 成功、revert、读超时且只发一次、响应体中途断连、坏 JSON、5xx、连接被拒=未发出、无会话地址拒发、按哈希查回执 |
| `WeBaseResponsesTest` | 15 | 真实链响应体分类：成功、revert（HTTP 200）、`Error(string)` 解码、回执超时 50001、422 `201015`/`201151`/未知码、5xx、坏 JSON、查回执 200/500、revert → 409/404/403 等 |
| `ChainTxLifecycleIntegrationTest` | 17 | 发请求前记录已落库、各类结果对应的状态与 HTTP 码、UNKNOWN 后重复提交 409、查证（按哈希 / 读链：本人写入、他人写入、同人不同数据、未写入后显式重提、不能冒领、非本人 403） |
| `RealChainSmokeTest` | 1（默认跳过） | 设置 `E2E_SMOKE_FILE` 时直连本地隔离链；2026-09-28 在 WSL 中运行通过，记录见 `docs/artifacts/local-chain-java-test-2026-09-28.txt` |
| `IotDataSimulatorTaskTest` | 1 | 3 批次 × 3 指标字段与量程 |
| `IotSensorValidatorTest` | 5 | 缺 batchId、空单位、温度/湿度超量程 |

Postman 集合 11 条：`docs/artifacts/postman_collection.json`（无导出 run）。

本地隔离链冒烟（真实 FISCO BCOS 2.7.2 + WeBASE-Front v1.5.5，27 步全部符合预期）：`docs/artifacts/local-chain-smoke-2026-09-28.md`。
