# Trace 合约

本目录是农产品溯源系统的链上部分（`pragma solidity ^0.4.25`，面向 FISCO BCOS 2.x / WeBASE-Front）。

- **v2（当前源码）**：重做了访问控制与阶段规则，并补上可离线运行的合约测试。
- **v1（提交 `8ec675c` 及之前的源码）**：毕业设计时的版本，存在下文「v1 的已知漏洞」中列出的问题。**已经部署到链上的 v1 合约不会因为本目录源码更新而改变，这些漏洞在旧部署上依然存在。**

## 来源与诚实说明

- `code1.1.3` 仓库原先 **没有** `.sol`，后端只在 `application.yml` 内嵌 ABI，经 WeBASE-Front 调用
- v1 源码复制自同课题早期本地目录 `code1.1/contracts-me/`；用本目录的编译脚本编译 v1 源码，得到的 ABI 与 v1 的 `abi/Trace.json` 逐项一致
- **不能**声称 v1 源码就是链上已部署字节码的逐字节还原——没有从节点导出的 bytecode 对照
- v2 在 Hardhat 进程内 EVM 上有完整测试；另外已部署到**本地隔离的** FISCO BCOS 2.7.2 四节点链（经 WeBASE-Front v1.5.5），跑通授权 → 生产 → 分销 → 零售，越权、乱序、重复写入均在链上被拒，记录见 `docs/artifacts/local-chain-smoke-2026-09-28.md`。没有在生产或多机环境部署过

## 文件

| 文件 | 职责 |
|------|------|
| `Trace.sol` | 溯源入口：创建批次、分销/零售追加、阶段检查、查询 |
| `AgroFoodInfoItem.sol` | 单个溯源号的生产/分销/零售数据与各阶段写入者；只接受创建它的 Trace 写入 |
| `Ownable.sol` | 管理员（owner = 部署者） |
| `Producer.sol` / `Distributor.sol` / `Retailer.sol` | 三种业务角色，授予/撤销仅限 owner |
| `Roles.sol` | 地址集合 |
| `SystemInfo.sol` | 系统名/版本/描述，修改仅限 owner |
| `abi/Trace.json` | Trace 的 ABI，由 `npm run abi` 生成 |
| `scripts/compile.js` | 用 npm 包 `solc@0.4.25` 编译 |
| `scripts/test-legacy.js` | 把 v1 源码从 git 历史导出，用同一套测试跑对比 |
| `test/Trace.test.js` | 合约测试 |

## 权限模型（v2）

构造函数签名不变：

```text
Trace(address producer, address distributor, address retailer)
```

- `msg.sender`（部署者）成为 **owner**，`owner()` 可查。
- owner **不会**自动获得三种业务角色（最小权限）。三个参数中非 0 地址的分别授予对应角色；传 0 地址表示暂不授予，之后由 owner 补授。
- 如确有需要，owner 可以显式把业务角色授予自己，但不推荐：管理员身份与业务身份应该分开。

| 操作 | 谁可以调用 |
|------|-----------|
| `addProducer` / `addDistributor` / `addRetailer(address)` | 仅 owner |
| `removeProducer` / `removeDistributor` / `removeRetailer(address)` | 仅 owner |
| `renounceProducer` / `renounceDistributor` / `renounceRetailer()` | 角色持有者本人 |
| `setSystemInfo` / `clearSystemInfo` | 仅 owner |
| `newAgroFood` | 生产者 |
| `addTraceInfoByDistributor` | 分销商 |
| `addTraceInfoByRetailer` | 零售商 |
| `AgroFoodInfoItem.setProducer` / `setDistributor` / `setRetailer` | 仅创建该条目的 Trace 合约 |
| 所有 `get*` / `is*` / `owner()` | 任何人（链上数据本来就公开） |

角色撤销立即生效：被 `remove*` 的地址在下一笔交易就会被角色检查拒绝。**已经写入的数据不会因为撤销角色而被删除或标记**，`getStageActors` 仍会返回当时的写入者。

## 阶段规则（v2）

每个溯源号严格按 **生产 → 分销 → 零售** 的顺序，每个阶段只能写一次：

1. 生产者 `newAgroFood`：溯源号非空且未登记过，创建条目并写入生产信息
2. 分销商 `addTraceInfoByDistributor`：溯源号已登记（即已生产）且尚未分销
3. 零售商 `addTraceInfoByRetailer`：已分销且尚未零售

每个阶段写入成功时触发事件：

```solidity
event TraceStageRecorded(string traceNumber, uint8 stage, address actor); // stage：1=生产 2=分销 3=零售
```

`getStageActors(string traceNumber) returns (address producer, address distributor, address retailer)` 返回各阶段实际写入者（即当时的 `msg.sender`），未写入的阶段为 0 地址。

### revert 信息

| 场景 | revert 信息 |
|------|-------------|
| 非 owner 调用 add/remove/setSystemInfo/clearSystemInfo | `Ownable: caller is not the owner` |
| 没有对应角色的账户写入 | `ProducerRole: caller does not have the Producer role`（分销、零售同理） |
| 溯源号为空（写入和查询） | `Trace: traceNumber is empty` |
| 生产时溯源号已存在 | `Trace: traceNumber already exists` |
| 分销/零售/查询时溯源号不存在（分销早于生产即属此类） | `Trace: traceNumber does not exist` |
| 重复分销，或零售之后再补分销 | `Trace: distribution already recorded` |
| 零售早于分销 | `Trace: distribution not recorded yet` |
| 重复零售 | `Trace: retail already recorded` |
| 绕过 Trace 直接调用条目合约的 setter | `AgroFoodInfoItem: caller is not the Trace contract` |
| 条目合约内部的阶段兜底检查（正常路径不会触发，Trace 已先检查） | `AgroFoodInfoItem: producer/distributor/retailer stage not allowed` |
| 重复授予 / 撤销不存在的角色 / 授予 0 地址 | `Roles: account already has role` / `Roles: account does not have role` / `Roles: account is the zero address` |

说明：新合约里「溯源号已登记」与「生产阶段已写入」是同一件事，因此「分销早于生产」表现为 `traceNumber does not exist`，没有单独的错误信息。

### 读取行为

- 溯源号不存在：`getAgroFoodInfo`、`getAgroFoodInfoByDistributor`、`getAgroFoodInfoByRetailer`、`getStageActors` 都 **revert**（`Trace: traceNumber does not exist`）。
- 溯源号存在但分销/零售阶段尚未写入：对应查询 **不 revert，返回空值**——字符串为 `""`、数值为 `0`，`timestamp` 为 `0` 即表示未写入；`getStageActors` 对应位置为 0 地址。这与 v1 的行为及后端「公司名为空视为未录入」的解析方式一致。

## 接口变化与兼容性

`Trace` 的 ABI 相对 v1 **只增不改**：v1 的全部函数与事件签名、参数和返回值都保留，新增 6 项：

- `owner()`
- `removeProducer(address)` / `removeDistributor(address)` / `removeRetailer(address)`
- `getStageActors(string)`
- `event TraceStageRecorded(string,uint8,address)`

但**语义**变了，调用方需要注意：

- `addProducer` 等从「同角色持有者可调用」改为「仅 owner」。
- 部署者不再自动拥有三种业务角色。如果后端曾用 `contract.owner` 身份直接写溯源数据，在 v2 上会被拒绝，需要改用被授予角色的账户。
- revert 文本统一改成 `Trace: ...` 格式（例如 v1 的 `Trace:traceNumber is not exists!` 变为 `Trace: traceNumber does not exist`），依赖旧文本的调用方需要调整。
- `AgroFoodInfoItem` 的 setter 多了第一个参数 `address actor`。它不是对外接口，后端不应直接调用。

### 迁移

- **v1 部署的数据不迁移。** v2 必须重新部署到新地址，新地址上从空数据开始。
- 后端配置中的合约地址和 ABI（`application.yml` 的 `contract.abi`）需要换成 v2 的；这部分由后端改动负责，不在本目录。
- 部署后 owner 需要先调用 `addProducer` / `addDistributor` / `addRetailer` 授予业务账户（或在构造参数里直接传入）。

## v1 的已知漏洞

以下问题存在于 v1 源码及其所有已部署实例。v2 源码修复了它们，但**已部署的 v1 合约无法原地修复**，只能停用并改用新部署的 v2：

1. **条目合约可被任意改写**：`AgroFoodInfoItem` 的 `setProducer` / `setDistributor` / `setRetailer` 是没有任何限制的 `public`。只要知道条目地址（`newAgroFood` 的返回值，或按 CREATE 规则由 Trace 地址和 nonce 推算），任何账户都能直接调用条目合约覆写生产、分销、零售数据，完全绕过 Trace 的角色检查。
2. **系统信息可被任意修改**：`setSystemInfo`、`clearSystemInfo` 没有访问控制。
3. **角色自我扩散、无法撤销**：`addProducer` 等由 `onlyProducer` 等保护，任何角色持有者都能继续给别人授权；没有管理员，也没有撤销他人角色的途径，只能靠本人 `renounce`。部署者自动持有全部三种角色。构造参数传 0 地址时会把 0 地址登记为角色持有者。
4. **不强制阶段顺序与只写一次**：零售可以早于分销，分销和零售可以被重复覆盖；空字符串可以作为溯源号登记。

### 用同一套测试对 v1 的复现结果

`npm run test:legacy` 会把提交 `8ec675c` 的 v1 源码导出到临时目录、编译，并运行与 v2 完全相同的测试（条目 setter 的攻击参数按条目合约的实际 ABI 构造，因此对 v1 发起的是真实可执行的调用）。结果为 **4 通过、25 失败**，失败分三类：

| 类别 | 测试 | 说明 |
|------|------|------|
| 攻击在 v1 上成功（漏洞可复现） | I1-1、I1-2、I1-3、S2-1、S2-2、R3-2、R3-3、R3-9、P4-2、P4-3、P4-4、P4-5、P4-7 | 期望被拒的调用在 v1 上执行成功（R3-2：部署者自动持有角色；R3-9：0 地址被登记为角色） |
| v1 缺少对应接口 | I1-4、R3-1、R3-5、R3-6、R3-7、P4-8、F-2、F-3 | `owner()`、`remove*`、`getStageActors`、`TraceStageRecorded` 等在 v1 中不存在 |
| v1 同样拒绝，但 revert 文本不同 | R3-4、R3-8、P4-1、P4-6 | 行为一致，只是错误信息改了 |

v1 上通过的 4 项（S2-3、R3-10、F-1、F-4）是 owner 修改系统信息、`renounce`、正常三阶段读回、列表顺序，属于两版共有的正常功能。

## 构建与测试

环境：Node.js ≥ 20（CI 用 22）。所有命令在 `contracts/` 目录下执行。

```bash
npm ci              # 安装 solc@0.4.25、hardhat、ethers（版本在 package.json 中锁定）
npm test            # 编译到 build/，然后在 Hardhat 进程内 EVM 上运行 test/
npm run abi         # 重新生成 abi/Trace.json
npm run test:legacy # 对 v1 源码跑同一套测试（需要完整 git 历史；预期失败，用于对比）
```

- **编译器**：直接使用 npm 包 `solc@0.4.25` 内置的 `soljson.js`，不在运行时联网下载编译器。该文件是 asm.js，Node 默认调用栈加载不了，所以脚本以 `node --stack-size=4000` 运行（已写在 npm scripts 里）。编译设置显式写为不开优化器、`evmVersion: byzantium`，与 solc 0.4.25 默认值一致。编译前把源码换行统一为 LF，使 Windows 与 Linux 得到相同的产物。
- **测试链**：Hardhat 只作为进程内 EVM 和 mocha 运行器（`hardhat test --no-compile`），硬分叉设为 `byzantium` 以匹配编译目标；没有开启 `allowUnlimitedContractSize`，Trace 运行时字节码约 22.9 KB，在 EIP-170 的 24 KB 上限内。这不是 FISCO BCOS 节点的仿真，FISCO 特有行为（国密、预编译合约、群组等）不在测试范围内。
- **ABI 可复现**：`abi/Trace.json` 由 `npm run abi` 生成（即 `node --stack-size=4000 scripts/compile.js --write-abi`）。CI 的 `contracts` job 会重新生成并用 `git diff --exit-code` 检查提交的文件是否最新。
- `npm audit` 会报告若干 devDependencies（Hardhat 的传递依赖）的告警；这些包只在本地/CI 测试时使用，不进入链上合约或后端产物。

## 已知限制

- **owner 是单点**：没有 `transferOwnership`、多签或时间锁。owner 私钥丢失则无法再授予/撤销角色、修改系统信息，只能重新部署；owner 私钥泄露则攻击者可以授予自己业务角色并写入数据（但不能修改已写入的阶段）。
- 撤销角色不会追溯处理该账户此前写入的数据。
- 链上只保证「谁、按什么顺序、写了一次」，不验证数据内容本身的真实性（证书、质检报告等只是存了 hash/CID）。
- 查询仍是 N+1：`getAgroFoodList()` 只返回溯源号数组，后端对每个号再调三次查询；列表没有分页。
- `pragma experimental ABIEncoderV2` 在 0.4.25 中仍是实验特性（`getAgroFoodList` 返回 `string[]` 需要它），沿用 v1 的选择以保持 ABI 兼容。
- 只在本地隔离链（单机 4 节点，`scripts/local-chain/`）上部署验证过，未在生产或多机环境验证。

## 调用路径

应用不使用 FISCO Java SDK。`HttpUtil` POST 到 WeBASE-Front，body 带 `contractName=Trace`、`contractAddress`、`contractAbi`、`user`（链上身份）、`funcName`、`funcParam`。
