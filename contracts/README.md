# Trace 合约

## 来源与诚实说明

- `code1.1.3` 仓库原先 **没有** `.sol`，后端只在 `application.yml` 内嵌 ABI，经 WeBASE-Front 调用
- 本目录 Solidity 复制自同课题早期本地目录 `code1.1/contracts-me/`（`pragma solidity ^0.4.25`）
- 运行时 ABI 已抽出为 [`abi/Trace.json`](abi/Trace.json)，与 `application.yml` 中 `contract.abi` 一致
- 函数名已与 ABI 对齐（19 个 external/public 函数）。**不能**声称「这就是当前链上已部署字节码的逐字节还原」——没有从节点导出的 bytecode 对照

## 文件

| 文件 | 职责 |
|------|------|
| `Trace.sol` | 溯源入口：创建批次、分销/零售追加、列表查询 |
| `AgroFoodInfoItem.sol` | 单个溯源号的生产/分销/零售结构体 |
| `Producer.sol` / `Distributor.sol` / `Retailer.sol` | 角色（OpenZeppelin-like `Roles`） |
| `Roles.sol` | 地址集合 |
| `SystemInfo.sol` | 系统名/版本/描述 |

## 部署与角色

构造函数：

```text
Trace(address producer, address distributor, address retailer)
```

随后 `msg.sender`（部署者）会被 `_addProducer` / `_addDistributor` / `_addRetailer`。因此部署者同时具备三角色，才能在后端以 `contract.owner` 调用 `addXxx`。

合约层 **不是** `onlyOwner`：

- `addProducer` → `onlyProducer`（已有生产者可再添加）
- 分销/零售同理
- `newAgroFood` → `onlyProducer`，溯源号不可重复
- `addTraceInfoByDistributor` → `onlyDistributor`，溯源号必须已存在
- `addTraceInfoByRetailer` → `onlyRetailer`，溯源号必须已存在

Java 层 `/add/user`、`/delete/user` 另外校验 `AddressContext` 等于配置里的 `contract.owner`。两层需要分开表述：链上是角色 modifier，后台管理是 owner 地址头。

## 流转顺序

1. 生产者 `newAgroFood` 写入生产信息并登记溯源号
2. 分销商对 **已存在** 编号 `addTraceInfoByDistributor`
3. 零售商对 **已存在** 编号 `addTraceInfoByRetailer`

合约 **没有**「必须先分销再零售」的状态机；空分销/空零售由 `AgroFoodInfoItem` 空字段表示。后端 `GET /trace/detail` 对缺失环节返回空对象。

## 查询与 N+1

`getAgroFoodList()` 只返回溯源号数组。后端 `GET /trace/list` 对每个号再调：

- `getAgroFoodInfo`
- `getAgroFoodInfoByDistributor`
- `getAgroFoodInfoByRetailer`

没有 `getAgroFoodListDetail`。未改合约前这是已知限制，不要在接口里偷偷截断列表充性能。

## 调用路径

应用不使用 FISCO Java SDK。`HttpUtil` POST 到 WeBASE-Front，body 带 `contractName=Trace`、`contractAddress`、`contractAbi`、`user`（链上身份）、`funcName`、`funcParam`。
