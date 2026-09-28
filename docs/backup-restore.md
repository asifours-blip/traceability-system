# 备份与恢复

本文档说明本仓库演示环境（本地隔离链 + 真实 kubo + 真实 MySQL）里，MySQL、kubo 仓库、链上标识三样东西
怎么备份、怎么恢复、恢复演练怎么跑，以及哪些数据是链下独有、备份缺失就再也找不回来的。

## 三个备份对象

| 对象 | 是什么 | 脚本 | 恢复方式 |
|---|---|---|---|
| MySQL（`trace_it`） | 账号、会话、交易台账（`chain_tx`）、文件对象（`file_object`）、读模型（`trace_read_model`）、交接历史（`trace_assignment_log`）、更正（`trace_correction`） | `scripts/backup/backup-mysql.sh` / `restore-mysql.sh` | `mysqldump` 逻辑备份；恢复到全新容器，不覆盖原容器 |
| kubo 仓库 | 已上传文件的实际内容（按 CID 寻址的 block）与 pin 列表 | `scripts/backup/backup-ipfs.sh` / `restore-ipfs.sh` | 停止守护进程后物理打包整个 repo 目录；恢复到全新目录 + 换端口起独立实例 |
| 链上标识 | 合约地址、群组 ID、ABI 版本（sha256）、部署时的仓库 commit | `scripts/backup/backup-chain-identity.sh` | 不是账本备份，只是「重新指向同一条链 / 同一份合约」需要的元数据；账本本身的备份是节点自己的数据目录，不在本仓库管辖范围 |

三者的关系：`trace_read_model` 完全可以从链上重建（`POST /admin/read-model/rebuild`，管理员权限），
所以只要链在、链上标识对得上、kubo 里的文件还在，读模型这张表本身丢了也无所谓，重建即可。
但 `chain_tx`（交易台账，含可能未确认结果的记录）、`trace_assignment_log`（交接改派历史）、`trace_correction`
（追加更正）、账号与会话，链上完全没有对应数据，只存在于 MySQL 里，丢了就是永久丢失。

## 恢复演练（本机跑过一次，记录见 `docs/artifacts/restore-drill-2026-09-28*.txt`）

步骤（本机 Windows Git Bash + WSL 混合环境，与 `scripts/local-chain/files-e2e.sh` 的边界一致：
本地隔离链跑在 WSL 里，MySQL 与 kubo 是 Windows 侧的资源，Docker Desktop 的网络转发让 WSL 能直接连
`127.0.0.1:<映射端口>`）：

1. **准备**：`docker start trace-mysql-test`；`bash scripts/ipfs/start.sh`；WSL 里 `bash scripts/local-chain/start.sh`。
2. **产出真实数据**：`RealChainBusinessFlowTest` 新增了 `E2E_MYSQL_URL`（可选 `E2E_MYSQL_USER` / `E2E_MYSQL_PASSWORD`）开关，
   设置后连真实 MySQL 而不是默认的 H2；配合已有的 `E2E_SMOKE_FILE`（真实链）与 `E2E_IPFS_API_URL`（真实 kubo，经
   `scripts/ipfs/wsl-bridge.sh` 从 WSL 转发），在 WSL 里跑：
   ```bash
   E2E_MYSQL_URL="jdbc:mysql://127.0.0.1:13306/<库名>?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai" \
   E2E_MYSQL_USER=root E2E_MYSQL_PASSWORD=<容器 root 密码> \
   E2E_IPFS_API_URL="http://127.0.0.1:5201" \
   E2E_TESTS="RealChainBusinessFlowTest" \
   bash scripts/local-chain/java-real-chain-test.sh
   ```
   演练用的是同一容器下新建的 `trace_it_drill` 库（避免和其它阶段在 `trace_it` 里留下的数据、尤其是
   已经存在的管理员账号密码混在一起）；生产环境直接对 `trace_it` 操作即可。
3. **备份**：
   ```bash
   MYSQL_DB=trace_it_drill bash scripts/backup/backup-mysql.sh
   bash scripts/ipfs/stop.sh && bash scripts/backup/backup-ipfs.sh
   bash scripts/backup/backup-chain-identity.sh <smoke.json 路径>
   ```
4. **恢复到全新 MySQL + 全新 kubo**：
   ```bash
   MYSQL_DB=trace_it_drill bash scripts/backup/restore-mysql.sh backups/mysql/<dump>.sql.gz trace-mysql-restore 13307 trace_it_drill
   bash scripts/backup/restore-ipfs.sh backups/ipfs/<repo>.tar.gz 'D:\trace-ipfs-restore'
   TRACE_IPFS_HOME_WIN='D:\trace-ipfs-restore' IPFS_API_PORT=5202 bash scripts/ipfs/setup.sh
   TRACE_IPFS_HOME_WIN='D:\trace-ipfs-restore' IPFS_API_PORT=5202 bash scripts/ipfs/start.sh
   ```
5. **校验**：`RealChainRestoreVerifyTest`（只读，不发任何链上交易）在 WSL 里对着恢复出来的 MySQL + kubo 跑：
   先直接读消费者公开视图（证明恢复出来的 MySQL 数据本身就是对的，不依赖重建）、
   再读文件（证明恢复出来的 kubo 按 CID 能读到字节级一致的内容）、
   最后跑一次读模型重建并断言不报错、批次视图前后一致（证明恢复出来的数据和链上一致，重建是安全的）。
   ```bash
   E2E_MYSQL_URL="jdbc:mysql://127.0.0.1:13307/trace_it_drill?..." E2E_MYSQL_PASSWORD=<恢复容器 root 密码> \
   E2E_IPFS_API_URL="http://127.0.0.1:5202" \
   E2E_RESTORE_VERIFY_RUN=<第 2 步日志里的 run 号，如 3934807> \
   E2E_TESTS="RealChainRestoreVerifyTest" \
   bash scripts/local-chain/java-real-chain-test.sh
   ```
6. **清理**：`docker rm -f trace-mysql-restore`；停恢复出来的 kubo 并删除临时目录；停两条 WSL→Windows 的 ipfs 桥；
   `bash scripts/local-chain/stop.sh`；`bash scripts/ipfs/stop.sh`；`docker stop trace-mysql-test`（如果是借来跑演练的）。

本机实跑结果：MySQL 恢复后 `trace_batch=1 user_account=5 file_object=3 trace_read_model=1`（与备份前一致）；
kubo 恢复后递归 pin 数与备份前一致，指定 CID `cat` 出来的字节和备份前完全相同；`RealChainRestoreVerifyTest`
通过（`tests=1 errors=0 failures=0`）。详见 `docs/artifacts/restore-drill-2026-09-28.txt`。

## 只存在于链下、备份缺失就永久丢失的数据

这些数据链上没有任何形式的存证，唯一副本就是 MySQL；备份策略必须覆盖它们，否则不是"重建"能解决的：

- **账号与密码**：`user_account` 表。丢失等于所有账号需要重新开户（管理员可以重新建号并重新给已有链上角色的地址跳过授权交易，
  但用户名 / 密码 / 公司名这些资料本身没有链上来源）。
- **会话**：`user_session`。就算 MySQL 恢复了，`token_hash` 对应的明文 token 只在登录那一刻返回给客户端过一次，
  服务端和备份里都不存明文；恢复后所有人必须重新登录（这本来就是会话该有的行为，不是恢复的缺陷）。
- **交接历史**：`trace_assignment_log`。生产商在分销之前可以改派下游分销商/零售商，留痕在这张表；
  链上只有最终真正写入交易的那个地址，中途改派了几次、改派原因都只在链下。
- **更正**：`trace_correction`。本系统的"更正"设计就是链下追加、不改链上原始记录（见 `docs/business-flow.md`），
  所以更正内容本来就不会以任何形式出现在链上，丢了就是彻底丢了。
- **限流状态**：登录失败计数（`LoginRateLimiterImpl`）是纯内存，进程重启即清空，不需要也不能备份——这是设计上的取舍，
  不是遗漏。

## 已知限制

- kubo 的物理备份要求备份前必须停止守护进程（一致性要求），生产环境需要一个能接受短暂只读窗口的备份计划；
  本仓库演示规模小可以接受停机打包，真实体量下应改成增量的 `ipfs pin ls` + 按需 `ipfs get` 导出，或者用支持热备份的对象存储替代单机 kubo。
- MySQL 备份用 `mysqldump --single-transaction`，不锁表，但仍是单次快照；两次快照之间发生的写入不在备份范围内，
  恢复点是"最近一次备份完成的时刻"，不是任意时间点（没有做 binlog 增量）。
- 链上标识备份的 `contractAddress` / `groupId` 依赖调用方提供 smoke json（本地隔离链每次 `smoke.sh` 部署都会生成一份）；
  生产环境应该从实际部署记录里取，不能假设固定路径。
