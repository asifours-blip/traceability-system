-- 账号与登录会话表（MySQL 5.7+ / 8.0）
-- 初始化：mysql -u <用户> -p <库名> < back-me/src/main/resources/db/auth-schema.sql
-- 库名与 application.yml 的 MYSQL_DB 一致（默认 mysql）。语句带 IF NOT EXISTS，可重复执行。
-- 离线测试用 H2（MySQL 模式）执行同一份文件。

CREATE TABLE IF NOT EXISTS user_account (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(64)  NOT NULL COMMENT '登录用户名，唯一',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，不存明文',
    role          VARCHAR(16)  NOT NULL COMMENT 'ADMIN / PRODUCER / DISTRIBUTOR / RETAILER',
    chain_address VARCHAR(42)  NOT NULL COMMENT '绑定的链上地址（私钥托管在 WeBASE-Front）',
    company_name  VARCHAR(128) NULL COMMENT '公司/组织名',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1 启用，0 停用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_username (username),
    UNIQUE KEY uk_user_account_chain_address (chain_address)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '平台账号';

CREATE TABLE IF NOT EXISTS user_session (
    id         BIGINT     NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT     NOT NULL COMMENT '所属账号',
    token_hash CHAR(64)   NOT NULL COMMENT 'sha256(token) 十六进制，不存明文 token',
    expires_at DATETIME   NOT NULL COMMENT '过期时间',
    revoked    TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1 已撤销（登出/停用）',
    created_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_session_token_hash (token_hash),
    KEY idx_user_session_user_id (user_id),
    CONSTRAINT fk_user_session_user FOREIGN KEY (user_id) REFERENCES user_account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '登录会话';
