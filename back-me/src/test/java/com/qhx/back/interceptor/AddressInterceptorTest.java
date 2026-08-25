package com.qhx.back.interceptor;

import com.qhx.back.context.AddressContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressInterceptorTest {

    private static final String ALLOW =
            "/login,/register,/getContractOwner,/getSystemInfo,/trace/detail";
    private static final String LEGAL = "0x" + "a".repeat(40);

    private AddressInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AddressInterceptor();
        ReflectionTestUtils.setField(interceptor, "allowPaths", ALLOW);
        AddressContext.clear();
    }

    @AfterEach
    void tearDown() {
        AddressContext.clear();
    }

    @Test
    void 白名单溯源详情_无address也放行() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/trace/detail/SY1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, resp, new Object()));
    }

    @Test
    void OPTIONS预检放行() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/producer/add");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, resp, new Object()));
        assertEquals(200, resp.getStatus());
    }

    @Test
    void 写接口缺address_拒绝且HTTP为401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/producer/add");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
        String body = resp.getContentAsString();
        assertTrue(body.contains("address"), body);
        assertFalse(body.contains("adddress"), body);
        assertTrue(body.contains("\"code\":401"), body);
    }

    @Test
    void 非法address_拒绝() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/producer/add");
        req.addHeader("address", "not-an-address");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("address"));
        assertFalse(resp.getContentAsString().contains("adddress"));
        assertTrue(resp.getContentAsString().contains("\"code\":401"));
    }

    @Test
    void 合法address_放行并写入上下文() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/producer/add");
        req.addHeader("address", LEGAL);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, resp, new Object()));
        assertEquals(LEGAL, AddressContext.getAddress());
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
        AddressContext.setAddress(LEGAL);
        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);
        assertEquals(null, AddressContext.getAddress());
    }
}
