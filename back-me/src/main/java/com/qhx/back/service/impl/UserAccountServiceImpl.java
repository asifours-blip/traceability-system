package com.qhx.back.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qhx.back.client.WeBaseClient;
import com.qhx.back.enums.UserRole;
import com.qhx.back.exception.WeBaseFrontException;
import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.model.UserAccount;
import com.qhx.back.model.to.CreateUserTo;
import com.qhx.back.model.vo.UserVO;
import com.qhx.back.service.AuthService;
import com.qhx.back.service.UserAccountService;
import com.qhx.back.util.UserAddressUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserAccountServiceImpl implements UserAccountService
{
    public static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,64}$");

    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private AuthService authService;
    @Autowired
    private WeBaseClient weBaseClient;

    @Override
    public UserVO createUser(CreateUserTo to)
    {
        UserRole role = UserRole.parse(to.getRole());
        if (!role.isBusinessRole()) {
            throw new IllegalArgumentException("管理员账号只能通过启动引导创建");
        }
        String username = StrUtil.trim(to.getUsername());
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("用户名只能包含字母、数字、下划线，长度 3-64");
        }
        if (to.getPassword() == null || to.getPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位");
        }
        String chainAddress = StrUtil.trim(to.getChainAddress());
        if (!UserAddressUtil.isLegalAddress(chainAddress)) {
            throw new IllegalArgumentException("链上地址不合法");
        }
        if (userAccountMapper.selectCount(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, username)) > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }
        if (userAccountMapper.selectCount(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getChainAddress, chainAddress)) > 0) {
            throw new IllegalArgumentException("该链上地址已绑定其他账号");
        }

        // 先上链授角色（onlyOwner，签名者是当前管理员），失败则不建账号
        weBaseClient.sendTransaction(role.addFunction(), Collections.singletonList(chainAddress));

        Date now = new Date();
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPasswordHash(authService.hashPassword(to.getPassword()));
        account.setRole(role.name());
        account.setChainAddress(chainAddress);
        account.setCompanyName(StrUtil.trim(to.getCompanyName()));
        account.setEnabled(true);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        userAccountMapper.insert(account);
        return UserVO.of(account);
    }

    @Override
    public List<UserVO> listUsers()
    {
        return userAccountMapper.selectList(new LambdaQueryWrapper<UserAccount>().orderByAsc(UserAccount::getId))
                .stream().map(UserVO::of).collect(Collectors.toList());
    }

    @Override
    public void disableUser(Long userId)
    {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        UserRole role = UserRole.parse(user.getRole());
        if (!role.isBusinessRole()) {
            throw new IllegalArgumentException("不能停用管理员");
        }

        // 先停用账号并撤销全部 token，保证本地身份立即失效；再由管理员签名撤销链上角色
        userAccountMapper.update(null, new LambdaUpdateWrapper<UserAccount>()
                .set(UserAccount::getEnabled, false)
                .set(UserAccount::getUpdatedAt, new Date())
                .eq(UserAccount::getId, userId));
        authService.revokeAllSessions(userId);

        try {
            weBaseClient.sendTransaction(role.removeFunction(), Collections.singletonList(user.getChainAddress()));
        } catch (WeBaseFrontException e) {
            // 账号已停用，链上撤销可对同一用户重试本接口
            log.error("用户 {} 已停用，但链上 {} 失败", user.getUsername(), role.removeFunction(), e);
            throw new WeBaseFrontException("账号已停用并已撤销登录，但链上撤销角色失败，请重试：" + e.mes);
        }
    }
}
