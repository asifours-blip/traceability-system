#!/usr/bin/env node
// 对比用：把访问控制重做之前的旧合约（默认基线提交 8ec675c）从 git 历史导出到临时目录，
// 用同一个编译脚本编译，再用同一套测试跑一遍。
// 旧合约上预期出现大量失败——这正是用来证明 README 里列出的漏洞在旧合约上可以复现。
// 需要完整 git 历史，因此不在 CI 中运行。
//
// 用法：npm run test:legacy [-- <commit>]
"use strict";

const { execFileSync, spawnSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
const BASE = process.argv[2] || "8ec675c";

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "trace-legacy-"));
const buildDir = path.join(tmp, "build");

const files = execFileSync("git", ["ls-tree", "--name-only", "--full-tree", BASE, "contracts/"], { cwd: ROOT, encoding: "utf8" })
  .split("\n")
  .filter((f) => f.endsWith(".sol"));
for (const file of files) {
  const source = execFileSync("git", ["show", BASE + ":" + file], { cwd: ROOT });
  fs.writeFileSync(path.join(tmp, path.basename(file)), source);
}
console.log("已导出 " + BASE + " 的 " + files.length + " 个合约到 " + tmp);

const env = Object.assign({}, process.env, { TRACE_SOURCES_DIR: tmp, TRACE_BUILD_DIR: buildDir });

const compiled = spawnSync(process.execPath, ["--stack-size=4000", path.join(__dirname, "compile.js")], {
  cwd: ROOT,
  env,
  stdio: "inherit",
});
if (compiled.status !== 0) process.exit(compiled.status || 1);

const hardhatCli = path.join(ROOT, "node_modules", "hardhat", "internal", "cli", "bootstrap.js");
const tested = spawnSync(process.execPath, [hardhatCli, "test", "--no-compile"], { cwd: ROOT, env, stdio: "inherit" });

fs.rmSync(tmp, { recursive: true, force: true });
process.exit(tested.status === null ? 1 : tested.status);
