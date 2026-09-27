package com.qhx.back.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qhx.back.exception.AuthException;
import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.mapper.UserSessionMapper;
import com.qhx.back.model.UserAccount;
import com.qhx.back.model.UserSession;
import com.qhx.back.model.vo.LoginVO;
import com.qhx.back.model.vo.UserVO;
import com.qhx.back.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
public class AuthServiceImpl implements AuthService
{
    private static final String LOGIN_FAILED = "用户名或密码错误";

    private final SecureRandom secureRandom = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    // 用户不存在时也跑一次 BCrypt，避免靠响应时间枚举用户名
    private final String dummyHash = passwordEncoder.encode("dummy-password-for-timing");

    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private UserSessionMapper userSessionMapper;

    @Value("${auth.token-ttl-hours:12}")
    private long tokenTtlHours;

    @Override
    public LoginVO login(String username, String password)
    {
        if (StrUtil.isBlank(username) || StrUtil.isEmpty(password)) {
            throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, LOGIN_FAILED);
        }
        UserAccount user = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, username.trim()));
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, LOGIN_FAILED);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, LOGIN_FAILED);
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "账号已停用");
        }

        // 256 位随机 token，库里只存 sha256
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + TimeUnit.HOURS.toMillis(tokenTtlHours));

        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setTokenHash(hashToken(token));
        session.setExpiresAt(expiresAt);
        session.setRevoked(false);
        session.setCreatedAt(now);
        userSessionMapper.insert(session);

        return new LoginVO(token, expiresAt, UserVO.of(user));
    }

    @Override
    public void logout(String token)
    {
        if (StrUtil.isEmpty(token)) {
            return;
        }
        userSessionMapper.update(null, new LambdaUpdateWrapper<UserSession>()
                .set(UserSession::getRevoked, true)
                .eq(UserSession::getTokenHash, hashToken(token)));
    }

    @Override
    public UserAccount authenticate(String token)
    {
        if (StrUtil.isEmpty(token)) {
            return null;
        }
        UserSession session = userSessionMapper.selectOne(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getTokenHash, hashToken(token)));
        if (session == null || Boolean.TRUE.equals(session.getRevoked())) {
            return null;
        }
        if (session.getExpiresAt() == null || !session.getExpiresAt().after(new Date())) {
            return null;
        }
        UserAccount user = userAccountMapper.selectById(session.getUserId());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            return null;
        }
        return user;
    }

    @Override
    public void revokeAllSessions(Long userId)
    {
        userSessionMapper.update(null, new LambdaUpdateWrapper<UserSession>()
                .set(UserSession::getRevoked, true)
                .eq(UserSession::getUserId, userId)
                .eq(UserSession::getRevoked, false));
    }

    @Override
    public String hashPassword(String rawPassword)
    {
        return passwordEncoder.encode(rawPassword);
    }

    static String hashToken(String token)
    {
        return DigestUtil.sha256Hex(token);
    }
}
