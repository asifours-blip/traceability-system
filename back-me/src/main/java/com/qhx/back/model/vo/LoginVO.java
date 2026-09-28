package com.qhx.back.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO
{
    // 明文 token 只在这里返回一次，服务端只存 sha256
    private String token;
    private Date expiresAt;
    private UserVO user;
}
