package com.qhx.back.model.to;

import lombok.Data;

/**
 * 管理员新建业务账号。chainAddress 是新用户要绑定的地址（交易参数），
 * 不是签名地址；签名地址固定为当前管理员会话绑定的地址。
 */
@Data
public class CreateUserTo
{
    private String username;
    private String password;
    private String role;
    // 必须是已在 WeBASE-Front 托管私钥的地址
    private String chainAddress;
    private String companyName;
}
