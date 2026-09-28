// Hardhat 在本项目里只当作进程内 EVM 和 mocha 测试运行器使用。
// 合约编译由 scripts/compile.js 调用 npm 包 solc@0.4.25 完成（Hardhat 自带的编译流程需要联网下载编译器），
// 因此测试命令固定带 --no-compile，这里也不配置 solidity。
module.exports = {
  networks: {
    hardhat: {
      // 与编译目标（solc 0.4.25 默认 evmVersion byzantium）保持一致；这不是 FISCO BCOS 节点本身的仿真
      hardfork: "byzantium",
      // 不开启 allowUnlimitedContractSize：Trace 运行时字节码需保持在 EIP-170 的 24576 字节以内
    },
  },
  paths: {
    tests: "./test",
  },
  mocha: {
    timeout: 60000,
  },
};
