package com.qhx.back.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("user_account")
public class UserAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    // BCrypt 哈希，禁止返回给前端
    private String passwordHash;
    // UserRole 枚举名
    private String role;
    // 服务端绑定的链上地址，所有交易的签名地址只来自这里
    private String chainAddress;
    private String companyName;
    private Boolean enabled;
    private Date createdAt;
    private Date updatedAt;
}
