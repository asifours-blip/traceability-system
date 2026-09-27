package com.qhx.back.config;

import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.model.UserAccount;
import com.qhx.back.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    private static final String OWNER = "0x" + "a".repeat(40);

    @Mock
    private UserAccountMapper userAccountMapper;
    @Mock
    private AuthService authService;

    private AdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new AdminBootstrap();
        ReflectionTestUtils.setField(bootstrap, "userAccountMapper", userAccountMapper);
        ReflectionTestUtils.setField(bootstrap, "authService", authService);
        ReflectionTestUtils.setField(bootstrap, "username", "admin");
        ReflectionTestUtils.setField(bootstrap, "address", OWNER);
    }

    @Test
    void 缺少初始密码_跳过且不建账号() {
        when(userAccountMapper.selectCount(any())).thenReturn(0L);
        ReflectionTestUtils.setField(bootstrap, "password", "");
        assertFalse(bootstrap.bootstrap());
        verify(userAccountMapper, never()).insert(any(UserAccount.class));
        verify(authService, never()).hashPassword(any());
    }

    @Test
    void 缺少初始密码_启动不抛异常() {
        when(userAccountMapper.selectCount(any())).thenReturn(0L);
        ReflectionTestUtils.setField(bootstrap, "password", "");
        bootstrap.run(null);
        verify(userAccountMapper, never()).insert(any(UserAccount.class));
    }

    @Test
    void 初始密码过短_跳过() {
        when(userAccountMapper.selectCount(any())).thenReturn(0L);
        ReflectionTestUtils.setField(bootstrap, "password", "short");
        assertFalse(bootstrap.bootstrap());
        verify(userAccountMapper, never()).insert(any(UserAccount.class));
    }

    @Test
    void 绑定地址非法_跳过() {
        // CI 里 CONTRACT_OWNER=0x0，引导应跳过而不是建出一个无法签名的管理员
        when(userAccountMapper.selectCount(any())).thenReturn(0L);
        ReflectionTestUtils.setField(bootstrap, "password", "strong-password");
        ReflectionTestUtils.setField(bootstrap, "address", "0x0");
        assertFalse(bootstrap.bootstrap());
        verify(userAccountMapper, never()).insert(any(UserAccount.class));
    }

    @Test
    void 已有管理员_不覆盖() {
        when(userAccountMapper.selectCount(any())).thenReturn(1L);
        ReflectionTestUtils.setField(bootstrap, "password", "strong-password");
        assertFalse(bootstrap.bootstrap());
        verify(userAccountMapper, never()).insert(any(UserAccount.class));
    }

    @Test
    void 配置齐全_创建管理员且只存哈希() {
        when(userAccountMapper.selectCount(any())).thenReturn(0L);
        when(authService.hashPassword("strong-password")).thenReturn("$2a$10$hash");
        ReflectionTestUtils.setField(bootstrap, "password", "strong-password");

        assertTrue(bootstrap.bootstrap());

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountMapper).insert(captor.capture());
        UserAccount admin = captor.getValue();
        assertEquals("admin", admin.getUsername());
        assertEquals("ADMIN", admin.getRole());
        assertEquals(OWNER, admin.getChainAddress());
        assertEquals("$2a$10$hash", admin.getPasswordHash());
        assertTrue(admin.getEnabled());
    }
}
