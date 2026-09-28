package com.qhx.back.config;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qhx.back.enums.UserRole;
import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.model.UserAccount;
import com.qhx.back.service.AuthService;
import com.qhx.back.service.impl.UserAccountServiceImpl;
import com.qhx.back.util.UserAddressUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 首次启动引导管理员账号。
 * 只在库里没有任何 ADMIN 时生效；初始密码只来自环境变量 ADMIN_INITIAL_PASSWORD，
 * 仓库里不内置默认密码，缺失时跳过并打 ERROR 日志（公开溯源查询仍可用，但无人能登录管理）。
 */
@Component
@Slf4j
public class AdminBootstrap implements ApplicationRunner
{
    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private AuthService authService;

    @Value("${auth.bootstrap-admin.username:admin}")
    private String username;
    @Value("${auth.bootstrap-admin.password:}")
    private String password;
    @Value("${auth.bootstrap-admin.address:}")
    private String address;

    @Override
    public void run(ApplicationArguments args)
    {
        try {
            bootstrap();
        } catch (Exception e) {
            // 数据库不可用时不阻塞启动，与原先"无库也能起"的行为一致
            log.error("管理员引导失败，请检查 MySQL 与 user_account 表是否已初始化", e);
        }
    }

    /**
     * @return 本次是否新建了管理员
     */
    public boolean bootstrap()
    {
        Long adminCount = userAccountMapper.selectCount(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getRole, UserRole.ADMIN.name()));
        if (adminCount != null && adminCount > 0) {
            return false;
        }
        if (StrUtil.isBlank(username)) {
            log.error("未创建管理员：ADMIN_USERNAME 为空");
            return false;
        }
        if (StrUtil.isEmpty(password)) {
            log.error("未创建管理员：库中没有 ADMIN 账号，且未设置环境变量 ADMIN_INITIAL_PASSWORD。"
                    + "设置 ADMIN_INITIAL_PASSWORD（可选 ADMIN_USERNAME / ADMIN_ADDRESS）后重启即可");
            return false;
        }
        if (password.length() < UserAccountServiceImpl.MIN_PASSWORD_LENGTH) {
            log.error("未创建管理员：ADMIN_INITIAL_PASSWORD 长度不能少于 {} 位", UserAccountServiceImpl.MIN_PASSWORD_LENGTH);
            return false;
        }
        if (!UserAddressUtil.isLegalAddress(address)) {
            log.error("未创建管理员：ADMIN_ADDRESS / contract.owner 不是合法地址：{}", address);
            return false;
        }

        Date now = new Date();
        UserAccount admin = new UserAccount();
        admin.setUsername(username.trim());
        admin.setPasswordHash(authService.hashPassword(password));
        admin.setRole(UserRole.ADMIN.name());
        admin.setChainAddress(address.trim());
        admin.setEnabled(true);
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        userAccountMapper.insert(admin);
        log.info("已创建初始管理员账号 {}，绑定地址 {}", admin.getUsername(), admin.getChainAddress());
        return true;
    }
}
