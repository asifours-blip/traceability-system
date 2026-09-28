-- 链上交易提交记录（MySQL 5.7+ / 8.0）
-- 初始化：mysql -u <用户> -p <库名> < back-me/src/main/resources/db/chain-tx-schema.sql
-- 语句带 IF NOT EXISTS，可重复执行。离线测试用 H2（MySQL 模式）执行同一份文件。
-- 状态机与查证规则见 docs/tx-lifecycle.md。

CREATE TABLE IF NOT EXISTS chain_tx (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_key        VARCHAR(191) NOT NULL COMMENT '业务键：阶段交易 trace:{溯源号}:{阶段}，其他交易 {函数名}:{参数摘要前16位}',
    inflight_key   VARCHAR(191) NULL COMMENT '未决占位：阶段交易处于 PENDING/SUBMITTED/UNKNOWN 时等于 biz_key，终态或查证确认未写入后置空',
    trace_number   VARCHAR(128) NULL COMMENT '溯源号（阶段交易）',
    stage          TINYINT      NULL COMMENT '1 生产 2 分销 3 零售；非阶段交易为空',
    func_name      VARCHAR(64)  NOT NULL COMMENT '合约函数名',
    params_digest  CHAR(64)     NOT NULL COMMENT 'sha256(参数逐个转字符串后的 JSON 数组)',
    signer         VARCHAR(42)  NOT NULL COMMENT '签名地址（服务端会话绑定的链上地址）',
    state          VARCHAR(16)  NOT NULL COMMENT 'PENDING / SUBMITTED / CONFIRMED / FAILED / UNKNOWN',
    tx_hash        VARCHAR(66)  NULL COMMENT '交易哈希（WeBASE 返回时才有）',
    block_number   BIGINT       NULL COMMENT '块高（有回执时）',
    receipt_status VARCHAR(16)  NULL COMMENT '回执 status 原值：0x0 成功，0x16 revert，50001 WeBASE 等回执超时等',
    error_reason   VARCHAR(512) NULL COMMENT '失败或未知的原因：revert 文本 / WeBASE 错误 / 传输异常',
    verify_result  VARCHAR(32)  NULL COMMENT '最近一次查证结论',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（提交意图写入时间）',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 唯一索引对 NULL 不生效：同一业务键同时最多一条未决记录，并发提交由数据库兜底
    UNIQUE KEY uk_chain_tx_inflight_key (inflight_key),
    KEY idx_chain_tx_biz_key (biz_key),
    KEY idx_chain_tx_tx_hash (tx_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '链上交易提交记录';
