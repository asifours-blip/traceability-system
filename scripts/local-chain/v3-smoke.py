#!/usr/bin/env python3
"""在 ~/trace-e2e 隔离链上验证 v2/v3 双地址和 v3 指定交接。只用标准库。"""
import datetime
import json
import sys
import urllib.request
from pathlib import Path

from smoke import Front, parse, receipt_summary

ZERO = "0x" + "0" * 40
BASE = "http://127.0.0.1:5102/WeBASE-Front"
ROOT = str(Path(__file__).resolve().parents[2])
front = Front(BASE, 1)
steps = []
run = datetime.datetime.now().strftime("%Y%m%d%H%M%S")


def rpc(method, params):
    request = urllib.request.Request("http://127.0.0.1:8845",
                                     data=json.dumps({"jsonrpc": "2.0", "method": method,
                                                      "params": params, "id": 1}).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response).get("result")


def account(role):
    status, raw = front.request("GET", "/privateKey?type=0&userName=v3_" + run + "_" + role)
    data = parse(raw)
    assert status == 200 and isinstance(data, dict) and data.get("address"), (role, status, raw)
    return data["address"]


def artifact(name):
    with open(ROOT + "/contracts/build/" + name + ".json", encoding="utf-8") as source:
        return json.load(source)


def deploy(name, owner):
    compiled = artifact(name)
    status, raw = front.request("POST", "/contract/deploy", {
        "groupId": 1, "user": owner, "contractName": name, "abiInfo": compiled["abi"],
        "bytecodeBin": compiled["bytecode"], "funcParam": [ZERO, ZERO, ZERO]})
    address = raw.strip().strip('"')
    assert status == 200 and address.startswith("0x") and len(address) == 42, (name, status, raw)
    steps.append({"step": "deploy " + name, "address": address, "httpStatus": status,
                  "creationBytes": (len(compiled["bytecode"]) - 2) // 2,
                  "runtimeBytes": (len(compiled["deployedBytecode"]) - 2) // 2})
    return address, compiled["abi"]


def transact(name, address, abi, user, func, params, label, success=True, reason=None):
    status, raw = front.request("POST", "/trans/handle", {
        "groupId": 1, "user": user, "contractName": name, "contractAddress": address,
        "contractAbi": abi, "funcName": func, "funcParam": params})
    body = parse(raw)
    expected = "0x0" if success else None
    assert status == 200 and isinstance(body, dict) and body.get("transactionHash"), (label, status, body)
    assert body.get("statusOK") is success and (
        body.get("status") == expected if success else body.get("status") != "0x0"), (label, body)
    if reason is not None:
        assert body.get("message") == reason, (label, body)
    number = int(body["blockNumber"])
    block = rpc("getBlockByNumber", [1, hex(number), False])
    assert block and block.get("hash"), (label, number, block)
    receipt_status, receipt_raw = front.request("GET", "/1/web3/transactionReceipt/" + body["transactionHash"])
    receipt = parse(receipt_raw)
    assert receipt_status == 200 and receipt.get("transactionHash") == body["transactionHash"]
    assert receipt.get("status") == body.get("status")
    entry = {"step": label, "contract": name, "address": address, **receipt_summary(body),
             "blockHash": block["hash"], "receiptQueryStatus": receipt_status}
    steps.append(entry)
    print(label, body["transactionHash"], number, body["status"], body.get("message", ""))
    return body


def call(name, address, abi, user, func, params):
    status, raw = front.request("POST", "/trans/handle", {
        "groupId": 1, "user": user, "contractName": name, "contractAddress": address,
        "contractAbi": abi, "funcName": func, "funcParam": params})
    result = parse(raw)
    assert status == 200 and isinstance(result, list), (func, status, result)
    return result


def main():
    users = {role: account(role) for role in
             ("owner", "producer", "distributor", "otherDistributor", "retailer")}
    old, old_abi = deploy("Trace", users["owner"])
    new, new_abi = deploy("TraceV3", users["owner"])
    for name, address, abi in (("Trace", old, old_abi), ("TraceV3", new, new_abi)):
        for role, func in (("producer", "addProducer"), ("distributor", "addDistributor"),
                           ("otherDistributor", "addDistributor"), ("retailer", "addRetailer")):
            transact(name, address, abi, users["owner"], func, [users[role]], name + " grant " + role)
    v2_tn = "V2-COMPAT-" + run
    v3_tn = "V3-E2E-" + run
    production = ["农场", "苹果", "烟台", "红富士", "P-" + run, "QmCert", "2026-09-28"]
    distribution = ["仓配", "冷藏", "冷链车", "D-" + run, "济南", 10, 100, "QmReport"]
    retail = ["门店", 20, 5, 7, "INV-" + run, "2026-09-29"]
    transact("Trace", old, old_abi, users["producer"], "newAgroFood",
             [v2_tn] + production, "v2 production only")
    transact("TraceV3", new, new_abi, users["producer"], "newAgroFood",
             [v3_tn] + production + [users["distributor"]], "v3 production")
    assert call("TraceV3", new, new_abi, users["owner"], "getDesignations", [v3_tn])[0].lower() == users["distributor"].lower()
    transact("TraceV3", new, new_abi, users["otherDistributor"], "addTraceInfoByDistributor",
             [v3_tn] + distribution + [users["retailer"]], "v3 direct bypass rejected",
             False, "Trace: caller is not the designated distributor")
    transact("TraceV3", new, new_abi, users["distributor"], "addTraceInfoByDistributor",
             [v3_tn] + distribution + [users["retailer"]], "v3 distribution")
    transact("TraceV3", new, new_abi, users["retailer"], "addTraceInfoByRetailer",
             [v3_tn] + retail, "v3 retail")
    actors = call("TraceV3", new, new_abi, users["owner"], "getStageActors", [v3_tn])
    assert [item.lower() for item in actors] == [users[role].lower() for role in ("producer", "distributor", "retailer")]
    for func in ("getAgroFoodInfo", "getAgroFoodInfoByDistributor", "getAgroFoodInfoByRetailer"):
        assert call("TraceV3", new, new_abi, users["owner"], func, [v3_tn])
    steps.append({"step": "v3 consumer chain query", "traceNumber": v3_tn, "actors": actors,
                  "designations": call("TraceV3", new, new_abi, users["owner"], "getDesignations", [v3_tn])})
    transact("Trace", old, old_abi, users["distributor"], "addTraceInfoByDistributor",
             [v2_tn] + distribution, "v2 distribution after v3")
    transact("Trace", old, old_abi, users["retailer"], "addTraceInfoByRetailer",
             [v2_tn] + retail, "v2 retail after v3")
    assert call("Trace", old, old_abi, users["owner"], "getStageActors", [v2_tn])[2].lower() == users["retailer"].lower()
    assert call("Trace", old, old_abi, users["owner"], "getAgroFoodInfoByRetailer", [v2_tn])
    assert v2_tn in call("Trace", old, old_abi, users["owner"], "getAgroFoodList", [])[0]
    assert v3_tn in call("TraceV3", new, new_abi, users["owner"], "getAgroFoodList", [])[0]
    payload = {"runId": run, "rpc": "http://127.0.0.1:8845", "front": BASE,
               "v2Address": old, "v3Address": new, "accounts": users, "v2Trace": v2_tn, "v3Trace": v3_tn,
               "steps": steps, "result": "PASS"}
    with open(sys.argv[1], "w", encoding="utf-8") as output:
        json.dump(payload, output, ensure_ascii=False, indent=2)
        output.write("\n")


if __name__ == "__main__":
    main()
