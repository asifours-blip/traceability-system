package com.qhx.back.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 业务账号的链上角色授权状态。PENDING 时账号保持停用，查证确认链上已有角色后才启用。
 */
@Data
@TableName("account_role_grant")
public class AccountRoleGrant {
    public static final String GRANTED_BY_TX = "GRANTED_BY_TX";
    public static final String ALREADY_ON_CHAIN = "ALREADY_ON_CHAIN";
    public static final String PENDING = "PENDING";

    @TableId(type = IdType.INPUT)
    private Long userId;
    private String role;
    private String state;
    private Long txId;
    private String note;
    private Date createdAt;
    private Date updatedAt;
}
