package com.qhx.back.flow;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qhx.back.enums.UserRole;
import com.qhx.back.mapper.ChainTxMapper;
import com.qhx.back.mapper.FileObjectMapper;
import com.qhx.back.mapper.TraceAssignmentLogMapper;
import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.UserAccount;
import com.qhx.back.service.AuthService;
import com.qhx.back.support.FakeKubo;
import com.qhx.back.support.FakeWeBaseFront;
import com.qhx.back.task.IotDataSimulatorTask;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务流程类测试的公共部分：Spring 上下文、WeBASE-Front 替身（打开合约模拟）、kubo 替身、建号 / 登录 / 上传 / 请求工具。
 * 子类决定数据库：H2（CI）或真实 MySQL 容器（设置 MYSQL_IT_URL 时）。
 * 文件上限在测试里调成 64 KB，便于构造超限文件。
 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/auth-schema.sql,classpath:db/chain-tx-schema.sql,classpath:db/business-schema.sql",
        "auth.bootstrap-admin.username=admin",
        "auth.bootstrap-admin.password=" + FlowTestSupport.ADMIN_PASSWORD,
        "auth.bootstrap-admin.address=" + FlowTestSupport.ADMIN_ADDRESS,
        "webase-front.read-timeout-ms=800",
        "file.max-bytes=" + FlowTestSupport.MAX_BYTES,
        // 测试里手动触发清理，不让定时任务插进来
        "file.orphan.cleanup-enabled=false",
})
@AutoConfigureMockMvc
abstract class FlowTestSupport {

    static final String ADMIN_PASSWORD = "admin-test-password";
    static final String ADMIN_ADDRESS = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static final int MAX_BYTES = 65536;
    static final String USER_PASSWORD = "user-test-password";
    static final AtomicInteger SEQ = new AtomicInteger(1);
    // 真实 MySQL 上可能重复运行：用户名、地址、溯源号都带随机前缀，避免与上次运行冲突
    static final int RUN = ThreadLocalRandom.current().nextInt(0x10000, 0xfffff);

    static FakeWeBaseFront fake;
    static FakeKubo kubo;

    @MockBean
    IotDataSimulatorTask iotDataSimulatorTask;

    @Autowired
    MockMvc mvc;
    @Autowired
    UserAccountMapper userAccountMapper;
    @Autowired
    ChainTxMapper chainTxMapper;
    @Autowired
    TraceAssignmentLogMapper assignmentLogMapper;
    @Autowired
    FileObjectMapper fileObjectMapper;
    @Autowired
    AuthService authService;

    @DynamicPropertySource
    static void doubles(DynamicPropertyRegistry registry) throws IOException {
        if (fake == null) {
            fake = new FakeWeBaseFront();
        }
        if (kubo == null) {
            kubo = new FakeKubo();
        }
        registry.add("webase-front.url", fake::baseUrl);
        registry.add("ipfs.api-url", kubo::apiUrl);
    }

    @AfterAll
    static void stopDoubles() {
        if (fake != null) {
            fake.close();
            fake = null;
        }
        if (kubo != null) {
            kubo.close();
            kubo = null;
        }
    }

    @BeforeEach
    void resetFake() {
        fake.simulateContract();
        fake.reset();
        kubo.corruptCat(false);
    }

    // ================================================================ 账号与批次

    static final class Party {
        final UserAccount user;
        final String token;

        Party(UserAccount user, String token) {
            this.user = user;
            this.token = token;
        }
    }

    Party party(UserRole role) throws Exception {
        UserAccount user = account(role);
        return new Party(user, login(user.getUsername(), USER_PASSWORD));
    }

    /** 只建账号不登录（用于「旧批次写入者」等场景） */
    UserAccount account(UserRole role) {
        int n = SEQ.incrementAndGet();
        UserAccount user = new UserAccount();
        user.setUsername("bf" + RUN + "_" + role.name().toLowerCase() + "_" + n);
        user.setPasswordHash(authService.hashPassword(USER_PASSWORD));
        user.setRole(role.name());
        user.setChainAddress(String.format("0x%08x%032x", RUN, n));
        user.setCompanyName(role.getDesc() + n);
        user.setEnabled(true);
        user.setCreatedAt(new Date());
        user.setUpdatedAt(new Date());
        userAccountMapper.insert(user);
        return user;
    }

    String produced(Party p, Party d) throws Exception {
        String tn = traceNumber();
        perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn, d.user.getUsername(), "2026-01-01", cert(p))), p.token, 200);
        return tn;
    }

    String distributed(Party p, Party d, Party r) throws Exception {
        String tn = produced(p, d);
        perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, r.user.getUsername(), "10", "100", cert(d))), d.token, 200);
        return tn;
    }

    static String traceNumber() {
        return String.format("BF%X-%d", RUN, SEQ.incrementAndGet());
    }

    static String address() {
        return String.format("0x%08x%032x", RUN + 1, SEQ.incrementAndGet());
    }

    static String producerBody(String tn, String distributor, String productTime, String cert) {
        return "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"农场A\",\"productName\":\"苹果\","
                + "\"productionLocation\":\"烟台\",\"variety\":\"红富士\",\"productionBatch\":\"B001\","
                + "\"productionCert\":\"" + cert + "\",\"productTime\":\"" + productTime + "\","
                + "\"distributorUsername\":\"" + distributor + "\"}";
    }

    /** price / quantity 原样拼进 JSON，便于构造小数、字符串等非法值 */
    static String distributorBody(String tn, String retailer, String price, String quantity, String report) {
        return "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"仓配B\",\"storageCondition\":\"冷藏\","
                + "\"transportMethod\":\"冷链车\",\"distributeBatch\":\"D001\",\"storageLocation\":\"济南\","
                + "\"distributePrice\":" + price + ",\"distributeQuantity\":" + quantity + ","
                + "\"inspectionReport\":\"" + report + "\",\"retailerUsername\":\"" + retailer + "\"}";
    }

    static String retailerBody(String tn, String quantity, String saleTime) {
        return "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"门店C\",\"salePrice\":20,\"saleQuantity\":" + quantity + ","
                + "\"shelfLife\":7,\"invoiceNo\":\"INV-001\",\"saleTime\":\"" + saleTime + "\"}";
    }

    static String createUserBody(String username, String role, String address) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + USER_PASSWORD + "\",\"role\":\"" + role
                + "\",\"chainAddress\":\"" + address + "\",\"companyName\":\"测试公司\"}";
    }

    // ================================================================ 文件

    /** 一个合法的小 PNG（文件头正确），tag 不同内容就不同 */
    static byte[] png(String tag) {
        byte[] body = tag.getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[8 + body.length];
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(head, 0, b, 0, 8);
        System.arraycopy(body, 0, b, 8, body.length);
        return b;
    }

    static byte[] pdf(String tag) {
        return ("%PDF-1.4\n% " + tag + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
    }

    /** 当前账号上传一张新的 PNG，返回 CID */
    String cert(Party who) throws Exception {
        return upload(who, "cert-" + SEQ.incrementAndGet() + ".png", png("cert-" + RUN + "-" + SEQ.incrementAndGet()), 200)
                .getJSONObject("data").getStr("cid");
    }

    JSONObject upload(Party who, String fileName, byte[] content, int expectedStatus) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "application/octet-stream", content);
        return perform(multipart("/upload").file(file), who.token, expectedStatus);
    }

    // ================================================================ 请求与断言

    static JSONObject find(JSONArray list, String tn) {
        for (int i = 0; i < list.size(); i++) {
            if (tn.equals(list.getJSONObject(i).getStr("traceNumber"))) {
                return list.getJSONObject(i);
            }
        }
        return null;
    }

    /** GET /batches 的一页 */
    JSONArray batches(Party who) throws Exception {
        return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/batches").param("size", "100"),
                who.token, 200).getJSONObject("data").getJSONArray("records");
    }

    static void assertFields(JSONObject body, String... fields) {
        assertEquals(400, body.getInt("code"), body.toString());
        JSONArray errors = body.getJSONObject("data").getJSONArray("errors");
        for (String f : fields) {
            boolean found = false;
            for (int i = 0; i < errors.size(); i++) {
                found |= f.equals(errors.getJSONObject(i).getStr("field"));
            }
            assertTrue(found, "缺少字段错误 " + f + "：" + body);
        }
    }

    String login(String username, String password) throws Exception {
        return loginRaw(username, password, 200).getJSONObject("data").getStr("token");
    }

    JSONObject loginRaw(String username, String password, int expectedStatus) throws Exception {
        String content = JSONUtil.createObj().set("username", username).set("password", password).toString();
        MvcResult result = mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().is(expectedStatus)).andReturn();
        return body(result);
    }

    JSONObject perform(MockHttpServletRequestBuilder builder, String token, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(builder.header("Authorization", "Bearer " + token)).andReturn();
        JSONObject body = body(result);
        assertEquals(expectedStatus, result.getResponse().getStatus(), body.toString());
        return body;
    }

    static JSONObject body(MvcResult result) throws Exception {
        String s = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isEmpty() || !s.startsWith("{") ? new JSONObject() : JSONUtil.parseObj(s);
    }

    List<ChainTx> rows(String tn) {
        return chainTxMapper.selectList(new LambdaQueryWrapper<ChainTx>().eq(ChainTx::getTraceNumber, tn));
    }
}
