package com.qhx.back.auth;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qhx.back.enums.UserRole;
import com.qhx.back.mapper.FileObjectMapper;
import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.mapper.UserSessionMapper;
import com.qhx.back.model.UserAccount;
import com.qhx.back.model.UserSession;
import com.qhx.back.service.AuthService;
import com.qhx.back.support.FakeWeBaseFront;
import com.qhx.back.support.TestFiles;
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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账号体系端到端测试：H2(MySQL 模式) + 本地 WeBASE-Front 替身，不连真实链 / IPFS / MySQL。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/auth-schema.sql,classpath:db/chain-tx-schema.sql,classpath:db/business-schema.sql",
        "auth.bootstrap-admin.username=admin",
        "auth.bootstrap-admin.password=" + AuthIntegrationTest.ADMIN_PASSWORD,
        "auth.bootstrap-admin.address=" + AuthIntegrationTest.ADMIN_ADDRESS,
})
@AutoConfigureMockMvc
class AuthIntegrationTest {

    // 测试专用值，只存在于内存库
    static final String ADMIN_PASSWORD = "admin-test-password";
    static final String ADMIN_ADDRESS = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String FORGED = "0xffffffffffffffffffffffffffffffffffffffff";
    private static final String USER_PASSWORD = "user-test-password";
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private static FakeWeBaseFront fakeWeBase;

    // 避免定时任务在测试里写 IoT 表
    @MockBean
    private IotDataSimulatorTask iotDataSimulatorTask;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private FileObjectMapper fileObjectMapper;
    @Autowired
    private UserSessionMapper userSessionMapper;
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

    // 生产信息必须指定下游分销商（后端规则），每个用例准备一个
    private String distributorUsername;

    @BeforeEach
    void resetFake() {
        fakeWeBase.reset();
        distributorUsername = seedUser(UserRole.DISTRIBUTOR).getUsername();
    }

    // ---------- 身份来源 ----------

    @Test
    void 伪造address头_没有token仍401且不发交易() throws Exception {
        mvc.perform(post("/producer/add").header("address", ADMIN_ADDRESS)
                        .contentType(MediaType.APPLICATION_JSON).content(producerBody("")))
                .andExpect(status().isUnauthorized());
        assertTrue(fakeWeBase.requests().isEmpty());
    }

    @Test
    void 伪造address头_签名地址仍是会话绑定地址() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String token = login(producer.getUsername(), USER_PASSWORD);

        JSONObject body = perform(post("/producer/add").header("address", FORGED)
                .contentType(MediaType.APPLICATION_JSON).content(producerBody("")), token, 200);
        assertEquals(200, body.getInt("code"));

        List<FakeWeBaseFront.Request> tx = fakeWeBase.requestsFor("newAgroFood");
        assertEquals(1, tx.size());
        assertEquals(producer.getChainAddress(), tx.get(0).user);
    }

    @Test
    void 请求体夹带地址不会被用作签名地址() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String token = login(producer.getUsername(), USER_PASSWORD);
        String smuggled = "\"address\":\"" + FORGED + "\",\"user\":\"" + FORGED
                + "\",\"roleAddress\":\"" + ADMIN_ADDRESS + "\",\"signer\":\"" + FORGED + "\",";

        perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON).content(producerBody(smuggled)), token, 200);

        List<FakeWeBaseFront.Request> tx = fakeWeBase.requestsFor("newAgroFood");
        assertEquals(1, tx.size());
        assertEquals(producer.getChainAddress(), tx.get(0).user);
    }

    // ---------- token 生命周期 ----------

    @Test
    void 无token返回401() throws Exception {
        MvcResult result = mvc.perform(get("/batches")).andExpect(status().isUnauthorized()).andReturn();
        assertEquals(401, json(result).getInt("code"));
        assertTrue(fakeWeBase.requests().isEmpty());
    }

    @Test
    void 过期token返回401() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String token = login(producer.getUsername(), USER_PASSWORD);
        perform(get("/getContractOwner"), token, 200);

        userSessionMapper.update(null, new LambdaUpdateWrapper<UserSession>()
                .set(UserSession::getExpiresAt, new Date(System.currentTimeMillis() - 1000))
                .eq(UserSession::getTokenHash, DigestUtil.sha256Hex(token)));

        perform(get("/getContractOwner"), token, 401);
    }

    @Test
    void 登出后token被撤销返回401() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String token = login(producer.getUsername(), USER_PASSWORD);
        perform(get("/getContractOwner"), token, 200);

        perform(post("/logout"), token, 200);

        perform(get("/getContractOwner"), token, 401);
    }

    @Test
    void 登录成功_库里只存token的sha256() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String token = login(producer.getUsername(), USER_PASSWORD);
        assertTrue(token.length() >= 43, "256 位随机数的 base64url 至少 43 字符");

        UserSession session = userSessionMapper.selectOne(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getUserId, producer.getId()));
        assertEquals(DigestUtil.sha256Hex(token), session.getTokenHash());
        assertNotEquals(token, session.getTokenHash());
        assertNotEquals(USER_PASSWORD, userAccountMapper.selectById(producer.getId()).getPasswordHash());
    }

    @Test
    void 错误密码与不存在的用户都登录失败() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        JSONObject wrong = loginRaw(producer.getUsername(), "wrong-password", 401);
        JSONObject unknown = loginRaw("no_such_user", USER_PASSWORD, 401);
        assertNull(wrong.get("data"));
        assertEquals(wrong.getStr("mes"), unknown.getStr("mes"));
    }

    // ---------- 角色鉴权 ----------

    @Test
    void 生产商调用分销接口返回403且不发交易() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String token = login(producer.getUsername(), USER_PASSWORD);

        JSONObject body = perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content("{\"traceNumber\":\"SY1\"}"), token, 403);
        assertEquals(403, body.getInt("code"));
        assertTrue(fakeWeBase.requests().isEmpty());
    }

    @Test
    void 非ADMIN不能管理用户() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        UserAccount victim = seedUser(UserRole.RETAILER);
        String token = login(producer.getUsername(), USER_PASSWORD);

        perform(get("/admin/users"), token, 403);
        perform(post("/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(createUserBody("sneaky_" + SEQ.get(), UserRole.PRODUCER, nextAddress(), "")), token, 403);
        perform(post("/admin/users/" + victim.getId() + "/disable"), token, 403);
        perform(get("/get/user/role").param("address", victim.getChainAddress()).param("role", "RETAILER"), token, 403);
        perform(post("/setSystemInfo").contentType(MediaType.APPLICATION_JSON).content("{}"), token, 403);

        assertTrue(fakeWeBase.requests().isEmpty());
        assertTrue(userAccountMapper.selectById(victim.getId()).getEnabled());
    }

    @Test
    void 公开注册接口已移除_未登录401() throws Exception {
        mvc.perform(post("/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"" + FORGED + "\",\"type\":\"0\",\"roleAddress\":\"" + ADMIN_ADDRESS + "\"}"))
                .andExpect(status().isUnauthorized());
        assertTrue(fakeWeBase.requests().isEmpty());
    }

    // ---------- 用户管理 ----------

    @Test
    void 管理员新建用户_签名地址是管理员地址() throws Exception {
        String adminToken = login("admin", ADMIN_PASSWORD);
        String newAddress = nextAddress();
        String username = "prod_" + SEQ.incrementAndGet();
        // 链上还没有该角色，才会发授权交易（已有角色时跳过，见 BusinessFlowTestBase）
        fakeWeBase.on("isProducer", r -> FakeWeBaseFront.json(200, "[false]"));
        // 夹带 signer/roleAddress/address，验证不会被当成签名地址
        String extra = "\"signer\":\"" + FORGED + "\",\"roleAddress\":\"" + FORGED + "\",\"address\":\"" + FORGED + "\",";

        JSONObject body = perform(post("/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(createUserBody(username, UserRole.PRODUCER, newAddress, extra)), adminToken, 200);
        assertEquals(200, body.getInt("code"), body.toString());
        assertFalse(body.getJSONObject("data").containsKey("passwordHash"));

        List<FakeWeBaseFront.Request> tx = fakeWeBase.requestsFor("addProducer");
        assertEquals(1, tx.size());
        assertEquals(ADMIN_ADDRESS, tx.get(0).user);
        assertEquals(Collections.singletonList(newAddress), tx.get(0).params.toList(String.class));

        // 新用户登录后，业务交易用的是自己绑定的地址
        fakeWeBase.reset();
        String userToken = login(username, USER_PASSWORD);
        TestFiles.seedUploaded(fileObjectMapper, authService.authenticate(userToken).getId(), "QmCid");
        perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON).content(producerBody("")), userToken, 200);
        assertEquals(newAddress, fakeWeBase.requestsFor("newAgroFood").get(0).user);
    }

    @Test
    void 停用用户后token立即失效且发出removeX交易() throws Exception {
        UserAccount retailer = seedUser(UserRole.RETAILER);
        String retailerToken = login(retailer.getUsername(), USER_PASSWORD);
        perform(get("/getContractOwner"), retailerToken, 200);
        String adminToken = login("admin", ADMIN_PASSWORD);

        JSONObject body = perform(post("/admin/users/" + retailer.getId() + "/disable"), adminToken, 200);
        assertEquals(200, body.getInt("code"), body.toString());

        List<FakeWeBaseFront.Request> tx = fakeWeBase.requestsFor("removeRetailer");
        assertEquals(1, tx.size());
        assertEquals(ADMIN_ADDRESS, tx.get(0).user);
        assertEquals(Collections.singletonList(retailer.getChainAddress()), tx.get(0).params.toList(String.class));
        assertTrue(fakeWeBase.requests().stream().noneMatch(r -> r.funcName.startsWith("renounce")));

        perform(get("/getContractOwner"), retailerToken, 401);
        loginRaw(retailer.getUsername(), USER_PASSWORD, 401);
        assertFalse(userAccountMapper.selectById(retailer.getId()).getEnabled());
    }

    // ---------- 公开接口 ----------

    @Test
    void 扫码详情与系统信息免登录() throws Exception {
        MvcResult info = mvc.perform(get("/getSystemInfo")).andExpect(status().isOk()).andReturn();
        assertEquals("溯源系统", json(info).getJSONObject("data").getStr("name"));

        // 免登录：不存在的溯源号返回 404 而不是 401
        MvcResult detail = mvc.perform(get("/trace/detail/SY-NONE")).andExpect(status().isNotFound()).andReturn();
        assertEquals(404, json(detail).getInt("code"));
    }

    // ---------- 工具方法 ----------

    private UserAccount seedUser(UserRole role) {
        int n = SEQ.incrementAndGet();
        UserAccount user = new UserAccount();
        user.setUsername(role.name().toLowerCase() + "_" + n);
        user.setPasswordHash(authService.hashPassword(USER_PASSWORD));
        user.setRole(role.name());
        user.setChainAddress(String.format("0x%040x", n));
        user.setCompanyName("公司" + n);
        user.setEnabled(true);
        user.setCreatedAt(new Date());
        user.setUpdatedAt(new Date());
        userAccountMapper.insert(user);
        // 阶段交易引用的文件必须是本账号上传的：本测试不关心文件，直接放一条上传记录
        if (role == UserRole.PRODUCER) {
            TestFiles.seedUploaded(fileObjectMapper, user.getId(), "QmCid");
        }
        return user;
    }

    private static String nextAddress() {
        return String.format("0x%040x", 100000 + SEQ.incrementAndGet());
    }

    private String login(String username, String password) throws Exception {
        JSONObject body = loginRaw(username, password, 200);
        return body.getJSONObject("data").getStr("token");
    }

    private JSONObject loginRaw(String username, String password, int expectedStatus) throws Exception {
        String content = JSONUtil.createObj().set("username", username).set("password", password).toString();
        MvcResult result = mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().is(expectedStatus)).andReturn();
        return json(result);
    }

    private JSONObject perform(MockHttpServletRequestBuilder builder, String token, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(builder.header("Authorization", "Bearer " + token))
                .andExpect(status().is(expectedStatus)).andReturn();
        return json(result);
    }

    private static JSONObject json(MvcResult result) throws Exception {
        return JSONUtil.parseObj(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String producerBody(String extraFields) {
        return "{" + extraFields + "\"traceNumber\":\"AUTH-" + SEQ.incrementAndGet() + "\",\"companyName\":\"农场A\",\"productName\":\"苹果\","
                + "\"productionLocation\":\"烟台\",\"variety\":\"红富士\",\"productionBatch\":\"B001\","
                + "\"productionCert\":\"QmCid\",\"productTime\":\"2026-01-01\",\"distributorUsername\":\"" + distributorUsername + "\"}";
    }

    private static String createUserBody(String username, UserRole role, String chainAddress, String extraFields) {
        return "{" + extraFields + "\"username\":\"" + username + "\",\"password\":\"" + USER_PASSWORD
                + "\",\"role\":\"" + role.name() + "\",\"chainAddress\":\"" + chainAddress + "\",\"companyName\":\"测试公司\"}";
    }
}
