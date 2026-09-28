-- 批次归属、交接历史、链下更正、账号链上授权状态（MySQL 5.7+ / 8.0）
-- 初始化：mysql -u <用户> -p <库名> < back-me/src/main/resources/db/business-schema.sql
-- 需在 auth-schema.sql 之后执行。语句带 IF NOT EXISTS，可重复执行。离线测试用 H2（MySQL 模式）执行同一份文件。
-- 规则说明见 docs/business-flow.md：这些都是后端规则，链上合约 v2 只强制角色、阶段顺序与每阶段只写一次。

CREATE TABLE IF NOT EXISTS trace_batch (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_number   VARCHAR(64)  NOT NULL COMMENT '溯源号，唯一；生产商提交生产信息时占用',
    product_name   VARCHAR(128) NULL COMMENT '产品名（仅列表展示用的链下副本，以链上数据为准）',
    producer_id    BIGINT       NOT NULL COMMENT '建档生产商账号',
    distributor_id BIGINT       NULL COMMENT '当前指定的分销商账号，只有它能写分销阶段',
    retailer_id    BIGINT       NULL COMMENT '当前指定的零售商账号，只有它能写零售阶段',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trace_batch_trace_number (trace_number),
    KEY idx_trace_batch_producer (producer_id),
    KEY idx_trace_batch_distributor (distributor_id),
    KEY idx_trace_batch_retailer (retailer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '批次归属与交接对象';

CREATE TABLE IF NOT EXISTS trace_assignment_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_number  VARCHAR(64)  NOT NULL COMMENT '溯源号',
    stage         TINYINT      NOT NULL COMMENT '被指定对象负责的阶段：2 分销 3 零售',
    from_user_id  BIGINT       NULL COMMENT '原指定账号，首次指定为空',
    to_user_id    BIGINT       NOT NULL COMMENT '新指定账号',
    operator_id   BIGINT       NOT NULL COMMENT '操作人',
    reason        VARCHAR(200) NULL COMMENT '变更原因',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '时间',
    PRIMARY KEY (id),
    KEY idx_trace_assignment_log_trace (trace_number)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '交接对象指定历史（只追加）';

CREATE TABLE IF NOT EXISTS trace_correction (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_number    VARCHAR(64)   NOT NULL COMMENT '溯源号',
    stage           TINYINT       NOT NULL COMMENT '被更正的阶段：1 生产 2 分销 3 零售',
    author_id       BIGINT        NOT NULL COMMENT '提交人账号（必须是该阶段的链上写入者）',
    author_username VARCHAR(64)   NOT NULL COMMENT '提交人用户名快照',
    author_company  VARCHAR(128)  NULL COMMENT '提交人公司名快照',
    author_address  VARCHAR(42)   NOT NULL COMMENT '提交人链上地址快照（与该阶段链上写入者一致）',
    reason          VARCHAR(500)  NOT NULL COMMENT '更正原因',
    content         VARCHAR(2000) NOT NULL COMMENT '更正内容 JSON：{字段名: 更正后的值}',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    PRIMARY KEY (id),
    KEY idx_trace_correction_trace (trace_number)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '链下更正记录（只追加，应用不提供修改/删除）';

CREATE TABLE IF NOT EXISTS account_role_grant (
    user_id      BIGINT       NOT NULL COMMENT '账号',
    role         VARCHAR(16)  NOT NULL COMMENT 'PRODUCER / DISTRIBUTOR / RETAILER',
    state        VARCHAR(24)  NOT NULL COMMENT 'GRANTED_BY_TX 授权交易已确认 / ALREADY_ON_CHAIN 链上已有角色未发交易 / PENDING 授权交易结果未知',
    tx_id        BIGINT       NULL COMMENT '授权交易 chain_tx.id（跳过交易时为空）',
    note         VARCHAR(500) NULL COMMENT '说明',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '业务账号的链上角色授权状态';

-- ---------------------------------------------------------------- 文件与读模型（见 docs/files.md、docs/read-model.md）

CREATE TABLE IF NOT EXISTS file_object (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    cid                VARCHAR(128) NOT NULL COMMENT 'IPFS CID（kubo add 返回，已读回核对）',
    sha256             CHAR(64)     NOT NULL COMMENT '服务端流式计算的内容 SHA-256（十六进制）',
    size_bytes         BIGINT       NOT NULL COMMENT '字节数',
    mime_type          VARCHAR(64)  NOT NULL COMMENT '按文件头判定的类型：image/png / image/jpeg / image/webp / application/pdf',
    file_name          VARCHAR(128) NOT NULL COMMENT '清洗后的文件名（只用于展示与下载）',
    uploader_id        BIGINT       NULL COMMENT '上传账号；重建时从 IPFS 导入的旧文件可能匹配不到账号',
    status             VARCHAR(16)  NOT NULL COMMENT 'UPLOADED 已上传未被确认的交易引用 / BOUND 已绑定且交易已确认 / ORPHANED 超期未绑定',
    claim_trace_number VARCHAR(128) NULL COMMENT '提交阶段交易时占用：溯源号（交易确认后据此绑定，未决期间不会被当成孤儿）',
    claim_stage        TINYINT      NULL COMMENT '提交阶段交易时占用：阶段 1 生产 2 分销',
    trace_number       VARCHAR(128) NULL COMMENT '绑定的溯源号（BOUND 时有值）',
    stage              TINYINT      NULL COMMENT '绑定的阶段（BOUND 时有值）',
    bound_key          VARCHAR(160) NULL COMMENT 'BOUND 时为 {溯源号}:{阶段}，唯一：每个阶段最多一个已绑定文件',
    chain_tx_id        BIGINT       NULL COMMENT '确认该绑定的交易 chain_tx.id；重建时按链上数据绑定的旧文件为空',
    bind_source        VARCHAR(16)  NULL COMMENT 'TX_CONFIRMED 交易确认后绑定 / REBUILD 重建读模型时按链上数据绑定',
    bound_at           DATETIME     NULL COMMENT '绑定时间',
    orphaned_at        DATETIME     NULL COMMENT '标为孤儿的时间',
    unpinned           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '孤儿文件是否已从本地 IPFS 取消 pin',
    unpin_note         VARCHAR(500) NULL COMMENT '取消 pin 的结果说明（失败原因 / 因其他记录仍引用同一 CID 而保留）',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_object_bound_key (bound_key),
    KEY idx_file_object_cid (cid),
    KEY idx_file_object_status_created (status, created_at),
    KEY idx_file_object_claim (claim_trace_number, claim_stage)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '上传到 IPFS 的文件及其绑定状态';

CREATE TABLE IF NOT EXISTS trace_read_model (
    trace_number        VARCHAR(128) NOT NULL COMMENT '溯源号',
    list_index          INT          NULL COMMENT '在链上 getAgroFoodList 中的位置（重建时写入）',
    stage_reached       TINYINT      NOT NULL COMMENT '链上已写入到的阶段：1 生产 2 分销 3 零售',
    product_name        VARCHAR(128) NULL COMMENT '产品名称（链上生产阶段）',
    producer_company    VARCHAR(128) NULL COMMENT '生产企业（链上生产阶段）',
    production_location VARCHAR(256) NULL COMMENT '产地（链上生产阶段）',
    variety             VARCHAR(128) NULL COMMENT '品种（链上生产阶段）',
    product_time        VARCHAR(16)  NULL COMMENT '生产日期（链上生产阶段）',
    production_ts       BIGINT       NULL COMMENT '生产阶段上链时间（毫秒）',
    distribution_ts     BIGINT       NULL COMMENT '分销阶段上链时间（毫秒）',
    retail_ts           BIGINT       NULL COMMENT '零售阶段上链时间（毫秒）',
    producer_address    VARCHAR(42)  NULL COMMENT '链上生产阶段写入者',
    distributor_address VARCHAR(42)  NULL COMMENT '链上分销阶段写入者',
    retailer_address    VARCHAR(42)  NULL COMMENT '链上零售阶段写入者',
    production_data     TEXT         NULL COMMENT '链上生产阶段全部字段 JSON（含 timestamp）',
    distribution_data   TEXT         NULL COMMENT '链上分销阶段全部字段 JSON',
    retail_data         TEXT         NULL COMMENT '链上零售阶段全部字段 JSON',
    production_cid      VARCHAR(128) NULL COMMENT '链上生产认证 CID',
    distribution_cid    VARCHAR(128) NULL COMMENT '链上质检报告 CID',
    claim_status        VARCHAR(16)  NOT NULL COMMENT 'CLAIMED 各阶段写入者都与本系统批次归属一致 / PARTIAL 部分阶段对不上 / UNCLAIMED 本系统没有该批次的归属记录',
    claim_note          VARCHAR(500) NULL COMMENT '未认领 / 部分认领的原因',
    synced_at           DATETIME     NOT NULL COMMENT '最近一次按链上数据写入本行的时间（内容无变化时不更新）',
    PRIMARY KEY (trace_number),
    KEY idx_trace_read_model_claim (claim_status),
    KEY idx_trace_read_model_stage (stage_reached)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '溯源读模型：只由读链结果写入，可随时从链上完整重建';
