// v3 指定交接对象的链上权限和改派规则。
"use strict";
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const hre = require("hardhat");
const { ethers } = require("ethers");
const suite = process.env.TRACE_SOURCES_DIR ? describe.skip : describe;
const build = path.join(__dirname, "..", "build");
const artifact = process.env.TRACE_SOURCES_DIR ? null : JSON.parse(fs.readFileSync(path.join(build, "TraceV3.json"), "utf8"));
const itemArtifact = process.env.TRACE_SOURCES_DIR ? null : JSON.parse(fs.readFileSync(path.join(build, "AgroFoodInfoItemV3.json"), "utf8"));
const produce = ["农场", "苹果", "产地", "品种", "P1", "CID-P", "2026-01-01"];
const distribute = ["物流", "冷藏", "货车", "D1", "仓库", 10n, 100n, "CID-D"];
const retail = ["超市", 20n, 50n, 30n, "INV", "2026-02-01"];

async function rejected(promise, reason) {
  let error;
  try {
    const tx = await promise;
    if (tx && tx.wait) await tx.wait();
  } catch (caught) { error = caught; }
  assert.ok(error, "预期链上拒绝");
  const raw = error.data || error.error?.data;
  const decoded = typeof raw === "string" && raw.startsWith("0x08c379a0")
    ? ethers.AbiCoder.defaultAbiCoder().decode(["string"], "0x" + raw.slice(10))[0] : null;
  assert.equal(error.reason || error.revert?.args?.[0] || decoded, reason);
}

suite("TraceV3 链上指定交接", function () {
  let owner, producer, distributor, distributor2, retailer, retailer2, outsider, trace;
  before(async function () {
    const provider = new ethers.BrowserProvider(hre.network.provider);
    [owner, producer, distributor, distributor2, retailer, retailer2, outsider] = await Promise.all(
      Array.from({ length: 7 }, (_, index) => provider.getSigner(index)));
  });
  beforeEach(async function () {
    const factory = new ethers.ContractFactory(artifact.abi, artifact.bytecode, owner);
    trace = await factory.deploy(producer.address, distributor.address, retailer.address);
    await trace.waitForDeployment();
    await (await trace.addDistributor(distributor2.address)).wait();
    await (await trace.addRetailer(retailer2.address)).wait();
  });
  async function start(number = "V3-1", designated = distributor) {
    return (await trace.connect(producer).newAgroFood(number, ...produce, designated.address)).wait();
  }
  async function middle(number = "V3-1", signer = distributor, designated = retailer) {
    return (await trace.connect(signer).addTraceInfoByDistributor(number, ...distribute, designated.address)).wait();
  }
  async function end(number = "V3-1", signer = retailer) {
    return (await trace.connect(signer).addTraceInfoByRetailer(number, ...retail)).wait();
  }
  it("V3-1 生产必须指定持有分销角色的非零地址", async function () {
    await rejected(trace.connect(producer).newAgroFood("A", ...produce, outsider.address), "Trace: designated distributor lacks role");
    await rejected(trace.connect(producer).newAgroFood("A", ...produce, ethers.ZeroAddress), "Trace: designated distributor lacks role");
  });
  it("V3-2 未指定但有角色的分销商不能抢写", async function () {
    await start();
    await rejected(trace.connect(distributor2).addTraceInfoByDistributor("V3-1", ...distribute, retailer.address),
      "Trace: caller is not the designated distributor");
    await middle();
    assert.equal((await trace.getStageActors("V3-1"))[1].toLowerCase(), distributor.address.toLowerCase());
  });
  it("V3-3 分销必须指定持有零售角色的非零地址，未指定零售商不能抢写", async function () {
    await start();
    await rejected(trace.connect(distributor).addTraceInfoByDistributor("V3-1", ...distribute, outsider.address),
      "Trace: designated retailer lacks role");
    await rejected(trace.connect(distributor).addTraceInfoByDistributor("V3-1", ...distribute, ethers.ZeroAddress),
      "Trace: designated retailer lacks role");
    await middle();
    await rejected(trace.connect(retailer2).addTraceInfoByRetailer("V3-1", ...retail),
      "Trace: caller is not the designated retailer");
    await end();
  });
  it("V3-4 只有生产者能在分销写入前改派，事件和 getter 反映结果", async function () {
    await start();
    await rejected(trace.connect(distributor).redesignateDistributor("V3-1", distributor2.address),
      "Trace: caller is not the producer");
    await rejected(trace.connect(producer).redesignateDistributor("V3-1", outsider.address),
      "Trace: designated distributor lacks role");
    const receipt = await (await trace.connect(producer).redesignateDistributor("V3-1", distributor2.address)).wait();
    assert.ok(receipt.logs.some(log => { try { return trace.interface.parseLog(log)?.name === "DistributorRedesignated"; } catch { return false; } }));
    assert.equal((await trace.getDesignations("V3-1"))[0].toLowerCase(), distributor2.address.toLowerCase());
    await rejected(trace.connect(distributor).addTraceInfoByDistributor("V3-1", ...distribute, retailer.address),
      "Trace: caller is not the designated distributor");
    await middle("V3-1", distributor2);
    await rejected(trace.connect(producer).redesignateDistributor("V3-1", distributor.address),
      "Trace: distribution already recorded");
  });
  it("V3-6 条目 setter 只能由创建它的 TraceV3 调用", async function () {
    const itemAddress = await trace.connect(producer).newAgroFood.staticCall("V3-I", ...produce, distributor.address);
    await start("V3-I");
    const item = new ethers.Contract(itemAddress, itemArtifact.abi, distributor);
    await rejected(item.setDesignatedDistributor(distributor2.address),
      "AgroFoodInfoItem: caller is not the Trace contract");
    await rejected(item.setDistributor(distributor.address, ...distribute, retailer.address),
      "AgroFoodInfoItem: caller is not the Trace contract");
    assert.equal((await trace.getDesignations("V3-I"))[0].toLowerCase(), distributor.address.toLowerCase());
  });
  it("V3-7 仅 owner 管理角色，撤销后指定账户也不能写", async function () {
    assert.equal(await trace.isProducer(owner.address), false);
    assert.equal(await trace.isDistributor(owner.address), false);
    assert.equal(await trace.isRetailer(owner.address), false);
    await rejected(trace.connect(distributor).addProducer(outsider.address),
      "Ownable: caller is not the owner");
    await rejected(trace.connect(distributor).addDistributor(outsider.address),
      "Ownable: caller is not the owner");
    await rejected(trace.connect(distributor).addRetailer(outsider.address),
      "Ownable: caller is not the owner");
    await start("V3-R");
    await (await trace.removeDistributor(distributor.address)).wait();
    await rejected(trace.connect(distributor).addTraceInfoByDistributor("V3-R", "预检", ...distribute.slice(1), retailer.address),
      "DistributorRole: caller does not have the Distributor role");
    await (await trace.addDistributor(distributor.address)).wait();
    const receipt = await middle("V3-R");
    assert.ok(receipt.logs.some(log => { try { return trace.interface.parseLog(log)?.name === "TraceStageRecorded"; } catch { return false; } }));
    await (await trace.removeRetailer(retailer.address)).wait();
    await rejected(trace.connect(retailer).addTraceInfoByRetailer("V3-R", ...retail),
      "RetailerRole: caller does not have the Retailer role");
  });
  it("V3-8 三阶段事件与 actors 保留；空溯源号拒绝", async function () {
    await rejected(trace.connect(producer).newAgroFood("", ...produce, distributor.address), "Trace: traceNumber is empty");
    const receipts = [await start("V3-E"), await middle("V3-E"), await end("V3-E")];
    for (let i = 0; i < receipts.length; i++) {
      const events = receipts[i].logs.flatMap(log => { try { const e = trace.interface.parseLog(log); return e?.name === "TraceStageRecorded" ? [e] : []; } catch { return []; } });
      assert.equal(events.length, 1);
      assert.equal(events[0].args.stage, BigInt(i + 1));
      assert.equal(events[0].args.traceNumber, "V3-E");
      assert.equal(events[0].args.actor.toLowerCase(), [producer, distributor, retailer][i].address.toLowerCase());
    }
    assert.deepEqual([...await trace.getStageActors("V3-E")].map(a => a.toLowerCase()),
      [producer, distributor, retailer].map(signer => signer.address.toLowerCase()));
  });
  it("V3-5 v2 阶段顺序、一次写入、owner 无业务角色保持成立", async function () {
    await rejected(trace.connect(owner).newAgroFood("V3-1", ...produce, distributor.address),
      "ProducerRole: caller does not have the Producer role");
    await rejected(trace.connect(distributor).addTraceInfoByDistributor("V3-ABSENT", ...distribute, retailer.address),
      "Trace: traceNumber does not exist");
    await start();
    await rejected(trace.connect(retailer).addTraceInfoByRetailer("V3-1", "预检", ...retail.slice(1)),
      "Trace: distribution not recorded yet");
    await rejected(trace.connect(producer).newAgroFood("V3-1", ...produce, distributor.address),
      "Trace: traceNumber already exists");
    await middle();
    await rejected(trace.connect(distributor).addTraceInfoByDistributor("V3-1", ...distribute, retailer.address),
      "Trace: distribution already recorded");
    await end();
    await rejected(trace.connect(retailer).addTraceInfoByRetailer("V3-1", ...retail),
      "Trace: retail already recorded");
  });
});
