# 运行产物索引

本目录保留原始成功与失败记录；“通过”只表示该次记录中列出的检查通过，不能外推到生产或另一条链。`.md` 摘要与同名 `.json` 明细属于同一次运行，不重复计数。新增运行记录应补上时间、环境、结果及失败原因。

| 文件 | 运行 / 用途 | 原始结果与边界 |
|------|-------------|----------------|
| [postman_collection.json](postman_collection.json) | 11 条手工接口请求模板 | **模板，非运行结果**；本目录没有 Newman/Postman 导出的执行记录 |
| [webase-string-array-2026-09-28.json](webase-string-array-2026-09-28.json) | WeBASE-Front v1.5.5 的 `getAgroFoodList` 响应形态探针 | **通过**；核对 `string[]` 原始编码与解析 |
| [local-chain-smoke-2026-09-28.md](local-chain-smoke-2026-09-28.md) | 09-28 01:46 本机隔离链 v2 冒烟摘要 | **通过**，27/27 步符合预期；含成功、revert 与共识停滞后的查证 |
| [local-chain-smoke-2026-09-28.json](local-chain-smoke-2026-09-28.json) | 上一行同次运行的机器可读明细 | **通过**；不能另计一轮 |
| [local-chain-java-test-2026-09-28.txt](local-chain-java-test-2026-09-28.txt) | 09-28 01:47 后端直连隔离链的 `RealChainSmokeTest` | **通过**，1/1；重复生产回执 `0x16`，无私钥用户被拒 |
| [local-chain-java-test-2026-09-28-110615.txt](local-chain-java-test-2026-09-28-110615.txt) | 09-28 11:06 隔离链上的 Java 冒烟 + 业务流，文件用内存替身 | **通过**，2/2；非指定分销商仅由后端返回 403，属于 v2 边界 |
| [local-chain-official-sources-2026-09-28.txt](local-chain-official-sources-2026-09-28.txt) | 09-28 11:20 官方源模式的来源与启动记录 | **通过**；记录下载/校验来源并指向下两份运行结果；不等于 GitHub Actions 已执行 |
| [local-chain-smoke-2026-09-28-official.md](local-chain-smoke-2026-09-28-official.md) | 官方源模式隔离链 v2 冒烟摘要 | **通过**，27/27；与非 official 运行分开计 |
| [local-chain-smoke-2026-09-28-official.json](local-chain-smoke-2026-09-28-official.json) | 上一行同次运行的机器可读明细 | **通过**；不能另计一轮 |
| [local-chain-java-test-2026-09-28-112202.txt](local-chain-java-test-2026-09-28-112202.txt) | 09-28 11:22 官方源模式隔离链的 Java 冒烟 + 业务流 | **通过**，2/2；文件用内存替身 |
| [mysql-business-flow-2026-09-28.txt](mysql-business-flow-2026-09-28.txt) | MySQL 8.0.46 业务 schema 与 `BusinessFlowMySqlSmokeTest` 重复运行 | **通过**，两次各 11/11；记录真实表结构 |
| [mysql-files-and-queries-2026-09-28.txt](mysql-files-and-queries-2026-09-28.txt) | MySQL 8.0.46 文件/查询与业务流各连续运行两次 | **修复后通过**，每轮两组各 11/11；日志保留首次发现的分页 SQL 空格问题 |
| [local-chain-files-e2e-2026-09-28-164122.txt](local-chain-files-e2e-2026-09-28-164122.txt) | 09-28 16:41 真实隔离链 + kubo 文件、GC 与读模型重建 | **通过**，`RealChainFilesE2ETest` 与 `RealChainBusinessFlowTest` 各 1/1 |
| [restore-drill-populate-2026-09-28-215701.txt](restore-drill-populate-2026-09-28-215701.txt) | 09-28 21:57 备份演练数据填充首次尝试 | **失败，原样保留**：登录响应缺少预期 `data`，测试在取 token 时 NullPointer；日志未记录上游响应详情，不能单凭它断定根因 |
| [restore-drill-populate-2026-09-28-215835.txt](restore-drill-populate-2026-09-28-215835.txt) | 09-28 21:58 同一演练的填充重试 | **通过**，1/1；生产/分销/零售上链与公开文件读回均记录 |
| [restore-drill-verify-2026-09-28-220654.txt](restore-drill-verify-2026-09-28-220654.txt) | 09-28 22:06 恢复后校验首次尝试 | **失败，原样保留**：断言要求首次重建 `rows.created=0`，实际为 4；失败不等于链上或文件读回失败 |
| [restore-drill-verify-2026-09-28-220951.txt](restore-drill-verify-2026-09-28-220951.txt) | 09-28 22:09 修正后的恢复校验汇总 | **通过**，1/1；该文件只含测试计数 |
| [restore-drill-verify-2026-09-28-220951-full.txt](restore-drill-verify-2026-09-28-220951-full.txt) | 与上一行同次运行的详细输出 | **通过**；消费者视图、CID 字节读回、读模型重建前后状态均可核对 |
| [restore-drill-2026-09-28.txt](restore-drill-2026-09-28.txt) | 09-28 备份与恢复演练命令、数据、清理记录 | **通过**；这是人工汇总，以上原始日志用于核对失败与重试过程 |
| [contract-v3-e2e-2026-09-29.txt](contract-v3-e2e-2026-09-29.txt) | 09-29 00:23 裸合约 v3 与 v2 兼容的隔离链运行 | **通过**；非指定分销商直接调用 v3 的交易在块 42 回执 `0x16`，有哈希与 revert；与下行是两次运行 |
| [contract-v3-e2e-2026-09-29-rerun.txt](contract-v3-e2e-2026-09-29-rerun.txt) | 09-29 00:29 补账户地址后的裸合约重跑 | **通过**；非指定分销商在块 59 再次回执 `0x16`，并验证 v2 旧批次续走 |
| [contract-v3-backend-e2e-2026-09-29.txt](contract-v3-backend-e2e-2026-09-29.txt) | 09-29 00:35 后端经 WeBASE-Front 按批次分流 | **通过**，1/1；六笔 ChainTx 块 77–82 回执 `0x0` 且 `to` 匹配 v2/v3；业务库 H2 内存、文件服务 FakeKubo，未验证真实 MySQL/IPFS |
| [contract-v3-backend-final-2026-09-29-003535.txt](contract-v3-backend-final-2026-09-29-003535.txt) | 上一行同次 Java runner 的原始测试计数 | **通过**，1/1；不能再计一次运行 |

这里有两处容易误读：`215835` 是**成功的填充重试**，不是第二份失败日志；第二份失败原始日志是 `220654` 的恢复校验。v3 裸链两次均有真实回执；后端分流验收连接真实隔离链与 WeBASE-Front，但业务库是 H2、文件是 FakeKubo。

Hardhat 测试只能证明进程内 EVM 行为；只有列出交易哈希、块高、回执 `status` 的隔离链记录才能证明 FISCO BCOS 上的结果。
