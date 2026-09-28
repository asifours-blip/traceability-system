#!/usr/bin/env node
// 用 npm 包 solc@0.4.25 内置的 soljson.js 编译合约，不联网下载编译器。
// soljson.js 是 asm.js 大文件，Node 默认栈不够加载，需以 `node --stack-size=4000` 运行（npm scripts 已带上）。
//
// 用法：
//   node --stack-size=4000 scripts/compile.js               编译到 build/
//   node --stack-size=4000 scripts/compile.js --write-abi   另外把 Trace / TraceV3 的 ABI 写到 abi/
// 环境变量：
//   TRACE_SOURCES_DIR  源码目录，默认 contracts/（仅用于对旧合约跑对比测试）
//   TRACE_BUILD_DIR    产物目录，默认 contracts/build
"use strict";

const fs = require("fs");
const path = require("path");
const solc = require("solc");

const ROOT = path.resolve(__dirname, "..");
const SOURCES_DIR = path.resolve(process.env.TRACE_SOURCES_DIR || ROOT);
const BUILD_DIR = path.resolve(process.env.TRACE_BUILD_DIR || path.join(ROOT, "build"));
const ENTRY = process.env.TRACE_V3 === "1" ? "TraceV3.sol" : "Trace.sol";
const EXPECTED_VERSION = "0.4.25";

// 统一换行为 LF，保证 Windows 与 Linux 编译出的 metadata/bytecode 一致
function readSource(name) {
  return fs.readFileSync(path.join(SOURCES_DIR, name), "utf8").replace(/\r\n/g, "\n");
}

function findImports(importPath) {
  // 源码只用 "./Xxx.sol" 形式的同目录导入
  const name = path.basename(importPath);
  let file = path.join(SOURCES_DIR, name);
  if (!fs.existsSync(file) && process.env.TRACE_V3 === "1") file = path.join(ROOT, name);
  if (!fs.existsSync(file)) {
    return { error: "File not found: " + importPath };
  }
  return { contents: fs.readFileSync(file, "utf8").replace(/\r\n/g, "\n") };
}

function main() {
  const version = solc.version();
  if (!version.startsWith(EXPECTED_VERSION + "+")) {
    console.error("需要 solc " + EXPECTED_VERSION + "，实际为 " + version);
    process.exit(1);
  }
  // soljson.js 会接管未捕获异常并打印整份编译器源码，这里提前给出可读的错误
  if (!fs.existsSync(path.join(SOURCES_DIR, ENTRY))) {
    console.error("找不到入口合约 " + path.join(SOURCES_DIR, ENTRY));
    process.exit(1);
  }

  const input = {
    language: "Solidity",
    sources: { [ENTRY]: { content: readSource(ENTRY) } },
    settings: {
      // v2 保持默认关闭；v3 为满足 EVM 合约大小限制启用优化器。真实链部署须使用同一产物。
      optimizer: { enabled: process.env.TRACE_V3 === "1", runs: 200 },
      evmVersion: "byzantium",
      outputSelection: { "*": { "*": ["abi", "evm.bytecode.object"] } },
    },
  };

  const output = JSON.parse(solc.compileStandardWrapper(JSON.stringify(input), findImports));
  const errors = (output.errors || []).filter((e) => e.severity === "error");
  for (const e of output.errors || []) {
    // 0.4.25 对 ABIEncoderV2 固定输出 experimental 警告，不打印以免淹没真正的问题
    if (e.severity === "warning" && /Experimental features/.test(e.message)) continue;
    console.error(e.formattedMessage || e.message);
  }
  if (errors.length > 0) {
    process.exit(1);
  }

  fs.mkdirSync(BUILD_DIR, { recursive: true });
  const written = [];
  for (const file of Object.keys(output.contracts)) {
    for (const [name, artifact] of Object.entries(output.contracts[file])) {
      const bytecode = artifact.evm.bytecode.object;
      if (!bytecode) continue; // 库或接口的空字节码跳过
      const out = { contractName: name, compiler: version, abi: artifact.abi, bytecode: "0x" + bytecode };
      fs.writeFileSync(path.join(BUILD_DIR, name + ".json"), JSON.stringify(out, null, 2) + "\n");
      written.push(name);
    }
  }
  console.log("solc " + version + "：" + SOURCES_DIR + " -> " + BUILD_DIR + "（" + written.join(", ") + "）");

  if (process.argv.includes("--write-abi")) {
    const name = process.env.TRACE_V3 === "1" ? "TraceV3" : "Trace";
    const abi = output.contracts[ENTRY][name].abi;
    const abiFile = path.join(ROOT, "abi", name + ".json");
    fs.writeFileSync(abiFile, JSON.stringify(abi, null, 2) + "\n");
    console.log("已写入 " + path.relative(ROOT, abiFile) + "（" + abi.length + " 项）");
  }
}

main();
// 默认同时编译独立的 v3 合约；旧版对比只编译导出的 v1 源码。
if (!process.env.TRACE_SOURCES_DIR && process.env.TRACE_V3 !== "1") {
  const { spawnSync } = require("child_process");
  const result = spawnSync(process.execPath, ["--stack-size=4000", __filename, ...process.argv.slice(2)], {
    env: { ...process.env, TRACE_V3: "1", TRACE_SOURCES_DIR: path.join(ROOT, "v3") },
    stdio: "inherit",
  });
  if (result.status !== 0) process.exit(result.status || 1);
}
