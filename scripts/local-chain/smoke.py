#!/usr/bin/env python3
"""本地隔离链冒烟：真实 FISCO BCOS 节点 + 真实 WeBASE-Front，不用任何替身。

步骤：
  1. 在 WeBASE-Front 里新建本次运行专用的托管账户（owner / producer / distributor / retailer / outsider）
  2. 用 contracts/build/Trace.json（solc 0.4.25 编译产物）的 bytecode + ABI，经 WeBASE-Front POST /contract/deploy 部署
  3. owner 授予三种角色 -> 生产 -> 分销 -> 零售
  4. 反例：越权写入、阶段乱序、重复写入，都必须得到失败回执
  5. 核验 /trans/handle 的契约：签名用户不存在、参数错误、按哈希查回执、查不到的哈希
  6. （默认开启）共识停滞：停掉 2/4 个节点后发交易，记录 WeBASE 的超时响应，恢复节点后查证该交易是否最终上链

每一步都断言期望结果，任何一步不符即以非 0 退出。回执摘要写入 docs/artifacts/，不写私钥。
只用 Python 标准库。
"""
import argparse
import datetime
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

ZERO = "0x" + "0" * 40
# TraceStageRecorded(string,uint8,address) 的 topic0
STAGE_EVENT_TOPIC = "0x8993ae0b59519d68a477cf2e78467d1498be530908faea39856dba55e9bb854c"


class Front:
    def __init__(self, base, group):
        self.base = base
        self.group = group

    def request(self, method, path, body=None, timeout=90):
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, resp.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8")


def parse(text):
    try:
        return json.loads(text)
    except ValueError:
        return text


def receipt_summary(obj):
    """只保留回执里与交易状态相关的字段；不带 input/logsBloom 等大字段。"""
    if not isinstance(obj, dict):
        return obj
    keys = ["transactionHash", "blockNumber", "status", "statusMsg", "statusOK", "message", "from", "to"]
    out = {k: obj.get(k) for k in keys if k in obj}
    if "logs" in obj and isinstance(obj["logs"], list):
        out["stageEvents"] = sum(1 for log in obj["logs"] if STAGE_EVENT_TOPIC in (log.get("topics") or []))
    for k in ("code", "errorMessage"):
        if k in obj:
            out[k] = obj[k]
    return out


class Smoke:
    def __init__(self, args):
        self.args = args
        self.front = Front(args.front_url, args.group)
        self.steps = []
        self.failures = []
        self.run_id = datetime.datetime.now().strftime("%Y%m%d%H%M%S")
        with open(args.artifact, encoding="utf-8") as f:
            art = json.load(f)
        self.abi = art["abi"]
        self.bytecode = art["bytecode"]
        self.compiler = art.get("compiler")
        self.accounts = {}
        self.contract = None

    # ---------- 基础 ----------
    def record(self, name, expect, status, body, ok, extra=None):
        entry = {"step": name, "expect": expect, "httpStatus": status, "ok": ok}
        entry.update(receipt_summary(body) if isinstance(body, dict) else {"body": body})
        if extra:
            entry.update(extra)
        self.steps.append(entry)
        flag = "OK  " if ok else "FAIL"
        print(f"[{flag}] {name}: http={status} "
              f"hash={entry.get('transactionHash')} block={entry.get('blockNumber')} "
              f"status={entry.get('status')} msg={entry.get('message') or entry.get('errorMessage') or entry.get('body')}")
        if not ok:
            self.failures.append(name)

    def new_account(self, role):
        name = f"e2e_{self.run_id}_{role}"
        status, text = self.front.request("GET", f"/privateKey?type=0&userName={name}")
        body = parse(text)
        if status != 200 or not isinstance(body, dict) or "address" not in body:
            raise SystemExit(f"创建托管账户 {name} 失败：{status} {text}")
        # 响应里的 privateKey 是 WeBASE 加密后的值，也不写入产物
        self.accounts[role] = body["address"]
        return body["address"]

    def trans(self, user, func, params, contract=None):
        body = {"groupId": self.args.group, "user": user, "contractName": "Trace",
                "contractAddress": contract or self.contract, "contractAbi": self.abi,
                "funcName": func, "funcParam": params}
        status, text = self.front.request("POST", "/trans/handle", body)
        return status, parse(text)

    def expect_success(self, name, user, func, params, stage_event=False):
        status, body = self.trans(user, func, params)
        ok = (status == 200 and isinstance(body, dict) and body.get("statusOK") is True
              and body.get("status") == "0x0" and bool(body.get("transactionHash"))
              and int(body.get("blockNumber") or 0) > 0)
        if ok and stage_event:
            ok = any(STAGE_EVENT_TOPIC in (log.get("topics") or []) for log in body.get("logs") or [])
        self.record(name, "success receipt status=0x0", status, body, ok)
        return body

    def expect_revert(self, name, user, func, params, reason):
        status, body = self.trans(user, func, params)
        ok = (status == 200 and isinstance(body, dict) and body.get("statusOK") is False
              and body.get("status") not in (None, "0x0") and bool(body.get("transactionHash"))
              and int(body.get("blockNumber") or 0) > 0 and body.get("message") == reason)
        self.record(name, f"revert receipt: {reason}", status, body, ok)
        return body

    def call(self, func, params):
        status, body = self.trans(self.accounts["owner"], func, params)
        return status, body

    # ---------- 步骤 ----------
    def run(self):
        f = self.front
        _, block0 = f.request("GET", f"/{self.args.group}/web3/blockNumber")
        _, client_version = f.request("GET", f"/{self.args.group}/web3/clientVersion")
        self.meta = {"runId": self.run_id, "frontUrl": self.args.front_url, "groupId": self.args.group,
                     "webaseFrontVersion": self.args.front_version, "nodeVersion": parse(client_version),
                     "solc": self.compiler, "blockNumberAtStart": parse(block0)}

        for role in ("owner", "producer", "distributor", "retailer", "outsider"):
            self.new_account(role)
        acc = self.accounts

        # 部署：构造参数全传 0 地址，角色全部由 owner 事后授予
        status, text = f.request("POST", "/contract/deploy", {
            "groupId": self.args.group, "user": acc["owner"], "contractName": "Trace",
            "abiInfo": self.abi, "bytecodeBin": self.bytecode, "funcParam": [ZERO, ZERO, ZERO]})
        address = text.strip().strip('"')
        ok = status == 200 and address.startswith("0x") and len(address) == 42 and address != ZERO
        self.record("deploy Trace via /contract/deploy", "contract address", status, address, ok)
        if not ok:
            return
        self.contract = address
        _, owner_on_chain = self.call("owner", [])
        self.record("owner() == deployer", acc["owner"], 200, owner_on_chain,
                    isinstance(owner_on_chain, list) and owner_on_chain[0].lower() == acc["owner"].lower())

        tn = f"E2E-{self.run_id}"
        prod = [tn, "农场A", "苹果", "烟台", "红富士", "B001", "QmCert", "2026-09-28"]
        # uint 参数按后端的发法传 JSON 数字（后端 DTO 是 Long）
        dist = [tn, "仓配B", "冷藏", "冷链车", "D001", "济南", 10, 100, "QmReport"]
        retail = [tn, "门店C", 20, 5, 7, "INV-001", "2026-09-29"]

        # 越权：非 owner 授权
        self.expect_revert("非 owner 授予角色", acc["producer"], "addProducer", [acc["outsider"]],
                           "Ownable: caller is not the owner")
        # owner 授予三种角色
        self.expect_success("owner 授予 Producer", acc["owner"], "addProducer", [acc["producer"]])
        self.expect_success("owner 授予 Distributor", acc["owner"], "addDistributor", [acc["distributor"]])
        self.expect_success("owner 授予 Retailer", acc["owner"], "addRetailer", [acc["retailer"]])

        # 越权写入：无角色账户、owner 自己（owner 不自动持有业务角色）
        self.expect_revert("无角色账户写生产", acc["outsider"], "newAgroFood", prod,
                           "ProducerRole: caller does not have the Producer role")
        self.expect_revert("owner 写生产（owner 无业务角色）", acc["owner"], "newAgroFood", prod,
                           "ProducerRole: caller does not have the Producer role")
        # 乱序：生产之前分销
        self.expect_revert("生产前分销", acc["distributor"], "addTraceInfoByDistributor", dist,
                           "Trace: traceNumber does not exist")

        self.expect_success("生产 newAgroFood", acc["producer"], "newAgroFood", prod, stage_event=True)
        produce_hash = self.steps[-1].get("transactionHash")

        self.expect_revert("重复生产", acc["producer"], "newAgroFood", prod, "Trace: traceNumber already exists")
        self.expect_revert("分销前零售", acc["retailer"], "addTraceInfoByRetailer", retail,
                           "Trace: distribution not recorded yet")
        self.expect_revert("生产者写分销（越权）", acc["producer"], "addTraceInfoByDistributor", dist,
                           "DistributorRole: caller does not have the Distributor role")

        self.expect_success("分销 addTraceInfoByDistributor", acc["distributor"], "addTraceInfoByDistributor",
                            dist, stage_event=True)
        self.expect_revert("重复分销", acc["distributor"], "addTraceInfoByDistributor", dist,
                           "Trace: distribution already recorded")

        self.expect_success("零售 addTraceInfoByRetailer", acc["retailer"], "addTraceInfoByRetailer",
                            retail, stage_event=True)
        self.expect_revert("重复零售", acc["retailer"], "addTraceInfoByRetailer", retail,
                           "Trace: retail already recorded")
        self.expect_revert("零售后补分销", acc["distributor"], "addTraceInfoByDistributor", dist,
                           "Trace: distribution already recorded")

        # 链上读回：各阶段写入者
        status, actors = self.call("getStageActors", [tn])
        expected = [acc["producer"], acc["distributor"], acc["retailer"]]
        ok = status == 200 and isinstance(actors, list) and [a.lower() for a in actors] == [e.lower() for e in expected]
        self.record("getStageActors 三阶段写入者", "producer/distributor/retailer", status, actors, ok)
        status, missing = self.call("getStageActors", ["E2E-NOT-EXIST"])
        ok = status == 200 and isinstance(missing, list) and len(missing) == 1 \
            and missing[0] == "Call contract return error: Trace: traceNumber does not exist"
        self.record("只读调用 revert 的响应形态", "[\"Call contract return error: ...\"]", status, missing, ok)

        # ---------- /trans/handle 契约：请求被 WeBASE 拒绝（没有发出交易） ----------
        status, body = self.trans("0x" + "1" * 40, "newAgroFood", [tn + "-X"] + prod[1:])
        ok = status == 422 and isinstance(body, dict) and body.get("code") == 201015
        self.record("签名用户不存在", "HTTP 422 code=201015", status, body, ok)
        status, body = self.trans(acc["producer"], "newAgroFood", [tn + "-Y"])
        ok = status == 422 and isinstance(body, dict) and body.get("code") == 201151
        self.record("参数个数错误", "HTTP 422 code=201151", status, body, ok)
        status, body = self.trans(acc["distributor"], "addTraceInfoByDistributor", dist[:6] + ["not-a-number"] + dist[7:])
        ok = status == 422 and isinstance(body, dict) and body.get("code") == 201151
        self.record("参数类型错误（uint 传非数字）", "HTTP 422 code=201151", status, body, ok)

        # ---------- 按哈希查回执 ----------
        status, text = f.request("GET", f"/{self.args.group}/web3/transactionReceipt/{produce_hash}")
        body = parse(text)
        ok = status == 200 and isinstance(body, dict) and body.get("transactionHash") == produce_hash \
            and body.get("status") == "0x0" and body.get("statusOK") is True
        self.record("按哈希查回执（生产交易）", "HTTP 200 同一回执", status, body, ok)
        fake_hash = "0x" + "ab" * 32
        status, text = f.request("GET", f"/{self.args.group}/web3/transactionReceipt/{fake_hash}")
        body = parse(text)
        ok = status == 500 and isinstance(body, dict) and body.get("code") == 500
        self.record("按哈希查回执（不存在的哈希）", "HTTP 500 code=500", status, body, ok)

        if not self.args.skip_stall:
            self.stall_scenario(tn)

    def stall_scenario(self, tn):
        """停掉 2/4 个节点让 PBFT 无法出块，观察 WeBASE 的回执超时响应，再恢复节点查证交易最终是否上链。"""
        acc = self.accounts
        stall_tn = tn + "-STALL"
        nodes = os.path.join(self.args.nodes_dir, "127.0.0.1")
        for n in ("node2", "node3"):
            subprocess.run(["bash", os.path.join(nodes, n, "stop.sh")], check=True, stdout=subprocess.DEVNULL)
        try:
            started = time.time()
            status, body = self.trans(acc["producer"], "newAgroFood",
                                      [stall_tn, "农场A", "苹果", "烟台", "红富士", "B002", "QmCert", "2026-09-28"])
            elapsed = round(time.time() - started, 1)
            ok = (status == 200 and isinstance(body, dict) and body.get("statusOK") is False
                  and body.get("transactionHash") is None and body.get("message") == "Transaction receipt timeout")
            self.record("共识停滞时发交易（2/4 节点停止）", "HTTP 200 statusOK=false transactionHash=null 超时",
                        status, body, ok, {"elapsedSeconds": elapsed})
        finally:
            for n in ("node2", "node3"):
                subprocess.run(["bash", os.path.join(nodes, n, "start.sh")], check=True, stdout=subprocess.DEVNULL)
        # 等恢复出块后读链上状态：同一笔交易是否最终被打包
        actors = None
        for _ in range(40):
            time.sleep(3)
            status, actors = self.call("getStageActors", [stall_tn])
            if isinstance(actors, list) and len(actors) == 3:
                break
        ok = isinstance(actors, list) and len(actors) == 3 and actors[0].lower() == acc["producer"].lower()
        self.record("节点恢复后查证超时交易", "getStageActors[0] == producer（交易最终上链）", 200, actors, ok)

    def write(self):
        date = datetime.date.today().isoformat()
        os.makedirs(self.args.out_dir, exist_ok=True)
        _, block1 = self.front.request("GET", f"/{self.args.group}/web3/blockNumber")
        self.meta["blockNumberAtEnd"] = parse(block1)
        doc = {"meta": self.meta, "contractAddress": self.contract, "accounts": self.accounts,
               "steps": self.steps, "failures": self.failures}
        json_path = os.path.join(self.args.out_dir, f"local-chain-smoke-{date}.json")
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=2)
            f.write("\n")
        # 供后端的真实链测试读取（合约地址、托管账户地址），放在工作目录，不入库
        with open(os.path.join(self.args.e2e_home, "last-smoke.json"), "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=2)
        md_path = os.path.join(self.args.out_dir, f"local-chain-smoke-{date}.md")
        with open(md_path, "w", encoding="utf-8") as f:
            f.write(f"# 本地隔离链冒烟记录 {date}\n\n")
            f.write("由 `scripts/local-chain/smoke.sh` 生成；真实 FISCO BCOS 节点 + 真实 WeBASE-Front，无替身。\n\n")
            for k, v in self.meta.items():
                f.write(f"- {k}: `{json.dumps(v, ensure_ascii=False)}`\n")
            f.write(f"- contractAddress: `{self.contract}`\n")
            for role, addr in self.accounts.items():
                f.write(f"- {role}: `{addr}`\n")
            f.write(f"\n结果：{len(self.steps) - len(self.failures)}/{len(self.steps)} 步符合预期\n\n")
            f.write("| 步骤 | 期望 | HTTP | transactionHash | blockNumber | status | message / errorMessage | 符合 |\n")
            f.write("|---|---|---|---|---|---|---|---|\n")
            for s in self.steps:
                msg = s.get("message") or s.get("errorMessage") or ""
                if not msg and "body" in s:
                    msg = json.dumps(s["body"], ensure_ascii=False)
                msg = str(msg).replace("|", "\\|")
                if len(msg) > 90:
                    msg = msg[:90] + "…"
                f.write(f"| {s['step']} | {s['expect']} | {s['httpStatus']} | `{s.get('transactionHash') or ''}` "
                        f"| {s.get('blockNumber') or ''} | {s.get('status') or ''} | {msg} | {'是' if s['ok'] else '否'} |\n")
        print(f"已写入 {json_path}\n已写入 {md_path}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--front-url", required=True)
    p.add_argument("--front-version", required=True)
    p.add_argument("--group", type=int, default=1)
    p.add_argument("--artifact", required=True, help="contracts/build/Trace.json")
    p.add_argument("--out-dir", required=True)
    p.add_argument("--e2e-home", required=True)
    p.add_argument("--nodes-dir", required=True)
    p.add_argument("--skip-stall", action="store_true", help="跳过停节点的共识停滞场景")
    args = p.parse_args()
    smoke = Smoke(args)
    try:
        smoke.run()
    finally:
        smoke.write()
    if smoke.failures:
        print("不符合预期的步骤：" + "、".join(smoke.failures), file=sys.stderr)
        sys.exit(1)
    print("全部步骤符合预期")


if __name__ == "__main__":
    main()
