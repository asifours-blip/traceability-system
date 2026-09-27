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
