package com.qhx.back.interceptor;

import com.qhx.back.context.AddressContext;
import com.qhx.back.context.UserContext;
import com.qhx.back.controller.TraceController;
import com.qhx.back.enums.UserRole;
import com.qhx.back.model.UserAccount;
import com.qhx.back.model.to.DistributorTo;
import com.qhx.back.model.to.ProducerTo;
import com.qhx.back.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddressInterceptorTest {

    private static final String ALLOW = "/login,/getSystemInfo,/trace/detail/**";
    private static final String BOUND = "0x" + "a".repeat(40);
    private static final String FORGED = "0x" + "f".repeat(40);
    private static final String TOKEN = "valid-token";

    @Mock
    private AuthService authService;

    private AddressInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AddressInterceptor();
        ReflectionTestUtils.setField(interceptor, "allowPaths", ALLOW);
        ReflectionTestUtils.setField(interceptor, "authService", authService);
        AddressContext.clear();
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        AddressContext.clear();
        UserContext.clear();
    }

    @Test
    void 白名单溯源详情_无token也放行() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/trace/detail/SY1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, resp, new Object()));
        verify(authService, never()).authenticate(anyString());
    }

    @Test
    void 白名单不能被分号或前缀拼接绕过() throws Exception {
        // 旧实现用 contains 匹配，/producer/add;/login 会被当成白名单
        for (String uri : new String[]{"/producer/add;/login", "/x/trace/detail/SY1", "/loginx", "/register"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(req, resp, new Object()), uri);
            assertEquals(401, resp.getStatus(), uri);
        }
    }

    @Test
    void OPTIONS预检放行() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/producer/add");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, resp, new Object()));
        assertEquals(200, resp.getStatus());
    }

    @Test
    void 写接口缺token_拒绝且HTTP为401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/producer/add");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
        String body = resp.getContentAsString();
        assertTrue(body.contains("\"code\":401"), body);
        assertTrue(body.contains("Bearer"), body);
    }

    @Test
    void 伪造address头_没有token仍然401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/producer/add");
        req.addHeader("address", FORGED);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
        assertNull(AddressContext.getAddress());
    }

    @Test
    void 非Bearer格式_401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/producer/add");
        req.addHeader("Authorization", "Basic " + TOKEN);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
        verify(authService, never()).authenticate(anyString());
    }

    @Test
    void token无效过期或撤销_401() throws Exception {
        when(authService.authenticate("bad-token")).thenReturn(null);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/producer/add");
        req.addHeader("Authorization", "Bearer bad-token");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"code\":401"));
    }

    @Test
    void 有效token_上下文写入服务端绑定地址_忽略address头() throws Exception {
        when(authService.authenticate(TOKEN)).thenReturn(user(UserRole.PRODUCER));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/producer/add");
        req.addHeader("Authorization", "Bearer " + TOKEN);
        req.addHeader("address", FORGED);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, resp, handler("addProducer", ProducerTo.class)));
        assertEquals(BOUND, AddressContext.getAddress());
        assertEquals("PRODUCER", UserContext.getUser().getRole());
    }

    @Test
    void 角色不符_403() throws Exception {
        when(authService.authenticate(TOKEN)).thenReturn(user(UserRole.PRODUCER));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/distributor/add");
        req.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, handler("addDistributor", DistributorTo.class)));
        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"code\":403"));
    }

    @Test
    void 拦截器不再写CORS头_交给WebConfig() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/trace/detail/SY1");
        req.addHeader("Origin", "http://localhost:8080");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        interceptor.preHandle(req, resp, new Object());
        assertEquals(null, resp.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void afterCompletion清理ThreadLocal() throws Exception {
        AddressContext.setAddress(BOUND);
        UserContext.setUser(user(UserRole.ADMIN));
        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);
        assertNull(AddressContext.getAddress());
        assertNull(UserContext.getUser());
    }

    private static UserAccount user(UserRole role) {
        UserAccount user = new UserAccount();
        user.setId(1L);
        user.setUsername("u1");
        user.setRole(role.name());
        user.setChainAddress(BOUND);
        user.setEnabled(true);
        return user;
    }

    private static HandlerMethod handler(String method, Class<?> paramType) throws NoSuchMethodException {
        return new HandlerMethod(new TraceController(), TraceController.class.getMethod(method, paramType));
    }
}
