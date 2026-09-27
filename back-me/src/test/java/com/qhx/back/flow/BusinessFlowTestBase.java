package com.qhx.back.flow;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qhx.back.enums.UserRole;
import com.qhx.back.mapper.ChainTxMapper;
import com.qhx.back.mapper.TraceAssignmentLogMapper;
import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.TraceAssignmentLog;
import com.qhx.back.model.UserAccount;
import com.qhx.back.service.AuthService;
import com.qhx.back.service.IPFSService;
import com.qhx.back.support.FakeWeBaseFront;
import com.qhx.back.task.IotDataSimulatorTask;
import io.ipfs.api.IPFS;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static com.qhx.back.support.FakeWeBaseFront.json;
import static com.qhx.back.support.FakeWeBaseFront.receiptTimeout;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务闭环测试：生产 → 分销 → 零售 → 消费者扫码，外加归属、交接、校验、重复提交、更正、公开文件、建号幂等。
 * WeBASE-Front 用本地替身并打开合约模拟（阶段顺序、只写一次、读回结构与真实链一致）；IPFS 用 Mock。
 * 子类决定数据库：H2（CI）或真实 MySQL 容器（设置 MYSQL_IT_URL 时）。
 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/auth-schema.sql,classpath:db/chain-tx-schema.sql,classpath:db/business-schema.sql",
        "auth.bootstrap-admin.username=admin",
        "auth.bootstrap-admin.password=" + BusinessFlowTestBase.ADMIN_PASSWORD,
        "auth.bootstrap-admin.address=" + BusinessFlowTestBase.ADMIN_ADDRESS,
        "webase-front.read-timeout-ms=800",
})
@AutoConfigureMockMvc
abstract class BusinessFlowTestBase {

    static final String ADMIN_PASSWORD = "admin-test-password";
    static final String ADMIN_ADDRESS = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String USER_PASSWORD = "user-test-password";
    private static final AtomicInteger SEQ = new AtomicInteger(1);
    // 真实 MySQL 上可能重复运行：用户名、地址、溯源号都带随机前缀，避免与上次运行冲突
    private static final int RUN = ThreadLocalRandom.current().nextInt(0x10000, 0xfffff);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};

    static FakeWeBaseFront fake;

    // IPFS 客户端在构造时就连守护进程，测试里整体替换；文件读写走 IPFSService 的 Mock
    @MockBean
    IPFS ipfs;
    @MockBean
    IPFSService ipfsService;
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
    AuthService authService;

    @DynamicPropertySource
    static void webaseUrl(DynamicPropertyRegistry registry) throws IOException {
        if (fake == null) {
            fake = new FakeWeBaseFront();
        }
        registry.add("webase-front.url", fake::baseUrl);
    }

    @AfterAll
    static void stopFake() {
        if (fake != null) {
            fake.close();
            fake = null;
        }
    }

    @BeforeEach
    void resetFake() {
        fake.simulateContract();
        fake.reset();
    }

    // ================================================================ 主流程

    @Test
    void 主流程_指定交接_三阶段上链_消费者只见公开字段_读回公开文件_追加更正() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        Party r = party(UserRole.RETAILER);
        String tn = traceNumber();

        // 生产：指定分销商
        JSONObject prod = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn, d.user.getUsername(), "2026-01-01")), p.token, 200);
        assertEquals("CONFIRMED", prod.getJSONObject("data").getStr("state"));
        assertEquals(p.user.getChainAddress(), fake.requestsFor("newAgroFood").get(0).user);

        // 分销：被指定的分销商写入，并指定零售商
        perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, r.user.getUsername(), "100", "100")), d.token, 200);
        // 零售：被指定的零售商写入
        perform(post("/retailer/add").contentType(MediaType.APPLICATION_JSON)
                .content(retailerBody(tn, "30", "2026-02-01")), r.token, 200);

        // 三方各自的列表都能看到这个批次，状态均为已上链
        for (Party who : List.of(p, d, r)) {
            JSONArray list = perform(get("/batches"), who.token, 200).getJSONArray("data");
            JSONObject row = find(list, tn);
            assertNotNull(row, who.user.getUsername() + " 看不到自己的批次");
            for (String stage : List.of("PRODUCTION", "DISTRIBUTION", "RETAIL")) {
                assertEquals("CONFIRMED", row.getJSONObject("stages").getJSONObject(stage).getStr("status"));
            }
        }
        JSONObject detail = perform(get("/batches/" + tn), p.token, 200).getJSONObject("data");
        JSONObject prodStage = detail.getJSONArray("stages").getJSONObject(0);
        assertEquals("CONFIRMED", prodStage.getStr("status"));
        assertNotNull(prodStage.getStr("txHash"));
        assertTrue(prodStage.getLong("blockNumber") > 0);
        assertEquals(100L, detail.getJSONArray("stages").getJSONObject(1).getJSONObject("data").getLong("distributeQuantity"));

        // 消费者（免登录）：只有公开字段
        MvcResult pub = mvc.perform(get("/trace/detail/" + tn)).andExpect(status().isOk()).andReturn();
        JSONObject pubData = body(pub).getJSONObject("data");
        assertEquals("苹果", pubData.getJSONObject("producer").getStr("productName"));
        assertTrue(pubData.getJSONObject("producer").getBool("hasFile"));
        assertFalse(pubData.getJSONObject("producer").containsKey("productionCert"));
        JSONObject pubDist = pubData.getJSONObject("distributor");
        assertEquals("冷链车", pubDist.getStr("transportMethod"));
        for (String hidden : List.of("distributePrice", "distributeQuantity", "storageLocation", "inspectionReport", "distributeBatch")) {
            assertFalse(pubDist.containsKey(hidden), "公开视图不应包含 " + hidden);
        }
        JSONObject pubRetail = pubData.getJSONObject("retailer");
        for (String hidden : List.of("salePrice", "saleQuantity", "invoiceNo")) {
            assertFalse(pubRetail.containsKey(hidden), "公开视图不应包含 " + hidden);
        }
        String pubText = pubData.toString();
        assertFalse(pubText.contains(p.user.getChainAddress()), "公开视图不应包含链上地址");
        assertFalse(pubText.contains(p.user.getUsername()), "公开视图不应包含用户名");
        assertNotNull(pubData.getJSONArray("stages").getJSONObject(0).getStr("txHash"));

        // 公开文件：CID 从链上该阶段读出
        when(ipfsService.loadFile("QmCert" + RUN)).thenReturn(PNG);
        MvcResult file = mvc.perform(get("/trace/" + tn + "/file/production")).andExpect(status().isOk()).andReturn();
        assertArrayEquals(PNG, file.getResponse().getContentAsByteArray());
        assertEquals("image/png", file.getResponse().getContentType());

        // 更正：生产阶段写入者追加一条，消费者页能看到公开字段的更正
        JSONObject corr = perform(post("/batches/" + tn + "/corrections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"PRODUCTION\",\"reason\":\"产地录入笔误\",\"content\":{\"productionLocation\":\"烟台市栖霞\"}}"),
                p.token, 200).getJSONObject("data");
        assertEquals("productionLocation", corr.getJSONArray("fields").getJSONObject(0).getStr("field"));
        JSONObject pubAfter = body(mvc.perform(get("/trace/detail/" + tn)).andReturn()).getJSONObject("data");
        JSONObject pubCorr = pubAfter.getJSONArray("stages").getJSONObject(0).getJSONArray("corrections").getJSONObject(0);
        assertEquals("烟台市栖霞", pubCorr.getJSONArray("fields").getJSONObject(0).getStr("value"));
        assertFalse(pubCorr.containsKey("authorUsername"));
        // 链上原始记录不变
        assertEquals("烟台", pubAfter.getJSONObject("producer").getStr("productionLocation"));
    }

    // ================================================================ 归属与交接

    @Test
    void 非指定的分销商写分销_403且不发交易() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        Party other = party(UserRole.DISTRIBUTOR);
        Party r = party(UserRole.RETAILER);
        String tn = produced(p, d);

        JSONObject body = perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, r.user.getUsername(), "10", "100")), other.token, 403);
        assertTrue(body.getStr("mes").contains("指定的分销商"), body.toString());
        assertTrue(fake.requestsFor("addTraceInfoByDistributor").isEmpty());
        perform(get("/batches/" + tn), other.token, 403);
    }

    @Test
    void 非指定的零售商写零售_403且不发交易() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        Party r = party(UserRole.RETAILER);
        Party other = party(UserRole.RETAILER);
        String tn = distributed(p, d, r);

        JSONObject body = perform(post("/retailer/add").contentType(MediaType.APPLICATION_JSON)
                .content(retailerBody(tn, "10", "2026-02-01")), other.token, 403);
        assertTrue(body.getStr("mes").contains("指定的零售商"), body.toString());
        assertTrue(fake.requestsFor("addTraceInfoByRetailer").isEmpty());
    }

    @Test
    void 生产商看不到也改不了别人的批次() throws Exception {
        Party owner = party(UserRole.PRODUCER);
        Party intruder = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        Party d2 = party(UserRole.DISTRIBUTOR);
        String tn = produced(owner, d);

        assertNull(find(perform(get("/batches"), intruder.token, 200).getJSONArray("data"), tn));
        assertNotNull(find(perform(get("/batches"), owner.token, 200).getJSONArray("data"), tn));
        perform(get("/batches/" + tn), intruder.token, 403);
        perform(put("/batches/" + tn + "/distributor").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + d2.user.getUsername() + "\"}"), intruder.token, 403);
        perform(post("/batches/" + tn + "/corrections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"PRODUCTION\",\"reason\":\"x\",\"content\":{\"variety\":\"y\"}}"), intruder.token, 403);

        // 用同一个溯源号抢建档：链上已存在 → 409，不发交易；链下批次仍归原生产商
        fake.reset();
        perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn, d2.user.getUsername(), "2026-01-01")), intruder.token, 409);
        assertTrue(fake.requestsFor("newAgroFood").isEmpty());
        assertEquals(d.user.getUsername(),
                perform(get("/batches/" + tn), owner.token, 200).getJSONObject("data").getJSONObject("distributor").getStr("username"));
    }

    @Test
    void 生产商在分销前可变更分销商_留历史_分销上链后不能再改() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d1 = party(UserRole.DISTRIBUTOR);
        Party d2 = party(UserRole.DISTRIBUTOR);
        Party r = party(UserRole.RETAILER);
        String tn = produced(p, d1);

        perform(put("/batches/" + tn + "/distributor").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + d2.user.getUsername() + "\",\"reason\":\"原分销商停运\"}"), p.token, 200);
        // 原分销商失去权限，新分销商可以写
        perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, r.user.getUsername(), "10", "100")), d1.token, 403);
        perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, r.user.getUsername(), "10", "100")), d2.token, 200);
        // 分销已上链：不能再改
        perform(put("/batches/" + tn + "/distributor").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + d1.user.getUsername() + "\"}"), p.token, 409);

        List<TraceAssignmentLog> logs = assignmentLogMapper.selectList(new LambdaQueryWrapper<TraceAssignmentLog>()
                .eq(TraceAssignmentLog::getTraceNumber, tn).eq(TraceAssignmentLog::getStage, 2).orderByAsc(TraceAssignmentLog::getId));
        assertEquals(2, logs.size());
        assertNull(logs.get(0).getFromUserId());
        assertEquals(d1.user.getId(), logs.get(1).getFromUserId());
        assertEquals(d2.user.getId(), logs.get(1).getToUserId());
        assertEquals("原分销商停运", logs.get(1).getReason());
    }

    // ================================================================ 字段校验

    @Test
    void 字段校验失败_400并指明字段() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        Party r = party(UserRole.RETAILER);

        // 生产：溯源号格式、不存在的日期、分销商不存在
        JSONObject bad = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody("sy-lower", "nobody_" + RUN, "2026-02-30")), p.token, 400);
        assertFields(bad, "traceNumber", "productTime", "distributorUsername");
        // 生产日期晚于今天
        JSONObject future = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(traceNumber(), d.user.getUsername(), LocalDate.now().plusDays(3).toString())), p.token, 400);
        assertFields(future, "productTime");
        assertTrue(fake.requestsFor("newAgroFood").isEmpty());

        String tn = produced(p, d);
        // 分销：数量 0、价格带小数
        JSONObject badDist = perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, r.user.getUsername(), "10.5", "0")), d.token, 400);
        assertFields(badDist, "distributePrice", "distributeQuantity");
        // 数量不是数字：请求体解析失败也要指出字段
        JSONObject notNumber = perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, r.user.getUsername(), "10", "\"abc\"")), d.token, 400);
        assertFields(notNumber, "distributeQuantity");
        assertTrue(fake.requestsFor("addTraceInfoByDistributor").isEmpty());

        perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, r.user.getUsername(), "10", "100")), d.token, 200);
        // 零售：数量超过分销数量、日期早于生产日期
        JSONObject badRetail = perform(post("/retailer/add").contentType(MediaType.APPLICATION_JSON)
                .content(retailerBody(tn, "101", "2025-12-31")), r.token, 400);
        assertFields(badRetail, "saleQuantity", "saleTime");
        assertTrue(badRetail.getStr("mes").contains("saleQuantity"), badRetail.toString());
        // 缺字段
        JSONObject missing = perform(post("/retailer/add").contentType(MediaType.APPLICATION_JSON)
                .content("{\"traceNumber\":\"" + tn + "\"}"), r.token, 400);
        assertFields(missing, "companyName", "salePrice", "saleQuantity", "shelfLife", "invoiceNo", "saleTime");
        assertTrue(fake.requestsFor("addTraceInfoByRetailer").isEmpty());
    }

    // ================================================================ 重复提交

    @Test
    void 结果未知时重复提交_409且不再发请求_详情显示待确认可查证() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        Party r = party(UserRole.RETAILER);
        String tn = produced(p, d);

        fake.on("addTraceInfoByDistributor", req -> receiptTimeout());
        JSONObject first = perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, r.user.getUsername(), "10", "100")), d.token, 202);
        Long txId = first.getJSONObject("data").getLong("id");
        assertEquals("UNKNOWN", chainTxMapper.selectById(txId).getState());

        JSONObject again = perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, r.user.getUsername(), "10", "100")), d.token, 409);
        assertEquals(txId, again.getJSONObject("data").getLong("id"));
        assertEquals(1, fake.requestsFor("addTraceInfoByDistributor").size());

        JSONObject stage = perform(get("/batches/" + tn), d.token, 200).getJSONObject("data").getJSONArray("stages").getJSONObject(1);
        assertEquals("PENDING", stage.getStr("status"));
        assertEquals(txId, stage.getLong("txId"));
        assertTrue(stage.getBool("canVerify"));
        // 待确认期间不能变更分销商
        Party d2 = party(UserRole.DISTRIBUTOR);
        perform(put("/batches/" + tn + "/distributor").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + d2.user.getUsername() + "\"}"), p.token, 409);

        // 查证：链上未写入 → 释放业务键，详情显示可重新提交
        JSONObject verified = perform(post("/chain-tx/" + txId + "/verify"), d.token, 200).getJSONObject("data");
        assertEquals("NOT_WRITTEN", verified.getStr("conclusion"));
        assertEquals("RELEASED", perform(get("/batches/" + tn), d.token, 200).getJSONObject("data")
                .getJSONArray("stages").getJSONObject(1).getStr("status"));
    }

    // ================================================================ 更正

    @Test
    void 更正只能追加_只有该阶段写入者能提交() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        Party r = party(UserRole.RETAILER);
        String tn = distributed(p, d, r);

        // 分销商不是生产阶段的写入者
        JSONObject denied = perform(post("/batches/" + tn + "/corrections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"PRODUCTION\",\"reason\":\"想改\",\"content\":{\"variety\":\"富士\"}}"), d.token, 403);
        assertTrue(denied.getStr("mes").contains("写入者"), denied.toString());
        // 零售阶段尚未上链：没有可更正的记录
        perform(post("/batches/" + tn + "/corrections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"RETAIL\",\"reason\":\"x\",\"content\":{\"invoiceNo\":\"1\"}}"), r.token, 409);
        // 字段不属于该阶段 / 值不合规 → 400
        JSONObject badField = perform(post("/batches/" + tn + "/corrections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"DISTRIBUTION\",\"reason\":\"x\",\"content\":{\"salePrice\":\"1\",\"distributeQuantity\":\"-1\"}}"), d.token, 400);
        assertFields(badField, "content.salePrice", "content.distributeQuantity");

        // 写入者本人可以连续追加
        perform(post("/batches/" + tn + "/corrections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"DISTRIBUTION\",\"reason\":\"数量少录一位\",\"content\":{\"distributeQuantity\":\"1000\"}}"), d.token, 200);
        JSONObject second = perform(post("/batches/" + tn + "/corrections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"DISTRIBUTION\",\"reason\":\"运输方式补充\",\"content\":{\"transportMethod\":\"冷链车（-18℃）\"}}"), d.token, 200);
        Long id = second.getJSONObject("data").getLong("id");

        // 没有修改、删除更正的接口
        mvc.perform(put("/batches/" + tn + "/corrections/" + id).header("Authorization", "Bearer " + d.token)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().is4xxClientError());
        mvc.perform(delete("/batches/" + tn + "/corrections/" + id).header("Authorization", "Bearer " + d.token))
                .andExpect(status().is4xxClientError());

        JSONArray corrections = perform(get("/batches/" + tn), p.token, 200).getJSONObject("data")
                .getJSONArray("stages").getJSONObject(1).getJSONArray("corrections");
        assertEquals(2, corrections.size());
        // 消费者页只展示公开字段的更正：数量是非公开字段，那一条不出现
        JSONArray pub = body(mvc.perform(get("/trace/detail/" + tn)).andReturn()).getJSONObject("data")
                .getJSONArray("stages").getJSONObject(1).getJSONArray("corrections");
        assertEquals(1, pub.size());
        assertEquals("transportMethod", pub.getJSONObject(0).getJSONArray("fields").getJSONObject(0).getStr("field"));
    }

    // ================================================================ 公开文件

    @Test
    void 未绑定的CID不能公开读取_绑定的按链上CID读取() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        String tn = produced(p, d);
        when(ipfsService.loadFile(anyString())).thenReturn(PNG);

        // 任意 CID 走原接口必须登录
        mvc.perform(get("/fileBase64/QmUnbound" + RUN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/file/QmUnbound" + RUN)).andExpect(status().isUnauthorized());
        // 公开接口不接受 CID 参数，也不能借路径读未绑定阶段
        mvc.perform(get("/trace/" + tn + "/file/QmUnbound" + RUN)).andExpect(status().isBadRequest());
        mvc.perform(get("/trace/" + tn + "/file/retail")).andExpect(status().isBadRequest());
        mvc.perform(get("/trace/" + tn + "/file/distribution")).andExpect(status().isNotFound());
        mvc.perform(get("/trace/NOPE-" + RUN + "/file/production")).andExpect(status().isNotFound());
        verify(ipfsService, never()).loadFile(anyString());

        mvc.perform(get("/trace/" + tn + "/file/production")).andExpect(status().isOk());
        verify(ipfsService).loadFile("QmCert" + RUN);
    }

    // ================================================================ 建号幂等

    @Test
    void 建号_链上已有角色则跳过授权交易() throws Exception {
        String admin = login("admin", ADMIN_PASSWORD);
        fake.on("isDistributor", req -> json(200, "[true]"));
        String username = "dist_" + RUN + "_" + SEQ.incrementAndGet();
        JSONObject body = perform(post("/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(createUserBody(username, "DISTRIBUTOR", address())), admin, 200);
        assertEquals("ALREADY_ON_CHAIN", body.getJSONObject("data").getStr("roleState"));
        assertTrue(body.getJSONObject("data").getBool("enabled"));
        assertTrue(fake.requestsFor("addDistributor").isEmpty(), "链上已有角色时不应发授权交易");
        assertEquals(1, fake.requestsFor("isDistributor").size());
        login(username, USER_PASSWORD);
    }

    @Test
    void 建号_授权结果未知_账号待确认不可登录_查证后启用() throws Exception {
        String admin = login("admin", ADMIN_PASSWORD);
        fake.on("isRetailer", req -> json(200, "[false]"));
        fake.on("addRetailer", req -> receiptTimeout());
        String username = "ret_" + RUN + "_" + SEQ.incrementAndGet();
        JSONObject body = perform(post("/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(createUserBody(username, "RETAILER", address())), admin, 202);
        JSONObject user = body.getJSONObject("data").getJSONObject("user");
        assertEquals("PENDING", user.getStr("roleState"));
        assertFalse(user.getBool("enabled"));
        assertEquals(1, fake.requestsFor("addRetailer").size());
        loginRaw(username, USER_PASSWORD, 401);

        // 链上仍无角色：查证不启用
        perform(post("/admin/users/" + user.getLong("id") + "/verify-role"), admin, 409);
        loginRaw(username, USER_PASSWORD, 401);

        // 链上已有角色（原交易后来上链了）：查证后启用
        fake.on("isRetailer", req -> json(200, "[true]"));
        JSONObject verified = perform(post("/admin/users/" + user.getLong("id") + "/verify-role"), admin, 200).getJSONObject("data");
        assertTrue(verified.getBool("enabled"));
        assertEquals("GRANTED_BY_TX", verified.getStr("roleState"));
        assertEquals(1, fake.requestsFor("addRetailer").size(), "查证不应再发授权交易");
        login(username, USER_PASSWORD);
    }

    // ================================================================ 工具

    static final class Party {
        final UserAccount user;
        final String token;

        Party(UserAccount user, String token) {
            this.user = user;
            this.token = token;
        }
    }

    Party party(UserRole role) throws Exception {
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
        return new Party(user, login(user.getUsername(), USER_PASSWORD));
    }

    String produced(Party p, Party d) throws Exception {
        String tn = traceNumber();
        perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn, d.user.getUsername(), "2026-01-01")), p.token, 200);
        return tn;
    }

    String distributed(Party p, Party d, Party r) throws Exception {
        String tn = produced(p, d);
        perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, r.user.getUsername(), "10", "100")), d.token, 200);
        return tn;
    }

    static String traceNumber() {
        return String.format("BF%X-%d", RUN, SEQ.incrementAndGet());
    }

    static String address() {
        return String.format("0x%08x%032x", RUN + 1, SEQ.incrementAndGet());
    }

    static String producerBody(String tn, String distributor, String productTime) {
        return "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"农场A\",\"productName\":\"苹果\","
                + "\"productionLocation\":\"烟台\",\"variety\":\"红富士\",\"productionBatch\":\"B001\","
                + "\"productionCert\":\"QmCert" + RUN + "\",\"productTime\":\"" + productTime + "\","
                + "\"distributorUsername\":\"" + distributor + "\"}";
    }

    /** price / quantity 原样拼进 JSON，便于构造小数、字符串等非法值 */
    static String distributorBody(String tn, String retailer, String price, String quantity) {
        return "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"仓配B\",\"storageCondition\":\"冷藏\","
                + "\"transportMethod\":\"冷链车\",\"distributeBatch\":\"D001\",\"storageLocation\":\"济南\","
                + "\"distributePrice\":" + price + ",\"distributeQuantity\":" + quantity + ","
                + "\"inspectionReport\":\"QmReport" + RUN + "\",\"retailerUsername\":\"" + retailer + "\"}";
    }

    static String retailerBody(String tn, String quantity, String saleTime) {
        return "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"门店C\",\"salePrice\":20,\"saleQuantity\":" + quantity + ","
                + "\"shelfLife\":7,\"invoiceNo\":\"INV-001\",\"saleTime\":\"" + saleTime + "\"}";
    }

    static String createUserBody(String username, String role, String address) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + USER_PASSWORD + "\",\"role\":\"" + role
                + "\",\"chainAddress\":\"" + address + "\",\"companyName\":\"测试公司\"}";
    }

    static JSONObject find(JSONArray list, String tn) {
        for (int i = 0; i < list.size(); i++) {
            if (tn.equals(list.getJSONObject(i).getStr("traceNumber"))) {
                return list.getJSONObject(i);
            }
        }
        return null;
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
        return s.isEmpty() ? new JSONObject() : JSONUtil.parseObj(s);
    }

    List<ChainTx> rows(String tn) {
        return chainTxMapper.selectList(new LambdaQueryWrapper<ChainTx>().eq(ChainTx::getTraceNumber, tn));
    }
}
