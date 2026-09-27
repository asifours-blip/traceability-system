// Trace 合约访问控制与阶段规则测试。
// 运行：npm test（先用 solc@0.4.25 编译到 build/，再在 Hardhat 进程内 EVM 上跑）。
// 同一套测试可以通过 TRACE_BUILD_DIR 指向旧合约的编译产物（见 scripts/test-legacy.js），用来对比修复前后。
"use strict";

const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const hre = require("hardhat");
const { ethers } = require("ethers");

const BUILD_DIR = path.resolve(process.env.TRACE_BUILD_DIR || path.join(__dirname, "..", "build"));
const ZERO = ethers.ZeroAddress;
const STAGE_PRODUCED = 1n;
const STAGE_DISTRIBUTED = 2n;
const STAGE_RETAILED = 3n;

function loadArtifact(name) {
  const file = path.join(BUILD_DIR, name + ".json");
  if (!fs.existsSync(file)) {
    throw new Error("缺少编译产物 " + file + "，请先运行 npm run compile");
  }
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

const TraceArtifact = loadArtifact("Trace");
const ItemArtifact = loadArtifact("AgroFoodInfoItem");

// 固定的测试数据
const PRODUCE = ["农场A", "苹果", "山东烟台", "红富士", "B001", "QmProductionCert", "2026-01-01"];
const DISTRIBUTE = ["冷链物流", "0-4℃冷藏", "冷藏车", "D001", "济南一号仓", 12n, 500n, "QmInspectionReport"];
const RETAIL = ["某某超市", 20n, 300n, 30n, "INV-0001", "2026-02-01"];

const ERROR_STRING_SELECTOR = "0x08c379a0"; // Error(string)

// 取 revert 原因字符串
function revertReason(err) {
  // 估算 gas 或 eth_call 阶段失败：ethers v6 已把 Error(string) 解码到 reason
  if (err.reason != null) return err.reason;
  if (err.revert && err.revert.args) return err.revert.args[0];
  // 发送阶段才失败（ethers 会在 250ms 内复用相同请求的 estimateGas 结果）：只拿得到原始 revert 数据
  const data = err.data || (err.error && err.error.data);
  if (typeof data === "string" && data.startsWith(ERROR_STRING_SELECTOR)) {
    return ethers.AbiCoder.defaultAbiCoder().decode(["string"], "0x" + data.slice(10))[0];
  }
  return null;
}

// 断言交易（或调用）revert，且原因精确等于 expected
async function expectRevert(pending, expected) {
  let error = null;
  try {
    const tx = await pending;
    if (tx && typeof tx.wait === "function") await tx.wait();
  } catch (e) {
    error = e;
  }
  assert.ok(error, "期望 revert「" + expected + "」，但调用成功了");
  assert.equal(revertReason(error), expected, "revert 原因不符：" + (error.shortMessage || error.message));
}

// 按条目合约实际 ABI 构造伪造参数，这样对新旧两版合约都能发起同样的攻击
function forgedArgs(item, functionName, attacker) {
  const fragment = item.interface.getFunction(functionName);
  return fragment.inputs.map((input) => {
    if (input.type === "address") return attacker.address;
    if (input.type === "string") return "伪造数据";
    if (input.type.startsWith("uint")) return 999n;
    throw new Error("未处理的参数类型 " + input.type);
  });
}

function stageEvents(trace, receipt) {
  return receipt.logs
    .map((log) => {
      try {
        return trace.interface.parseLog(log);
      } catch (e) {
        return null;
      }
    })
    .filter((parsed) => parsed && parsed.name === "TraceStageRecorded")
    .map((parsed) => ({
      traceNumber: parsed.args.traceNumber,
      stage: parsed.args.stage,
      actor: parsed.args.actor,
    }));
}

describe("Trace 合约访问控制（产物目录：" + BUILD_DIR + "）", function () {
  let provider;
  let owner, producer, distributor, retailer, stranger, newcomer;
  let trace;

  before(async function () {
    provider = new ethers.BrowserProvider(hre.network.provider, undefined, { pollingInterval: 10 });
    const signers = [];
    for (let i = 0; i < 6; i++) signers.push(await provider.getSigner(i));
    [owner, producer, distributor, retailer, stranger, newcomer] = signers;
  });

  async function deployTrace(p, d, r) {
    const factory = new ethers.ContractFactory(TraceArtifact.abi, TraceArtifact.bytecode, owner);
    const contract = await factory.deploy(p, d, r);
    await contract.waitForDeployment();
    return contract;
  }

  beforeEach(async function () {
    trace = await deployTrace(producer.address, distributor.address, retailer.address);
  });

  // 生产阶段：返回条目合约地址（先 staticCall 取返回值，再真正发送交易）
  async function produce(traceNumber, signer) {
    const c = trace.connect(signer || producer);
    const itemAddress = await c.newAgroFood.staticCall(traceNumber, ...PRODUCE);
    const receipt = await (await c.newAgroFood(traceNumber, ...PRODUCE)).wait();
    assert.notEqual(await provider.getCode(itemAddress), "0x", "条目合约应已部署");
    return { itemAddress, receipt };
  }

  async function distribute(traceNumber, signer) {
    const tx = await trace.connect(signer || distributor).addTraceInfoByDistributor(traceNumber, ...DISTRIBUTE);
    return tx.wait();
  }

  async function retail(traceNumber, signer) {
    const tx = await trace.connect(signer || retailer).addTraceInfoByRetailer(traceNumber, ...RETAIL);
    return tx.wait();
  }

  function itemAt(address, signer) {
    return new ethers.Contract(address, ItemArtifact.abi, signer);
  }

  // ---------------------------------------------------------------------------
  describe("问题1：AgroFoodInfoItem 的 setter 只允许创建它的 Trace 合约调用", function () {
    const ITEM_GUARD = "AgroFoodInfoItem: caller is not the Trace contract";

    it("I1-1 用条目地址直接调用 setProducer 覆写生产数据被拒（陌生人/各角色/owner）", async function () {
      const { itemAddress } = await produce("SY-I1-1");
      for (const attacker of [stranger, producer, distributor, retailer, owner]) {
        const item = itemAt(itemAddress, attacker);
        await expectRevert(item.setProducer(...forgedArgs(item, "setProducer", attacker)), ITEM_GUARD);
      }
      const info = await trace.getAgroFoodInfo("SY-I1-1");
      assert.equal(info.companyName, PRODUCE[0], "生产数据不应被覆写");
    });

    it("I1-2 用条目地址直接调用 setDistributor 被拒（不能绕过分销角色与阶段检查）", async function () {
      const { itemAddress } = await produce("SY-I1-2");
      for (const attacker of [stranger, producer, distributor, retailer, owner]) {
        const item = itemAt(itemAddress, attacker);
        await expectRevert(item.setDistributor(...forgedArgs(item, "setDistributor", attacker)), ITEM_GUARD);
      }
      const info = await trace.getAgroFoodInfoByDistributor("SY-I1-2");
      assert.equal(info.companyName, "", "分销数据不应被写入");
      assert.equal(info.timestamp, 0n);
    });

    it("I1-3 用条目地址直接调用 setRetailer 被拒（不能绕过零售角色与阶段检查）", async function () {
      const { itemAddress } = await produce("SY-I1-3");
      await distribute("SY-I1-3");
      for (const attacker of [stranger, producer, distributor, retailer, owner]) {
        const item = itemAt(itemAddress, attacker);
        await expectRevert(item.setRetailer(...forgedArgs(item, "setRetailer", attacker)), ITEM_GUARD);
      }
      const info = await trace.getAgroFoodInfoByRetailer("SY-I1-3");
      assert.equal(info.companyName, "", "零售数据不应被写入");
      assert.equal(info.timestamp, 0n);
    });

    it("I1-4 条目记录的可写入者就是创建它的 Trace 合约", async function () {
      const { itemAddress } = await produce("SY-I1-4");
      const item = itemAt(itemAddress, stranger);
      assert.equal(await item.trace(), await trace.getAddress());
      assert.equal(await item.stage(), STAGE_PRODUCED);
    });
  });

  // ---------------------------------------------------------------------------
  describe("问题2：系统信息只允许 owner 修改", function () {
    const OWNER_GUARD = "Ownable: caller is not the owner";

    it("S2-1 非 owner（含业务角色持有者）调用 setSystemInfo 被拒，数据不变", async function () {
      const before = await trace.getSystemInfo();
      for (const attacker of [stranger, producer, distributor, retailer]) {
        await expectRevert(trace.connect(attacker).setSystemInfo("伪造系统", "v9", "被篡改"), OWNER_GUARD);
      }
      assert.deepEqual([...(await trace.getSystemInfo())], [...before]);
    });

    it("S2-2 非 owner 调用 clearSystemInfo 被拒，owner 设置的数据不被重置", async function () {
      await (await trace.connect(owner).setSystemInfo("溯源平台", "v2.0", "正式版")).wait();
      for (const attacker of [stranger, producer, distributor, retailer]) {
        await expectRevert(trace.connect(attacker).clearSystemInfo(), OWNER_GUARD);
      }
      assert.deepEqual([...(await trace.getSystemInfo())], ["溯源平台", "v2.0", "正式版"]);
    });

    it("S2-3 owner 可以修改和重置系统信息", async function () {
      await (await trace.connect(owner).setSystemInfo("溯源平台", "v2.0", "正式版")).wait();
      assert.deepEqual([...(await trace.getSystemInfo())], ["溯源平台", "v2.0", "正式版"]);
      await (await trace.connect(owner).clearSystemInfo()).wait();
      assert.deepEqual([...(await trace.getSystemInfo())], ["农产品溯源系统", "v1.0", "一个溯源农产品的系统案例"]);
    });
  });

  // ---------------------------------------------------------------------------
  describe("问题3：角色只能由 owner 授予和撤销", function () {
    const OWNER_GUARD = "Ownable: caller is not the owner";

    it("R3-1 owner() 返回部署者", async function () {
      assert.equal(await trace.owner(), owner.address);
    });

    it("R3-2 owner 自己不持有任何业务角色，也不能写入溯源数据", async function () {
      assert.equal(await trace.isProducer(owner.address), false);
      assert.equal(await trace.isDistributor(owner.address), false);
      assert.equal(await trace.isRetailer(owner.address), false);
      await expectRevert(
        trace.connect(owner).newAgroFood("SY-R3-2", ...PRODUCE),
        "ProducerRole: caller does not have the Producer role"
      );
    });

    it("R3-3 持有角色的账户不能再给别人授予同类角色（角色不再自我扩散）", async function () {
      await expectRevert(trace.connect(producer).addProducer(newcomer.address), OWNER_GUARD);
      await expectRevert(trace.connect(distributor).addDistributor(newcomer.address), OWNER_GUARD);
      await expectRevert(trace.connect(retailer).addRetailer(newcomer.address), OWNER_GUARD);
      assert.equal(await trace.isProducer(newcomer.address), false);
      assert.equal(await trace.isDistributor(newcomer.address), false);
      assert.equal(await trace.isRetailer(newcomer.address), false);
    });

    it("R3-4 陌生人调用 add* 被拒", async function () {
      await expectRevert(trace.connect(stranger).addProducer(stranger.address), OWNER_GUARD);
      await expectRevert(trace.connect(stranger).addDistributor(stranger.address), OWNER_GUARD);
      await expectRevert(trace.connect(stranger).addRetailer(stranger.address), OWNER_GUARD);
    });

    it("R3-5 非 owner（含同角色持有者）调用 remove* 被拒", async function () {
      for (const attacker of [stranger, producer, distributor, retailer]) {
        await expectRevert(trace.connect(attacker).removeProducer(producer.address), OWNER_GUARD);
        await expectRevert(trace.connect(attacker).removeDistributor(distributor.address), OWNER_GUARD);
        await expectRevert(trace.connect(attacker).removeRetailer(retailer.address), OWNER_GUARD);
      }
      assert.equal(await trace.isProducer(producer.address), true);
      assert.equal(await trace.isDistributor(distributor.address), true);
      assert.equal(await trace.isRetailer(retailer.address), true);
    });

    it("R3-6 owner 可以授予和撤销三种角色，并触发 Added/Removed 事件", async function () {
      const cases = [
        ["addProducer", "removeProducer", "isProducer", "ProducerAdded", "ProducerRemoved"],
        ["addDistributor", "removeDistributor", "isDistributor", "DistributorAdded", "DistributorRemoved"],
        ["addRetailer", "removeRetailer", "isRetailer", "RetailerAdded", "RetailerRemoved"],
      ];
      for (const [add, remove, is, addedEvent, removedEvent] of cases) {
        const added = await (await trace.connect(owner)[add](newcomer.address)).wait();
        assert.equal(await trace[is](newcomer.address), true, add);
        const addLog = added.logs.map((l) => trace.interface.parseLog(l)).find((e) => e && e.name === addedEvent);
        assert.equal(addLog.args.account, newcomer.address);

        const removed = await (await trace.connect(owner)[remove](newcomer.address)).wait();
        assert.equal(await trace[is](newcomer.address), false, remove);
        const removeLog = removed.logs.map((l) => trace.interface.parseLog(l)).find((e) => e && e.name === removedEvent);
        assert.equal(removeLog.args.account, newcomer.address);
      }
    });

    it("R3-7 remove 之后该地址立刻不能写入", async function () {
      await produce("SY-R3-7a");
      await produce("SY-R3-7b");
      await distribute("SY-R3-7b");

      await (await trace.connect(owner).removeProducer(producer.address)).wait();
      await expectRevert(
        trace.connect(producer).newAgroFood("SY-R3-7c", ...PRODUCE),
        "ProducerRole: caller does not have the Producer role"
      );

      await (await trace.connect(owner).removeDistributor(distributor.address)).wait();
      await expectRevert(
        trace.connect(distributor).addTraceInfoByDistributor("SY-R3-7a", ...DISTRIBUTE),
        "DistributorRole: caller does not have the Distributor role"
      );

      await (await trace.connect(owner).removeRetailer(retailer.address)).wait();
      await expectRevert(
        trace.connect(retailer).addTraceInfoByRetailer("SY-R3-7b", ...RETAIL),
        "RetailerRole: caller does not have the Retailer role"
      );
    });

    it("R3-8 不能重复授予、不能撤销不存在的角色、不能授予 0 地址", async function () {
      await expectRevert(trace.connect(owner).addProducer(producer.address), "Roles: account already has role");
      await expectRevert(trace.connect(owner).removeProducer(stranger.address), "Roles: account does not have role");
      await expectRevert(trace.connect(owner).addProducer(ZERO), "Roles: account is the zero address");
    });

    it("R3-9 构造参数为 0 地址时不授予角色，之后由 owner 补授", async function () {
      const bare = await deployTrace(ZERO, ZERO, ZERO);
      assert.equal(await bare.isProducer(ZERO), false);
      assert.equal(await bare.isProducer(owner.address), false);
      await (await bare.connect(owner).addProducer(producer.address)).wait();
      assert.equal(await bare.isProducer(producer.address), true);
    });

    it("R3-10 角色持有者仍可以 renounce 放弃自己的角色", async function () {
      await (await trace.connect(producer).renounceProducer()).wait();
      await (await trace.connect(distributor).renounceDistributor()).wait();
      await (await trace.connect(retailer).renounceRetailer()).wait();
      assert.equal(await trace.isProducer(producer.address), false);
      assert.equal(await trace.isDistributor(distributor.address), false);
      assert.equal(await trace.isRetailer(retailer.address), false);
    });
  });

  // ---------------------------------------------------------------------------
  describe("问题4：阶段顺序与只写一次", function () {
    it("P4-1 溯源号未经生产登记时不能写入分销", async function () {
      await expectRevert(
        trace.connect(distributor).addTraceInfoByDistributor("SY-P4-1", ...DISTRIBUTE),
        "Trace: traceNumber does not exist"
      );
    });

    it("P4-2 零售不能早于分销", async function () {
      await produce("SY-P4-2");
      await expectRevert(
        trace.connect(retailer).addTraceInfoByRetailer("SY-P4-2", ...RETAIL),
        "Trace: distribution not recorded yet"
      );
      const info = await trace.getAgroFoodInfoByRetailer("SY-P4-2");
      assert.equal(info.companyName, "");
    });

    it("P4-3 分销只能写一次，已写数据不会被覆盖", async function () {
      await produce("SY-P4-3");
      await distribute("SY-P4-3");
      await expectRevert(
        trace.connect(distributor).addTraceInfoByDistributor("SY-P4-3", "另一家物流", "常温", "货车", "D999", "别处", 1n, 1n, "QmOther"),
        "Trace: distribution already recorded"
      );
      assert.equal((await trace.getAgroFoodInfoByDistributor("SY-P4-3")).companyName, DISTRIBUTE[0]);
    });

    it("P4-4 零售只能写一次，已写数据不会被覆盖", async function () {
      await produce("SY-P4-4");
      await distribute("SY-P4-4");
      await retail("SY-P4-4");
      await expectRevert(
        trace.connect(retailer).addTraceInfoByRetailer("SY-P4-4", "另一家超市", 1n, 1n, 1n, "INV-9999", "2026-03-01"),
        "Trace: retail already recorded"
      );
      assert.equal((await trace.getAgroFoodInfoByRetailer("SY-P4-4")).companyName, RETAIL[0]);
    });

    it("P4-5 零售之后不能再补写分销", async function () {
      await produce("SY-P4-5");
      await distribute("SY-P4-5");
      await retail("SY-P4-5");
      await expectRevert(
        trace.connect(distributor).addTraceInfoByDistributor("SY-P4-5", ...DISTRIBUTE),
        "Trace: distribution already recorded"
      );
    });

    it("P4-6 重复的溯源号被拒，原生产数据不变", async function () {
      await produce("SY-P4-6");
      await expectRevert(
        trace.connect(producer).newAgroFood("SY-P4-6", "别的农场", "梨", "河北", "鸭梨", "B999", "QmX", "2026-05-01"),
        "Trace: traceNumber already exists"
      );
      assert.equal((await trace.getAgroFoodInfo("SY-P4-6")).companyName, PRODUCE[0]);
      assert.deepEqual([...(await trace.getAgroFoodList())], ["SY-P4-6"]);
    });

    it("P4-7 空溯源号在生产、分销、零售三个入口都被拒", async function () {
      await expectRevert(trace.connect(producer).newAgroFood("", ...PRODUCE), "Trace: traceNumber is empty");
      await expectRevert(
        trace.connect(distributor).addTraceInfoByDistributor("", ...DISTRIBUTE),
        "Trace: traceNumber is empty"
      );
      await expectRevert(trace.connect(retailer).addTraceInfoByRetailer("", ...RETAIL), "Trace: traceNumber is empty");
      assert.deepEqual([...(await trace.getAgroFoodList())], []);
    });

    it("P4-8 读取：未写入的阶段返回空值，不存在的溯源号 revert", async function () {
      await produce("SY-P4-8");
      const d = await trace.getAgroFoodInfoByDistributor("SY-P4-8");
      assert.deepEqual([...d], ["", "", "", "", "", 0n, 0n, "", 0n]);
      const r = await trace.getAgroFoodInfoByRetailer("SY-P4-8");
      assert.deepEqual([...r], ["", 0n, 0n, 0n, "", "", 0n]);
      assert.deepEqual([...(await trace.getStageActors("SY-P4-8"))], [producer.address, ZERO, ZERO]);

      const missing = "Trace: traceNumber does not exist";
      await expectRevert(trace.getAgroFoodInfo("SY-NOPE"), missing);
      await expectRevert(trace.getAgroFoodInfoByDistributor("SY-NOPE"), missing);
      await expectRevert(trace.getAgroFoodInfoByRetailer("SY-NOPE"), missing);
      await expectRevert(trace.getStageActors("SY-NOPE"), missing);
    });
  });

  // ---------------------------------------------------------------------------
  describe("正常三阶段流程", function () {
    it("F-1 三阶段写入后读回的数据完整正确", async function () {
      await produce("SY-F-1");
      await distribute("SY-F-1");
      await retail("SY-F-1");

      const p = await trace.getAgroFoodInfo("SY-F-1");
      assert.deepEqual([...p].slice(0, 7), PRODUCE);
      assert.ok(p.timestamp > 0n);

      const d = await trace.getAgroFoodInfoByDistributor("SY-F-1");
      assert.deepEqual([...d].slice(0, 8), DISTRIBUTE);
      assert.ok(d.timestamp > 0n);

      const r = await trace.getAgroFoodInfoByRetailer("SY-F-1");
      assert.deepEqual([...r].slice(0, 6), RETAIL);
      assert.ok(r.timestamp > 0n);

      assert.deepEqual([...(await trace.getAgroFoodList())], ["SY-F-1"]);
    });

    it("F-2 getStageActors 返回各阶段实际写入者", async function () {
      // 用 owner 新授予的第二个生产者写入，确认记录的是真实调用者而不是构造参数
      await (await trace.connect(owner).addProducer(newcomer.address)).wait();
      await produce("SY-F-2", newcomer);
      assert.deepEqual([...(await trace.getStageActors("SY-F-2"))], [newcomer.address, ZERO, ZERO]);
      await distribute("SY-F-2");
      assert.deepEqual([...(await trace.getStageActors("SY-F-2"))], [newcomer.address, distributor.address, ZERO]);
      await retail("SY-F-2");
      const actors = await trace.getStageActors("SY-F-2");
      assert.equal(actors.producer, newcomer.address);
      assert.equal(actors.distributor, distributor.address);
      assert.equal(actors.retailer, retailer.address);
    });

    it("F-3 每个阶段写入成功时 emit TraceStageRecorded(traceNumber, stage, actor)", async function () {
      const { receipt: produced } = await produce("SY-F-3");
      assert.deepEqual(stageEvents(trace, produced), [
        { traceNumber: "SY-F-3", stage: STAGE_PRODUCED, actor: producer.address },
      ]);
      assert.deepEqual(stageEvents(trace, await distribute("SY-F-3")), [
        { traceNumber: "SY-F-3", stage: STAGE_DISTRIBUTED, actor: distributor.address },
      ]);
      assert.deepEqual(stageEvents(trace, await retail("SY-F-3")), [
        { traceNumber: "SY-F-3", stage: STAGE_RETAILED, actor: retailer.address },
      ]);
    });

    it("F-4 getAgroFoodList 按登记顺序返回全部溯源号", async function () {
      await produce("SY-F-4a");
      await produce("SY-F-4b");
      await produce("SY-F-4c");
      assert.deepEqual([...(await trace.getAgroFoodList())], ["SY-F-4a", "SY-F-4b", "SY-F-4c"]);
    });
  });
});
