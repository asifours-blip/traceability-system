package com.qhx.back.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("user_session")
public class UserSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    // sha256(token) 十六进制；明文 token 只在登录响应里出现一次
    private String tokenHash;
    private Date expiresAt;
    private Boolean revoked;
    private Date createdAt;
}
