package com.qhx.back.auth;

import cn.hutool.json.JSONUtil;
import com.qhx.back.enums.UserRole;
import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.model.UserAccount;
import com.qhx.back.service.AuthService;
import com.qhx.back.support.FakeWeBaseFront;
import com.qhx.back.task.IotDataSimulatorTask;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录限流：按「账号 + IP」统计失败次数，超限 429 + Retry-After，登录成功清零。
 * 用很小的阈值 / 锁定时长（见 @SpringBootTest properties）让用例快速跑完。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:login_rate_limit_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/auth-schema.sql,classpath:db/chain-tx-schema.sql,classpath:db/business-schema.sql",
        "auth.bootstrap-admin.username=admin",
        "auth.bootstrap-admin.password=" + LoginRateLimitTest.ADMIN_PASSWORD,
        "auth.rate-limit.max-attempts=3",
        "auth.rate-limit.window-seconds=300",
        "auth.rate-limit.lockout-seconds=2",
})
@AutoConfigureMockMvc
class LoginRateLimitTest {

    static final String ADMIN_PASSWORD = "admin-rl-test-password";
    private static final String USER_PASSWORD = "user-rl-test-password";
    private static final String WRONG_PASSWORD = "wrong-password";
    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final String IP_A = "10.0.0.1";
    private static final String IP_B = "10.0.0.2";

    private static FakeWeBaseFront fakeWeBase;

    @MockBean
    private IotDataSimulatorTask iotDataSimulatorTask;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private AuthService authService;

    @DynamicPropertySource
    static void webaseUrl(DynamicPropertyRegistry registry) throws IOException {
        if (fakeWeBase == null) {
            fakeWeBase = new FakeWeBaseFront();
        }
        registry.add("webase-front.url", fakeWeBase::baseUrl);
    }

    @AfterAll
    static void stopFake() {
        if (fakeWeBase != null) {
            fakeWeBase.close();
        }
    }

    @BeforeEach
    void reset() {
        fakeWeBase.reset();
    }

    @Test
    void 同账号同IP连续失败达到上限后返回429带RetryAfter() throws Exception {
        UserAccount user = seedUser();
        // max-attempts=3：前两次错误密码仍是普通的 401
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        // 第三次错误密码把计数打满，锁定生效
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        // 被锁定期间，即使密码正确也直接 429，不再校验密码
        MvcResult limited = loginWithIp(user.getUsername(), USER_PASSWORD, IP_A, 429);
        String retryAfter = limited.getResponse().getHeader("Retry-After");
        assertNotNull(retryAfter, "429 响应必须带 Retry-After");
        int seconds = Integer.parseInt(retryAfter);
        assertTrue(seconds > 0 && seconds <= 2, "Retry-After 应在锁定时长范围内，实际=" + seconds);
    }

    @Test
    void 不同IP的同账号不共享限流计数() throws Exception {
        UserAccount user = seedUser();
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        // IP_A 已锁定
        loginWithIp(user.getUsername(), USER_PASSWORD, IP_A, 429);
        // 换一个 IP，同一账号不受影响，密码正确直接登录成功
        loginWithIp(user.getUsername(), USER_PASSWORD, IP_B, 200);
    }

    @Test
    void 登录成功清零计数() throws Exception {
        UserAccount user = seedUser();
        // 差一次就到上限
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        // 登录成功，计数清零
        loginWithIp(user.getUsername(), USER_PASSWORD, IP_A, 200);
        // 清零后再错两次也不该被锁定（累计到 4 次失败但都在清零之后重新计数）
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        loginWithIp(user.getUsername(), USER_PASSWORD, IP_A, 200);
    }

    @Test
    void 锁定到期后自动恢复() throws Exception {
        UserAccount user = seedUser();
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        loginWithIp(user.getUsername(), WRONG_PASSWORD, IP_A, 401);
        loginWithIp(user.getUsername(), USER_PASSWORD, IP_A, 429);
        // lockout-seconds=2，多睡一会儿等锁定过期
        Thread.sleep(2500);
        loginWithIp(user.getUsername(), USER_PASSWORD, IP_A, 200);
    }

    private UserAccount seedUser() {
        int n = SEQ.incrementAndGet();
        UserAccount user = new UserAccount();
        user.setUsername(UserRole.PRODUCER.name().toLowerCase() + "_rl_" + n);
        user.setPasswordHash(authService.hashPassword(USER_PASSWORD));
        user.setRole(UserRole.PRODUCER.name());
        user.setChainAddress(String.format("0x%040x", n));
        user.setCompanyName("公司" + n);
        user.setEnabled(true);
        user.setCreatedAt(new Date());
        user.setUpdatedAt(new Date());
        userAccountMapper.insert(user);
        return user;
    }

    private MvcResult loginWithIp(String username, String password, String ip, int expectedStatus) throws Exception {
        String content = JSONUtil.createObj().set("username", username).set("password", password).toString();
        MockHttpServletRequestBuilder builder = post("/login").contentType(MediaType.APPLICATION_JSON)
                .content(content).with(req -> {
                    req.setRemoteAddr(ip);
                    return req;
                });
        return mvc.perform(builder).andExpect(status().is(expectedStatus)).andReturn();
    }
}
