# 测试报告

日期：2026-09-28（本地）

## 命令

```bash
cd back-me && MYSQL_PASSWORD="" WEBASE_FRONT_URL="http://127.0.0.1:9/WeBASE-Front" CONTRACT_ADDRESS="0x0" CONTRACT_OWNER="0x0" mvn -B test
cd front-me && npm ci && npm run lint   # Node 16.20.2，与 CI 一致
```

环境：Windows 11，Temurin JDK 21.0.12，Maven 3.9.16。不连 FISCO、WeBASE、MySQL、IPFS：账号表用 H2（MySQL 模式）内存库，WeBASE-Front 用本地 JDK HttpServer 替身（记录签名地址与函数名）。

## 结果

| 项 | 结果 |
|----|------|
| `mvn -B test` | **Tests run: 57, Failures: 0, Errors: 0, Skipped: 0** |
| `npm run lint` | **No lint errors found** |
| `npm run build` | 构建成功（5 条既有 no-console 警告） |

## 用例清单（57）

| 类 | 条数 | 覆盖 |
|----|------|------|
| `AuthIntegrationTest` | 14 | 伪造 address 头（无 token 401 / 有 token 仍用绑定地址签名）、请求体夹带地址、无/过期/登出撤销 token 401、token 只存 sha256、错误密码与不存在用户、生产商调分销 403、非 ADMIN 管理用户 403、公开注册已移除、管理员建号签名地址是管理员、停用后 token 失效且发 `removeX`、公开接口免登录 |
| `AdminBootstrapTest` | 6 | 缺初始密码跳过且不抛异常、密码过短、地址非法、已有管理员不覆盖、正常创建只存哈希 |
| `AddressInterceptorTest` | 11 | 白名单精确匹配（防 `;` 与前缀绕过）、OPTIONS、缺/非 Bearer/无效 token 401、伪造 address 头、上下文写绑定地址、角色不符 403、不写 CORS 头、ThreadLocal 清理 |
| `UserAddressUtilTest` | 4 | 长度、`0x` 前缀、十六进制（含全角数字）校验 |
| `TracePayloadParserTest` | 5 | 生产须 8 元组、分销/零售、缺失、组装详情 |
| `TraceControllerTest` | 7 | 详情、无此号、列表 N+1、生产/分销/零售上链、角色不足（Mock 端口） |
| `HttpUtilSendTransactionTest` | 4 | 签名地址取自会话、无会话地址拒发、链上失败保留 mes、非法 JSON |
| `IotDataSimulatorTaskTest` | 1 | 3 批次 × 3 指标字段与量程 |
| `IotSensorValidatorTest` | 5 | 缺 batchId、空单位、温度/湿度超量程 |

Postman 集合 11 条：`docs/artifacts/postman_collection.json`（无导出 run）。

禁止把 57 写成 60+。
