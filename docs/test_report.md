# 测试报告

日期：2026-09-28（本地）

## 命令

```bash
cd back-me && MYSQL_PASSWORD="" WEBASE_FRONT_URL="http://127.0.0.1:9/WeBASE-Front" CONTRACT_ADDRESS="0x0" CONTRACT_OWNER="0x0" mvn -B test
cd front-me && npm ci && npm run lint && npm run build   # Node 16.20.2，与 CI 一致
```

环境：Windows 11，Temurin JDK 21.0.12，Maven 3.9.16。不连 FISCO、WeBASE、MySQL、IPFS：账号表与交易记录表用 H2（MySQL 模式）内存库，WeBASE-Front 用本地 JDK HttpServer 替身（记录签名地址与函数名；响应结构按真实链核验结果构造，见 [webase-front-contract.md](webase-front-contract.md)）；响应中途断连用原始 socket 替身。

## 结果

| 项 | 结果 |
|----|------|
| `mvn -B test` | **Tests run: 112, Failures: 0, Errors: 0, Skipped: 13**（跳过的：需要真实链的 `RealChainSmokeTest`、`RealChainBusinessFlowTest`，需要真实 MySQL 的 `BusinessFlowMySqlSmokeTest` 11 条） |
| `BusinessFlowMySqlSmokeTest`（`MYSQL_IT_URL` 指向 MySQL 8.0.46 容器） | **11 / 11 通过**，连续两次；schema 在真实 MySQL 上重复执行无误，见 `artifacts/mysql-business-flow-2026-09-28.txt` |
| 真实链（WSL，`run-all.sh` 官方源模式） | 冒烟 27 步符合预期；`RealChainSmokeTest`、`RealChainBusinessFlowTest` 通过，见 `artifacts/local-chain-java-test-2026-09-28-112202.txt` |
| `npm run lint` | **No lint errors found** |
| `npm run build` | 构建成功（4 条既有 no-console 警告与体积警告） |

## 用例清单（112，其中 13 条默认跳过）

| 类 | 条数 | 覆盖 |
|----|------|------|
| `AuthIntegrationTest` | 14 | 伪造 address 头（无 token 401 / 有 token 仍用绑定地址签名）、请求体夹带地址、无/过期/登出撤销 token 401、token 只存 sha256、错误密码与不存在用户、生产商调分销 403、非 ADMIN 管理用户 403、公开注册已移除、管理员建号签名地址是管理员、停用后 token 失效且发 `removeX`、公开接口免登录 |
| `AdminBootstrapTest` | 6 | 缺初始密码跳过且不抛异常、密码过短、地址非法、已有管理员不覆盖、正常创建只存哈希 |
| `AddressInterceptorTest` | 11 | 白名单精确匹配（防 `;` 与前缀绕过）、OPTIONS、缺/非 Bearer/无效 token 401、伪造 address 头、上下文写绑定地址、角色不符 403、不写 CORS 头、ThreadLocal 清理 |
| `UserAddressUtilTest` | 4 | 长度、`0x` 前缀、十六进制（含全角数字）校验 |
| `BusinessFlowIntegrationTest` | 11 | 见 [business-flow.md](business-flow.md)「测试」：主流程、非指定分销商/零售商 403、他人批次不可见不可改、交接变更与历史、字段 400、未知状态重复提交 409、更正只追加与写入者限制、未绑定 CID 不能公开读取、建号跳过交易、授权待确认后查证启用 |
| `BusinessFlowMySqlSmokeTest` | 11（默认跳过） | 同上 11 条，跑在真实 MySQL 上 |
| `TraceValidatorTest` | 6 | 溯源号格式、正整数、严格日期、不早于上一阶段、不超过上一阶段数量、字段清单与公开范围 |
| `HttpUtilSendTransactionTest` | 9 | 成功、revert、读超时且只发一次、响应体中途断连、坏 JSON、5xx、连接被拒=未发出、无会话地址拒发、按哈希查回执 |
| `WeBaseResponsesTest` | 15 | 真实链响应体分类：成功、revert（HTTP 200）、`Error(string)` 解码、回执超时 50001、422 `201015`/`201151`/未知码、5xx、坏 JSON、查回执 200/500、revert → 409/404/403 等 |
| `ChainTxLifecycleIntegrationTest` | 17 | 发请求前记录已落库、各类结果对应的状态与 HTTP 码、UNKNOWN 后重复提交 409、查证（按哈希 / 读链：本人写入、他人写入、同人不同数据、未写入后显式重提、不能冒领、非本人 403） |
| `RealChainSmokeTest` | 1（默认跳过） | 设置 `E2E_SMOKE_FILE` 时直连本地隔离链；2026-09-28 在 WSL 中运行通过，记录见 `docs/artifacts/local-chain-java-test-2026-09-28*.txt` |
| `RealChainBusinessFlowTest` | 1（默认跳过） | 完整 Spring 后端 + 本地隔离链的业务闭环（IPFS 内存替身）；记录同上 |
| `IotDataSimulatorTaskTest` | 1 | 3 批次 × 3 指标字段与量程 |
| `IotSensorValidatorTest` | 5 | 缺 batchId、空单位、温度/湿度超量程 |

Postman 集合 11 条：`docs/artifacts/postman_collection.json`（无导出 run）。

本地隔离链冒烟（真实 FISCO BCOS 2.7.2 + WeBASE-Front v1.5.5，27 步全部符合预期）：`docs/artifacts/local-chain-smoke-2026-09-28.md`。

原 `TracePayloadParserTest`（5）、`TraceControllerTest`（7）随 `TracePayloadParser` 与 `/trace/list` 等旧接口一起删除，其覆盖由 `ChainTraceReader` 相关的集成用例承担。
