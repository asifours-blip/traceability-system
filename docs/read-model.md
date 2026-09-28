# 读模型与分页查询

各角色的批次列表、消费者查询都是分页查询，数据取 MySQL，不逐条读链。链上仍是唯一事实来源：读模型只由读链结果写入，可以随时从链上完整重建。

## 表

`trace_read_model`（`db/business-schema.sql`）：每个链上溯源号一行。

| 列 | 来源 |
|----|------|
| `stage_reached` | 已写入阶段中最大的阶段号 |
| `producer/distributor/retailer_address` | `getStageActors` |
| `production/distribution/retail_data` | 各阶段读函数的全部字段 JSON（含上链时间）；列表与消费者详情从这里取 |
| `product_name`、`producer_company`、`production_location`、`variety`、`product_time`、`production_ts` | 生产阶段数据的冗余列，供查询与排序 |
| `production_cid`、`distribution_cid` | 链上文件字段，文件绑定只用这里的值 |
| `list_index` | 在 `getAgroFoodList` 中的位置（重建时写） |
| `claim_status`、`claim_note` | 与本系统归属（`trace_batch`）的比对结果，见下 |
| `synced_at` | 最近一次**内容变化**的时间（内容没变不写，重建才可能完全幂等） |

写入时机：阶段交易 CONFIRMED 后刷新该溯源号（`StageConfirmedHandler`）；消费者首次查询一个读模型里还没有的溯源号时读一次链补进来；管理员重建。

## 使用的 WeBASE-Front 接口（全部经过核验）

只用一个接口：`POST /WeBASE-Front/trans/handle` 调合约只读函数。

| 函数 | 返回结构 | 核验依据 |
|------|----------|----------|
| `getStageActors(tn)` | `["0x…","0x…","0x…"]`，未写入为 0 地址；不存在 `["Call contract return error: Trace: traceNumber does not exist"]` | [webase-front-contract.md](webase-front-contract.md)、`docs/artifacts/local-chain-smoke-2026-09-28*.md` |
| `getAgroFoodInfo*` 三个阶段读函数 | 全部返回值是字符串，末尾是 timestamp，未写入为空串与 0 | 同上 |
| `getAgroFoodList()` | **单元素数组，元素是 JSON 数组的字符串**：`["[ \"A\", \"B\" ]"]`，空列表 `["[ ]"]`；引号、反斜杠按 JSON 转义 | 本阶段在本地隔离链上新部署合约实测：`scripts/local-chain/probe-list-shape.py` → [`artifacts/webase-string-array-2026-09-28.json`](artifacts/webase-string-array-2026-09-28.json)（含 `LIST-q"x, ]y\z 中` 这样的溯源号） |

此前测试替身把 `getAgroFoodList` 模拟成嵌套数组 `[["A","B"]]`，与真实响应不符，已按实测改正；`ChainTraceReaderListTest` 用逐字取自真实链的响应做解析测试。

**没有用 `TraceStageRecorded` 事件**：冒烟只核验过交易回执的 `logs` 里带这个事件（topic0 计数），WeBASE-Front 的事件查询接口（按块区间拉取日志）没有在真实链上核验过，按「只用核验过的接口」的要求不使用。代价是重建为 1 + N×(1 + 已写入阶段数) 次只读调用，链上批次多时较慢（同步执行，接口超时前端设为 5 分钟）。

## 重建（`POST /admin/read-model/rebuild`，仅管理员）

1. `getAgroFoodList` 取全部溯源号（读不到整次失败 503，不会把读模型清空）。
2. 逐个 `getStageActors` + 已写入阶段的读函数，组装一行；与现有行逐列比较，只在内容变化时写入。
3. **旧批次回填**：`trace_batch` 里没有这个溯源号时，按链上生产阶段写入者地址（大小写不敏感）找**角色为生产商**的账号，找到才建归属记录；已写入的下游阶段同样按写入者匹配分销商 / 零售商账号，写入交接历史（原因「重建读模型：按链上写入者地址回填」，操作人是执行重建的管理员）。已有归属记录只补空的下游对象，不改本系统已有的指定关系。
4. **认领状态**：
   - `UNCLAIMED`：本系统没有归属记录（生产写入者没有账号、账号不是生产商、或溯源号超过 64 字符放不进 `trace_batch`）；
   - `PARTIAL`：有归属记录，但某个已写入阶段的链上写入者不是本系统指定的账号（包括绕过后端直接调合约写入的情况）；
   - `CLAIMED`：各阶段写入者都与归属一致。
   `UNCLAIMED` 与 `PARTIAL` 列在管理员页面「读模型 → 未认领批次」（`GET /admin/read-model/unclaimed`，分页）。为写入者地址补建账号后再重建即可认领。
5. **文件**：按链上 CID 补齐绑定（见 [files.md](files.md)「旧文件」）。
6. 读模型里有、链上 `getAgroFoodList` 里已没有的行（如换了合约）删除。

幂等：同一时刻只允许一个重建（否则 409）；第二次重建报告 `created=0, updated=0, batchesBackfilled=0, removedStale=0`，文件为 `ALREADY_BOUND`，读模型、归属、交接历史、文件记录的快照完全相同（`FilesAndQueriesTestBase` 断言）。同一测试还逐条读链，断言读模型每行的写入者、各阶段 JSON（逐字段、同顺序、同类型）与 `stage_reached` 与直接读链一致。

## 分页接口

| 接口 | 说明 |
|------|------|
| `GET /batches?page&size&todo&keyword` | 归属取 `trace_batch`，链上进度左连读模型；`todo=true` 只看轮到自己录入的（生产商：链上还没有生产记录；分销商：已生产未分销；零售商：已分销未零售）；按建档倒序 |
| `GET /trace/search?keyword&page&size`（免登录） | 只查读模型，只返回公开字段（溯源号、产品、生产企业、产地、品种、生产日期、进度），按生产上链时间倒序 |
| `GET /admin/read-model/unclaimed?page&size` | 未认领 / 部分认领 |

参数规则：`page ≥ 1`、`1 ≤ size ≤ 100`，否则 400（不静默纠正）；超出末页返回空 `records` 与真实 `total`；`keyword` 转义 `%`、`_`、`\` 后做包含匹配。返回 `{records, total, page, size}`。

真实 MySQL 上发现并修复过一个 H2 不报的问题：动态条件之间没有空格时，MySQL 驱动在客户端替换参数，`?AND` 变成 `5AND`，报语法错误（记录见 `artifacts/mysql-files-and-queries-2026-09-28.txt`）。
