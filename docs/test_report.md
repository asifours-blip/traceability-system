# 测试报告

日期：2026-08-22（本地）

## 命令

```bash
cd back-me && mvn -B test
cd front-me && npm run lint
```

环境：Windows 11，Temurin JDK 21.0.12，Maven 3.9.16。不连 FISCO、WeBASE、MySQL、IPFS。

## 结果

| 项 | 结果 |
|----|------|
| `mvn -B test` | **Tests run: 31, Failures: 0, Errors: 0, Skipped: 0** |
| `npm run lint` | **No lint errors found** |
| GitHub Actions `ci.yml` | 文件已加，**尚未 push，没有远程绿勾** |

## 用例清单（31）

| 类 | 条数 | 覆盖 |
|----|------|------|
| `UserAddressUtilTest` | 3 | 合法/非法地址；不校验 hex 的现状 |
| `AddressInterceptorTest` | 7 | 白名单、OPTIONS、空/非法 address、合法放行、不写 CORS 头、ThreadLocal |
| `TracePayloadParserTest` | 5 | 生产须 8 元组、分销/零售、缺失、组装详情 |
| `TraceControllerTest` | 7 | 详情、无此号、列表 N+1、生产/分销/零售上链、角色不足（Mock 端口） |
| `HttpUtilSendTransactionTest` | 3 | 真走 `sendTransaction`：成功、链上失败保留 mes、非法 JSON |
| `IotDataSimulatorTaskTest` | 1 | 3 批次 × 3 指标字段与量程 |
| `IotSensorValidatorTest` | 5 | 缺 batchId、空单位、温度/湿度超量程 |

Postman 集合 11 条：`docs/artifacts/postman_collection.json`（无导出 run）。

禁止把 31 写成 60+。
