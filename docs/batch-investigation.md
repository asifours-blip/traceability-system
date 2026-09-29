# 单批只读调查与召回草案

`GET /batches/{traceNumber}/investigation` 只对该批次参与者与管理员开放，沿用批次详情的鉴权与合约地址漂移 409 规则。服务端只调用一次 `BatchService.detail` 构造调查输入；该详情内部会分别读取链上写入者、已写阶段与 v3 指定对象，因此不是同一区块高度的原子快照。此入口不扫描全链、不发送交易或通知、不写业务库。

响应 `schemaVersion=batch-investigation/v1`，包含批次号、绑定的合约版本与地址、`chainRecordStatus`（`NOT_CREATED`、`AVAILABLE`、`UNAVAILABLE`）、`status`、`evidence`、`issues` 与 `recallDraft`。证据按 `CHAIN_READ`、`LEDGER`、`FILE_PROBE` 标注来源；每条均携带批次号、合约绑定、可得的读取函数/阶段字段、写入者或台账交易哈希/块高。台账交易条目的 `writer` 留空，不把链上 actor 冒充为该交易的签名者。

规则线索包括 v3 链指定与台账地址不一致/未映射、交易未决、链上 CID 对应的文件缺失或绑定冲突。`IPFS_UNAVAILABLE` 只记 `FILE_UNVERIFIED`，不判文件丢失；`NOT_BOUND` 保留为台账登记状态。链不可用时不输出部分链证据，报告 `INCONCLUSIVE`。`recallDraft` 仅是**单批人工复核草案**，下游范围与库存为 `UNKNOWN`，不判定召回必要性或责任；它也是未来 Agent 可消费的规则结构，当前没有自主 LLM 调查。

批次详情页可手动生成调查卡并在浏览器本地下载该响应的 JSON；刷新失败会清掉旧调查结果。后端验收使用 H2、FakeWeBASE 与 FakeKubo，前端浏览器验收使用已构建 Vue 页面及合成 API fixture；这些证据不等于完整 FISCO 实链端到端验证。原始命令日志、浏览器截图与进程清理记录位于被 Git 忽略的 `back-me/target/investigation-acceptance/`。
