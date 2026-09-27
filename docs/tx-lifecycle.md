# 交易状态机与恢复

目标：链上成功只由回执（或可证明的链上状态）确认；拿不到结果时如实记为「未知」，不自动重发，也不假装失败。

WeBASE-Front 的响应契约见 [webase-front-contract.md](webase-front-contract.md)，本文的分类规则都以它为依据。

## 组成

| 类 | 职责 |
|----|------|
| `HttpUtil`（`WeBaseClient`） | 共享连接池客户端；连接超时 3 s、读超时 40 s（`webase-front.connect-timeout-ms` / `read-timeout-ms`）；**关闭自动重试**；响应在 try-with-resources 中关闭。发交易返回 `TxOutcome`，传输异常也折算成结果，不抛出 |
| `WeBaseResponses` | 纯函数：把 HTTP 状态码 + 响应体分类成 `TxOutcome`，解码 `Error(string)` |
| `ChainTxService` | 所有上链交易的唯一入口：先写 `chain_tx`，再发请求，按结果更新；非成功抛 `ChainTxException`（带 HTTP 状态码与交易记录） |
| `StageProbe` | 没有回执时读链判断阶段交易的下落 |
| `ChainErrors` | 结果 → 返回给前端的 HTTP 状态码与中文说明 |
| `ChainTxController` | `GET /chain-tx/{id}`、`POST /chain-tx/{id}/verify` |

## 状态

```
PENDING ──(推进后才发请求)──> SUBMITTED ──> CONFIRMED   回执 status=0x0
                                        ├─> FAILED      回执失败(revert) / WeBASE 在发送前拒绝 / 连不上
                                        └─> UNKNOWN     读超时、响应中断、坏 JSON、5xx、WeBASE 回执超时
SUBMITTED/UNKNOWN ──查证──> CONFIRMED / FAILED(冲突) / 仍为 UNKNOWN 但释放业务键(链上确认未写入)
```

- **PENDING**：提交意图已落库，尚未发请求。
- **SUBMITTED**：发请求前一刻推进；之后进程无论在哪一步中断，这条记录都表示「可能已发出」。
- **CONFIRMED**：回执 `status=0x0`，或查证确认该阶段由本次签名地址以相同数据写入。
- **FAILED**：回执带哈希与块高但 `status≠0x0`（revert 原因已解码）；或 WeBASE 422 `201015/201151`（没签名/没编码，交易没发出）；或连接被拒/连接超时（请求没送达）；或查证发现该阶段已被他人/其他数据写入。
- **UNKNOWN**：请求可能已送达、交易可能已发出，但没有可判定的结果。**不自动重发。**

## 发交易的分类规则

| 观察到的情况 | 结果 | 返回给前端 |
|--------------|------|------------|
| HTTP 200，有哈希、有块高、`status=0x0`、`statusOK=true` | CONFIRMED | 200，`data` 为交易记录（含哈希、块高） |
| HTTP 200，有哈希、有块高、`status≠0x0` | FAILED | 按 revert 映射：`already exists / already recorded / not recorded yet` → 409，`does not exist` → 404，角色不足 / 非 owner → 403，`is empty` → 400，其他 → 422 |
| HTTP 200，无哈希（如 `50001 Transaction receipt timeout`）或缺 `status`/块高 | UNKNOWN | 202，提示调用查证接口 |
| HTTP 422 且 code 为 201015 / 201151 | FAILED（未发出） | 502 / 400 |
| HTTP 422 其他 code | UNKNOWN | 202 |
| 其他 4xx（400 参数绑定、404 路径） | FAILED（未发出） | 502 |
| HTTP 5xx | UNKNOWN | 202 |
| 连接被拒、连接超时、域名解析失败 | FAILED（未送达） | 503，可直接重试 |
| 读超时 | UNKNOWN | 202 |
| 响应体中断、坏 JSON | UNKNOWN | 202 |

非成功时 HTTP 状态码与 body 的 `code` 一致，`data` 是 `chain_tx` 记录，`mes` 带中文说明与原始 revert 文本，例如 `分销信息已录入，不能重复录入（链上 revert：Trace: distribution already recorded）（交易记录 #19）`。

## 业务键与重复提交

- 阶段交易（生产/分销/零售）的业务键为 `trace:{溯源号}:{PRODUCTION|DISTRIBUTION|RETAIL}`。记录处于 PENDING / SUBMITTED / UNKNOWN 时 `inflight_key = biz_key`，`chain_tx.inflight_key` 上有唯一索引，所以同一溯源号同一阶段**同时只能有一笔未决交易**，并发提交由数据库兜底。
- 已有未决记录时，新的提交返回 **409**，`data` 是那条未决记录，提示先调用查证接口；**请求不会发到 WeBASE**。
- 其他交易（授予/撤销角色、系统信息）也写 `chain_tx`（业务键 `{函数名}:{参数摘要前 16 位}`），但**不占业务键**：这些操作重复执行只会被合约拒绝（如 `Roles: account already has role`）或写入相同的值，没有「重复写入」风险，而且没有回执时无法靠读链判定，占用业务键会让它们永远无法重试。

## 查证 `POST /chain-tx/{id}/verify`

只有签名者本人或管理员可以查看/查证（否则 403）。

1. 记录已是 CONFIRMED / FAILED：返回 `ALREADY_FINAL`。
2. PENDING 超过 60 秒：说明进程在发请求之前就中断了（请求前一定先推进到 SUBMITTED），判 FAILED（`NOT_SENT`）。
3. 有交易哈希：`GET /{groupId}/web3/transactionReceipt/{hash}`。成功回执 → CONFIRMED（`RECEIPT_CONFIRMED`）；失败回执 → FAILED（`RECEIPT_FAILED`）；查不到（v1.5.5 返回 500）→ 进入下一步。
4. 阶段交易读链（`StageProbe`，依据合约 v2「每个溯源号的每个阶段只能写一次」）：
   - 读 `getStageActors(溯源号)`；溯源号不存在或该阶段为 0 地址 → **未写入**。
   - 写入者不是本次签名地址 → FAILED，冲突（`CONFLICT`）。
   - 写入者是本次签名地址 → 再读该阶段数据（`getAgroFoodInfo*`），与提交时的参数摘要比对：相同 → CONFIRMED（`STATE_CONFIRMED`，哈希与块高未知）；不同 → FAILED，冲突。
   - 同一业务键已有另一条 CONFIRMED 记录（有回执为证）时，本条不能冒领 → FAILED，冲突。
   - **未写入** → 状态仍是 UNKNOWN（原交易之后是否上链确实未知），但释放业务键，`verify_result=NOT_WRITTEN`，**允许用户显式重新提交**。原交易若之后才上链，重新提交的那笔会被合约以「已写入」拒绝，不会重复写入；届时再查证原记录即可得到结论。
5. 非阶段交易且拿不到回执：`INCONCLUSIVE`，状态不变。

参数摘要：每个参数转成字符串后组成 JSON 数组取 sha256。实测 WeBASE 只读调用把 `uint` 也返回成字符串（`"10"`），后端 DTO 里是 `Long 10`，转字符串后一致；这一点在真实链上由 `RealChainSmokeTest` 验证过。

## 数据库

`back-me/src/main/resources/db/chain-tx-schema.sql`（MySQL；测试用 H2 MySQL 模式执行同一份文件）。

## 测试

| 类 | 内容 |
|----|------|
| `WeBaseResponsesTest`（15） | 用真实链上取得的响应体：成功、revert（HTTP 200）、`output` 解码、回执超时 50001、422 `201015`/`201151`、未知 422、5xx、坏/半截 JSON、有哈希缺 status、查回执 200/500/哈希不一致、revert → 业务状态码 |
| `HttpUtilSendTransactionTest`（9） | 本地 HTTP 替身：成功、revert、读超时且只发一次、响应体中途断连（原始 socket 替身）、坏 JSON、5xx、连接被拒 → 未发出、无会话地址拒发、按哈希查回执 |
| `ChainTxLifecycleIntegrationTest`（17） | MockMvc + H2 + 替身：请求到达 WeBASE 时记录已落库（SUBMITTED）且 5xx 后仍可查到；成功 / revert 409 / 读超时 / 响应中断 / 坏 JSON / 回执超时 / 私钥不在 WeBASE；UNKNOWN 后重复提交 409 且不发请求；查证：按哈希确认、查不到回执退回读链、本人相同数据 CONFIRMED、他人写入冲突、同人不同数据冲突、未写入后显式重提、原记录再查证不能冒领、溯源号不存在、非本人 403 |
| `RealChainSmokeTest`（1，默认跳过） | 设置 `E2E_SMOKE_FILE` 时用后端 `HttpUtil` / `StageProbe` 直连本地隔离链；由 `scripts/local-chain/java-real-chain-test.sh` 在 WSL 中运行 |

## 已知限制

- 读链查证只适用于阶段交易；角色授予/撤销、系统信息没有回执时只能人工核对。
- 两条都是 UNKNOWN、签名地址与参数完全相同的阶段记录（只可能出现在「未写入 → 重提 → 两者都未确认」的情况），查证时先查证的那条会被判 CONFIRMED；链上数据正确，只是账本里的归属可能对调。
- `UserAccountServiceImpl.createUser` 先上链授角色再建账号：授角色结果未知时账号不建；若那笔交易后来上链，重试会被合约以 `Roles: account already has role`（409）拒绝，需要人工处理。
- 前端 `axios` 超时是 5 s，而一笔交易正常 1 s 内返回、最坏要等 WeBASE 的 30 s 回执超时；前端尚未处理 202 / 409 与查证接口（本阶段只改后端）。
