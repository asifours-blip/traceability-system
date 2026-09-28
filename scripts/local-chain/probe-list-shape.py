#!/usr/bin/env python3
"""核验 WeBASE-Front v1.5.5 对 string[] 返回值（getAgroFoodList）的响应结构，供读模型重建使用。

在本地隔离链上新部署一个 Trace 合约（不动 smoke 用的那一个），依次记录：
  1. 空列表时 getAgroFoodList 的原始响应
  2. 写入一个普通溯源号后的原始响应
  3. 再写入一个含引号、逗号、方括号、反斜杠、中文的溯源号后的原始响应（直接调合约可以写入任意非空字符串）
结果写到 docs/artifacts/webase-string-array-<日期>.json。只用 Python 标准库；需要 last-smoke.json 里的账户（producer 已有角色的是旧合约，
这里由 owner 在新合约上重新授予）。
"""
import datetime
import json
import os
import sys
import urllib.request

ZERO = "0x" + "0" * 40
repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
smoke = json.load(open(os.path.expanduser(os.environ.get("E2E_SMOKE_FILE", "~/trace-e2e/last-smoke.json"))))
art = json.load(open(os.path.join(repo, "contracts", "build", "Trace.json")))
front = smoke["meta"]["frontUrl"]
owner = smoke["accounts"]["owner"]
producer = smoke["accounts"]["producer"]


def post(path, body):
    req = urllib.request.Request(front + path, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=90) as resp:
        return resp.status, resp.read().decode("utf-8")


def trans(contract, user, func, params):
    return post("/trans/handle", {"groupId": 1, "user": user, "contractName": "Trace", "contractAddress": contract,
                                  "contractAbi": art["abi"], "funcName": func, "funcParam": params})


status, text = post("/contract/deploy", {"groupId": 1, "user": owner, "contractName": "Trace", "abiInfo": art["abi"],
                                         "bytecodeBin": art["bytecode"], "funcParam": [ZERO, ZERO, ZERO]})
contract = text.strip().strip('"')
assert status == 200 and contract.startswith("0x") and len(contract) == 42, (status, text)

steps = []


def record(name, result):
    st, raw = result
    steps.append({"step": name, "httpStatus": st, "raw": raw})
    print(f"{name}: http={st} raw={raw}")
    return raw


record("空列表", trans(contract, owner, "getAgroFoodList", []))
receipt = json.loads(trans(contract, owner, "addProducer", [producer])[1])
assert receipt.get("status") == "0x0", receipt
plain = "LIST-" + datetime.datetime.now().strftime("%H%M%S")
tricky = 'LIST-q"x, ]y\\z 中'
for tn in (plain, tricky):
    receipt = json.loads(trans(contract, producer, "newAgroFood", [tn, "c", "p", "l", "v", "b", "QmX", "2026-01-01"])[1])
    assert receipt.get("status") == "0x0", receipt
    raw = record("写入 " + json.dumps(tn, ensure_ascii=False) + " 后", trans(contract, owner, "getAgroFoodList", []))

# 解析规则：外层是单元素 JSON 数组，元素是字符串；该字符串本身是 JSON 数组
outer = json.loads(raw)
assert isinstance(outer, list) and len(outer) == 1 and isinstance(outer[0], str), outer
inner = json.loads(outer[0])
assert inner == [plain, tricky], inner
print("解析结果与写入一致：", inner)

out = os.path.join(repo, "docs", "artifacts", f"webase-string-array-{datetime.date.today()}.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump({"frontUrl": front, "webaseFront": smoke["meta"].get("webaseFrontVersion"), "contract": contract,
               "steps": steps, "parsed": inner}, f, ensure_ascii=False, indent=2)
print("记录：", out)
sys.exit(0)
