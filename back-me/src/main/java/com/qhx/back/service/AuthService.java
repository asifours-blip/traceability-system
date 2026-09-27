package com.qhx.back.service;

import com.qhx.back.model.UserAccount;
import com.qhx.back.model.vo.LoginVO;

public interface AuthService
{
    // 用户名 + 密码登录，成功返回一次性明文 token；失败抛 AuthException(401)
    LoginVO login(String username, String password);

    // 撤销单个 token（登出）
    void logout(String token);

    // 根据 token 解析出启用中的账号；无效/过期/已撤销/账号停用都返回 null
    UserAccount authenticate(String token);

    // 撤销某账号的全部 token（停用账号时调用）
    void revokeAllSessions(Long userId);

    String hashPassword(String rawPassword);
}
