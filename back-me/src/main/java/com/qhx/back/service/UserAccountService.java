package com.qhx.back.service;

import com.qhx.back.model.to.CreateUserTo;
import com.qhx.back.model.vo.UserVO;

import java.util.List;

/**
 * 账号管理。调用方必须已经是 ADMIN（由拦截器 + @RequireRole 保证），
 * 链上交易的签名地址来自当前管理员会话。
 */
public interface UserAccountService
{
    UserVO createUser(CreateUserTo createUserTo);

    List<UserVO> listUsers();

    void disableUser(Long userId);
}
