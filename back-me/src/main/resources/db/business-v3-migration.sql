-- 一次性升级。执行前把下一行替换为当前 v2 部署地址（42 字符 0x...）；禁止用运行时配置兜底旧批次。
SET @trace_v2_address = 'REPLACE_WITH_DEPLOYED_V2_ADDRESS';
ALTER TABLE trace_batch ADD COLUMN contract_version VARCHAR(2) NOT NULL DEFAULT 'V2' COMMENT '批次合约版本';
ALTER TABLE trace_batch ADD COLUMN contract_address VARCHAR(42) NULL COMMENT '批次合约地址';
UPDATE trace_batch SET contract_address = @trace_v2_address WHERE contract_version = 'V2';
-- 占位值或空值会使校验失败，必须修正后才能认为迁移完成。
ALTER TABLE trace_batch MODIFY COLUMN contract_address VARCHAR(42) NOT NULL,
    ADD CONSTRAINT chk_trace_batch_address CHECK (contract_address REGEXP '^0x[0-9a-fA-F]{40}$');
