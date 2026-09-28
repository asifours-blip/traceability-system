# 手工接口证据

- Collection：`postman_collection.json`
- 请求条数：**11**（以 JSON 里 `item` 数组长度为准）
- 本目录 **没有** Newman/Postman 导出的 run 结果，因此不能据此宣称「已跑通 11 条联调」或「60+ 用例」
- 离线可重复证据是 `cd back-me && mvn -B test`，不连真链

# 运行记录索引（阶段 4：文件与查询）

- `webase-string-array-2026-09-28.json`：WeBASE-Front v1.5.5 对 `string[]`（`getAgroFoodList`）的真实响应结构核验（`scripts/local-chain/probe-list-shape.py`）
- `mysql-files-and-queries-2026-09-28.txt`：新表在真实 MySQL 8.0.46 上重复建表，文件与查询、业务闭环两组用例各连续运行两次
- `local-chain-files-e2e-2026-09-28-164122.txt`：本地隔离链 + 真实 kubo：上传 → 上链 → 绑定 → 读回 → 重启 kubo 后读回 → pin rm + gc 后 410 → 读模型重建与未认领
