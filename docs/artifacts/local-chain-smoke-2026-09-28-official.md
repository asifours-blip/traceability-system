# 本地隔离链冒烟记录 2026-09-28

由 `scripts/local-chain/smoke.sh` 生成；真实 FISCO BCOS 节点 + 真实 WeBASE-Front，无替身。

- runId: `"20260928112045"`
- frontUrl: `"http://127.0.0.1:5102/WeBASE-Front"`
- groupId: `1`
- webaseFrontVersion: `"v1.5.5"`
- nodeVersion: `{"FISCO-BCOS Version": "2.7.2", "Supported Version": "2.7.2", "Chain Id": "1", "Build Time": "20210201 10:03:03", "Build Type": "Linux/clang/Release", "Git Branch": "HEAD", "Git Commit Hash": "4c8a5bbe44c19db8a002017ff9dbb16d3d28e9da"}`
- solc: `"0.4.25+commit.59dbf8f1.Emscripten.clang"`
- blockNumberAtStart: `0`
- blockNumberAtEnd: `18`
- contractAddress: `0x957e6b940567c39b80ba1900982d8afd56a8cba6`
- owner: `0x3c4d05108407bc5e36f59997ebad127e8f3efe9c`
- producer: `0x99989f59a3b8b54bfda3ecf2e24c8176f4d37844`
- distributor: `0x99d06f07dac1de2155bf0c52446ef50eb1d2ab4a`
- retailer: `0x53974f31e52609dee64da1c8b3ee512fb51133b5`
- outsider: `0x11d95acf09cf2cb236713296269202e8c8952d58`

结果：27/27 步符合预期

| 步骤 | 期望 | HTTP | transactionHash | blockNumber | status | message / errorMessage | 符合 |
|---|---|---|---|---|---|---|---|
| deploy Trace via /contract/deploy | contract address | 200 | `` |  |  | "0x957e6b940567c39b80ba1900982d8afd56a8cba6" | 是 |
| owner() == deployer | 0x3c4d05108407bc5e36f59997ebad127e8f3efe9c | 200 | `` |  |  | ["0x3c4d05108407bc5e36f59997ebad127e8f3efe9c"] | 是 |
| 非 owner 授予角色 | revert receipt: Ownable: caller is not the owner | 200 | `0x160c7d12811df77e6bf786c7aa6520380cb48e9fd1f9c772cb117745ff97899e` | 2 | 0x16 | Ownable: caller is not the owner | 是 |
| owner 授予 Producer | success receipt status=0x0 | 200 | `0xbe9186e3ecc682497c64b08e0be92ef74618bc1ff7365cf71e880a20e355f982` | 3 | 0x0 | Success | 是 |
| owner 授予 Distributor | success receipt status=0x0 | 200 | `0x28e12bb308193d0d9a0aa2e4a56f3dcde9387c3573412083d47b4b7027807dc3` | 4 | 0x0 | Success | 是 |
| owner 授予 Retailer | success receipt status=0x0 | 200 | `0x85fb5484bca28f0d2461b7f8faab1f000916cc4f5e66349a0c95337ca1dec9d6` | 5 | 0x0 | Success | 是 |
| 无角色账户写生产 | revert receipt: ProducerRole: caller does not have the Producer role | 200 | `0xa015f3c385ad844fda0594fc62ff2dfc009fc567c0d71c4efce5e840bdd745a3` | 6 | 0x16 | ProducerRole: caller does not have the Producer role | 是 |
| owner 写生产（owner 无业务角色） | revert receipt: ProducerRole: caller does not have the Producer role | 200 | `0x2bf6db2affa2e674bd6259405e473da2a8fac4302c84ce9964017c657c418113` | 7 | 0x16 | ProducerRole: caller does not have the Producer role | 是 |
| 生产前分销 | revert receipt: Trace: traceNumber does not exist | 200 | `0x2c581f95c7b106021ef8f677e8ed044560a39dabf0f2673802bc374c9508d40b` | 8 | 0x16 | Trace: traceNumber does not exist | 是 |
| 生产 newAgroFood | success receipt status=0x0 | 200 | `0xdc58306657568e9a4aeb69c856869a112e29a730d012e56b7e25ab89751327a0` | 9 | 0x0 | Success | 是 |
| 重复生产 | revert receipt: Trace: traceNumber already exists | 200 | `0x954e846cec6cad986b1e64cb08c3e64cc3c1057ecd94c7d9b24a7faa833174c0` | 10 | 0x16 | Trace: traceNumber already exists | 是 |
| 分销前零售 | revert receipt: Trace: distribution not recorded yet | 200 | `0xd52bb32e26db879abff8d31e8eee3bb0ee333418cf7e4d35d8e95b80ad2e2c16` | 11 | 0x16 | Trace: distribution not recorded yet | 是 |
| 生产者写分销（越权） | revert receipt: DistributorRole: caller does not have the Distributor role | 200 | `0xcec0f8c81473d6cf492a1d6a2aef9a63d3ffeda12acdcc2f1bba2d88b0ab96af` | 12 | 0x16 | DistributorRole: caller does not have the Distributor role | 是 |
| 分销 addTraceInfoByDistributor | success receipt status=0x0 | 200 | `0xf040539361449131a08dc4e21651171207d755511d0c7da745d6ebb76ebaca84` | 13 | 0x0 | Success | 是 |
| 重复分销 | revert receipt: Trace: distribution already recorded | 200 | `0xda4f57d39a51ebf3d48dda2346283562f8d7f60494806f76e099d7dc08efe21f` | 14 | 0x16 | Trace: distribution already recorded | 是 |
| 零售 addTraceInfoByRetailer | success receipt status=0x0 | 200 | `0xf19a108689b4c8a1abc577959d40fa0a52d44aff5da406746701bd3f5b018816` | 15 | 0x0 | Success | 是 |
| 重复零售 | revert receipt: Trace: retail already recorded | 200 | `0xd85e78c6bf80b3a34f9ffbea1cf9093c3932c1d3d835e154aa77eec9e108f3b3` | 16 | 0x16 | Trace: retail already recorded | 是 |
| 零售后补分销 | revert receipt: Trace: distribution already recorded | 200 | `0x7bef6d5e61f919806459fcfbe568eaee3529b2316fc0f8835a1505e542464965` | 17 | 0x16 | Trace: distribution already recorded | 是 |
| getStageActors 三阶段写入者 | producer/distributor/retailer | 200 | `` |  |  | ["0x99989f59a3b8b54bfda3ecf2e24c8176f4d37844", "0x99d06f07dac1de2155bf0c52446ef50eb1d2ab4a… | 是 |
| 只读调用 revert 的响应形态 | ["Call contract return error: ..."] | 200 | `` |  |  | ["Call contract return error: Trace: traceNumber does not exist"] | 是 |
| 签名用户不存在 | HTTP 422 code=201015 | 422 | `` |  |  | user's privateKey is null | 是 |
| 参数个数错误 | HTTP 422 code=201151 | 422 | `` |  |  |  cannot encode in encodeMethodFromString with appropriate interface ABI, make sure params … | 是 |
| 参数类型错误（uint 传非数字） | HTTP 422 code=201151 | 422 | `` |  |  |  cannot encode in encodeMethodFromString with appropriate interface ABI, make sure params … | 是 |
| 按哈希查回执（生产交易） | HTTP 200 同一回执 | 200 | `0xdc58306657568e9a4aeb69c856869a112e29a730d012e56b7e25ab89751327a0` | 9 | 0x0 | Success | 是 |
| 按哈希查回执（不存在的哈希） | HTTP 500 code=500 | 500 | `` |  |  |  | 是 |
| 共识停滞时发交易（2/4 节点停止） | HTTP 200 statusOK=false transactionHash=null 超时 | 200 | `` | 0 | 50001 | Transaction receipt timeout | 是 |
| 节点恢复后查证超时交易 | getStageActors[0] == producer（交易最终上链） | 200 | `` |  |  | ["0x99989f59a3b8b54bfda3ecf2e24c8176f4d37844", "0x0000000000000000000000000000000000000000… | 是 |
