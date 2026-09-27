package com.qhx.back.service;

import com.qhx.back.model.to.CreateUserTo;
import com.qhx.back.model.vo.UserVO;

import java.util.List;

/**
 * 账号管理。建号是幂等的：链上已有角色就跳过授权交易；授权结果未知时账号保持停用（PENDING），查证后启用。
 * 调用方必须已经是 ADMIN（由拦截器 + @RequireRole 保证），
 * 链上交易的签名地址来自当前管理员会话。
 */
public interface UserAccountService
{
    UserVO createUser(CreateUserTo createUserTo);

    List<UserVO> listUsers();

    void disableUser(Long userId);

    /** 授权交易结果未知（PENDING）的账号：查证并以链上 isX 为准，已有角色则启用 */
    UserVO verifyRole(Long userId);

    /** PENDING 账号重新发送授权交易（链上已有角色时直接启用，不发交易） */
    UserVO retryGrant(Long userId);
}
