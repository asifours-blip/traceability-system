package com.qhx.back.chain;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qhx.back.enums.UserRole;
import com.qhx.back.mapper.ChainTxMapper;
import com.qhx.back.mapper.UserAccountMapper;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.UserAccount;
import com.qhx.back.service.AuthService;
import com.qhx.back.support.FakeWeBaseFront;
import com.qhx.back.support.RawHttpStub;
import com.qhx.back.util.HttpUtil;
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
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.qhx.back.support.FakeWeBaseFront.callResult;
import static com.qhx.back.support.FakeWeBaseFront.callRevert;
import static com.qhx.back.support.FakeWeBaseFront.delayed;
import static com.qhx.back.support.FakeWeBaseFront.frontError;
import static com.qhx.back.support.FakeWeBaseFront.json;
import static com.qhx.back.support.FakeWeBaseFront.receipt;
import static com.qhx.back.support.FakeWeBaseFront.receiptRevert;
import static com.qhx.back.support.FakeWeBaseFront.receiptSuccess;
import static com.qhx.back.support.FakeWeBaseFront.receiptTimeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交易状态机与恢复的端到端测试：MockMvc + H2(MySQL 模式) + 本地 WeBASE-Front 替身（响应结构按真实链核验结果构造）。
 * 不连真实链 / IPFS / MySQL。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chain_tx_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/auth-schema.sql,classpath:db/chain-tx-schema.sql",
        "auth.bootstrap-admin.username=admin",
        "auth.bootstrap-admin.password=" + ChainTxLifecycleIntegrationTest.ADMIN_PASSWORD,
        "auth.bootstrap-admin.address=" + ChainTxLifecycleIntegrationTest.ADMIN_ADDRESS,
        // 测试里把读超时调短，替身延迟 2 秒即触发
        "webase-front.read-timeout-ms=800",
})
@AutoConfigureMockMvc
class ChainTxLifecycleIntegrationTest {

    static final String ADMIN_PASSWORD = "admin-test-password";
    static final String ADMIN_ADDRESS = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String ZERO = "0x0000000000000000000000000000000000000000";
    private static final String USER_PASSWORD = "user-test-password";
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private static FakeWeBaseFront fake;

    @MockBean
    private IPFS ipfs;
    @MockBean
    private IotDataSimulatorTask iotDataSimulatorTask;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private ChainTxMapper chainTxMapper;
    @Autowired
    private AuthService authService;
    @Autowired
    private HttpUtil httpUtil;

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
        }
    }

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    // ---------- 发交易：各种结果 ----------

    @Test
    void 成功_回执确认后返回200_记录CONFIRMED含哈希与块高() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String tn = traceNumber();

        JSONObject body = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn)), login(producer), 200);
        JSONObject data = body.getJSONObject("data");
        assertEquals("CONFIRMED", data.getStr("state"));
        assertNotNull(data.getStr("txHash"));
        assertTrue(data.getLong("blockNumber") > 0);

        ChainTx row = chainTxMapper.selectById(data.getLong("id"));
        assertEquals("trace:" + tn + ":PRODUCTION", row.getBizKey());
        assertEquals(1, row.getStage());
        assertEquals("newAgroFood", row.getFuncName());
        assertEquals(producer.getChainAddress(), row.getSigner());
        assertEquals(ParamsDigest.of(producerParams(tn)), row.getParamsDigest());
        assertEquals("0x0", row.getReceiptStatus());
        assertNull(row.getInflightKey());
    }

    @Test
    void 发请求那一刻提交记录已落库_HTTP5xx后记录仍在且为UNKNOWN() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String tn = traceNumber();
        List<ChainTx> seenAtArrival = new CopyOnWriteArrayList<>();
        fake.onArrival(r -> seenAtArrival.addAll(rows(tn)));
        fake.on("newAgroFood", r -> json(500, "{\"code\":500,\"errorMessage\":null}"));

        JSONObject body = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn)), login(producer), 202);
        assertEquals(202, body.getInt("code"));

        // WeBASE 收到请求时，库里已经有这条提交意图，并已推进到 SUBMITTED
        assertEquals(1, seenAtArrival.size());
        assertEquals("SUBMITTED", seenAtArrival.get(0).getState());
        assertEquals(producer.getChainAddress(), seenAtArrival.get(0).getSigner());
        assertEquals("newAgroFood", seenAtArrival.get(0).getFuncName());

        List<ChainTx> after = rows(tn);
        assertEquals(1, after.size());
        assertEquals("UNKNOWN", after.get(0).getState());
        assertTrue(after.get(0).getErrorReason().contains("HTTP 500"), after.get(0).getErrorReason());
        assertEquals(after.get(0).getBizKey(), after.get(0).getInflightKey());
    }

    @Test
    void revert_重复分销映射409_记录FAILED含revert原因() throws Exception {
        UserAccount distributor = seedUser(UserRole.DISTRIBUTOR);
        String tn = traceNumber();
        fake.on("addTraceInfoByDistributor",
                r -> receiptRevert(r.user, fake.nextHash(), fake.nextBlock(), "Trace: distribution already recorded"));

        JSONObject body = perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, 10)), login(distributor), 409);
        assertEquals(409, body.getInt("code"));
        assertTrue(body.getStr("mes").contains("分销信息已录入"), body.getStr("mes"));
        JSONObject data = body.getJSONObject("data");
        assertEquals("FAILED", data.getStr("state"));
        assertNotNull(data.getStr("txHash"));
        assertTrue(data.getStr("errorReason").contains("Trace: distribution already recorded"));
        assertNull(chainTxMapper.selectById(data.getLong("id")).getInflightKey());
    }

    @Test
    void 读超时_返回202_记录UNKNOWN_不自动重发() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String tn = traceNumber();
        fake.on("newAgroFood", r -> delayed(2000, receiptSuccess(r.user, fake.nextHash(), fake.nextBlock())));

        JSONObject body = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn)), login(producer), 202);
        assertEquals("UNKNOWN", body.getJSONObject("data").getStr("state"));
        assertTrue(body.getStr("mes").contains("查证"), body.getStr("mes"));
        Thread.sleep(1500);
        assertEquals(1, fake.requestsFor("newAgroFood").size());
        assertEquals("UNKNOWN", rows(tn).get(0).getState());
    }

    @Test
    void 响应体中断_记录UNKNOWN() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String token = login(producer);
        String tn = traceNumber();
        // JDK HttpServer 无法在响应中途断开，这一笔临时改走原始 socket 替身
        String original = httpUtil.URL;
        try (RawHttpStub stub = RawHttpStub.truncatedJson("{\"transactionHash\":\"0x", 800)) {
            httpUtil.URL = stub.baseUrl();
            perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON).content(producerBody(tn)), token, 202);
            assertEquals(1, stub.requestCount());
        } finally {
            httpUtil.URL = original;
        }
        ChainTx row = rows(tn).get(0);
        assertEquals("UNKNOWN", row.getState());
        assertTrue(row.getErrorReason().contains("中断"), row.getErrorReason());
    }

    @Test
    void 坏JSON_记录UNKNOWN() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String tn = traceNumber();
        fake.on("newAgroFood", r -> json(200, "{\"transactionHash\":"));

        perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON).content(producerBody(tn)), login(producer), 202);
        assertEquals("UNKNOWN", rows(tn).get(0).getState());
    }

    @Test
    void WeBASE回执超时_没有哈希_记录UNKNOWN而不是FAILED() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String tn = traceNumber();
        fake.on("newAgroFood", r -> receiptTimeout());

        perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON).content(producerBody(tn)), login(producer), 202);
        ChainTx row = rows(tn).get(0);
        assertEquals("UNKNOWN", row.getState());
        assertNull(row.getTxHash());
        assertEquals("50001", row.getReceiptStatus());
    }

    @Test
    void 签名私钥不在WeBASE_交易未发出_FAILED且可以直接重新提交() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String token = login(producer);
        String tn = traceNumber();
        fake.on("newAgroFood", r -> frontError(201015, "user's privateKey is null"));

        JSONObject body = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn)), token, 502);
        assertEquals("FAILED", body.getJSONObject("data").getStr("state"));

        fake.reset();
        perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON).content(producerBody(tn)), token, 200);
        List<ChainTx> all = rows(tn);
        assertEquals(2, all.size());
        assertEquals("FAILED", all.get(0).getState());
        assertEquals("CONFIRMED", all.get(1).getState());
    }

    @Test
    void UNKNOWN后同一阶段重复提交被拒409_不再请求WeBASE() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String token = login(producer);
        String tn = traceNumber();
        fake.on("newAgroFood", r -> receiptTimeout());
        Long firstId = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn)), token, 202).getJSONObject("data").getLong("id");

        fake.reset();
        JSONObject body = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn)), token, 409);
        assertTrue(body.getStr("mes").contains("查证"), body.getStr("mes"));
        assertEquals(firstId, body.getJSONObject("data").getLong("id"));
        assertTrue(fake.requests().isEmpty());
        assertEquals(1, rows(tn).size());
    }

    // ---------- 查证 ----------

    @Test
    void 查证_有哈希_按回执确认CONFIRMED() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String token = login(producer);
        String tn = traceNumber();
        String hash = fake.nextHash();
        // 回执带哈希但缺 status：无法判定，记为 UNKNOWN 并保留哈希
        fake.on("newAgroFood", r -> json(200, withoutStatus(receipt(r.user, hash, 5, "0x0", "None", "0x", "Success", true))));
        Long id = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn)), token, 202).getJSONObject("data").getLong("id");
        assertEquals(hash, chainTxMapper.selectById(id).getTxHash());

        fake.onReceipt(h -> receiptSuccess(producer.getChainAddress(), h, 42));
        JSONObject data = perform(post("/chain-tx/" + id + "/verify"), token, 200).getJSONObject("data");
        assertEquals("RECEIPT_CONFIRMED", data.getStr("conclusion"));
        ChainTx row = chainTxMapper.selectById(id);
        assertEquals("CONFIRMED", row.getState());
        assertEquals(42L, row.getBlockNumber());
        assertNull(row.getInflightKey());
        assertEquals(List.of(hash), fake.receiptQueries());
        assertTrue(fake.requestsFor("getStageActors").isEmpty());
    }

    @Test
    void 查证_有哈希但查不到回执_退回读链_本人以相同数据写入则CONFIRMED() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String token = login(producer);
        String tn = traceNumber();
        String hash = fake.nextHash();
        fake.on("newAgroFood", r -> json(200, withoutStatus(receipt(r.user, hash, 5, "0x0", "None", "0x", "Success", true))));
        Long id = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn)), token, 202).getJSONObject("data").getLong("id");

        // 默认 onReceipt：HTTP 500（v1.5.5 查不到回执的实测行为）
        fake.on("getStageActors", r -> callResult(producer.getChainAddress(), ZERO, ZERO));
        fake.on("getAgroFoodInfo", r -> callResult(producerChainData(1790529909955L)));
        JSONObject data = perform(post("/chain-tx/" + id + "/verify"), token, 200).getJSONObject("data");
        assertEquals("STATE_CONFIRMED", data.getStr("conclusion"));
        assertEquals("CONFIRMED", chainTxMapper.selectById(id).getState());
        assertEquals(List.of(hash), fake.receiptQueries());
    }

    @Test
    void 查证_没有哈希_阶段已由本人以相同数据写入_CONFIRMED() throws Exception {
        UserAccount distributor = seedUser(UserRole.DISTRIBUTOR);
        String token = login(distributor);
        String tn = traceNumber();
        Long id = unknownDistribution(tn, token, 10);

        fake.on("getStageActors", r -> callResult(OTHER, distributor.getChainAddress(), ZERO));
        fake.on("getAgroFoodInfoByDistributor", r -> callResult(distributorChainData(10, 1790529911979L)));
        JSONObject data = perform(post("/chain-tx/" + id + "/verify"), token, 200).getJSONObject("data");
        assertEquals("STATE_CONFIRMED", data.getStr("conclusion"));
        ChainTx row = chainTxMapper.selectById(id);
        assertEquals("CONFIRMED", row.getState());
        assertEquals("STATE_CONFIRMED", row.getVerifyResult());
        assertNull(row.getInflightKey());
        assertTrue(fake.receiptQueries().isEmpty(), "没有哈希时不查回执");
    }

    @Test
    void 查证_没有哈希_阶段已被他人写入_FAILED冲突() throws Exception {
        UserAccount distributor = seedUser(UserRole.DISTRIBUTOR);
        String token = login(distributor);
        String tn = traceNumber();
        Long id = unknownDistribution(tn, token, 10);

        fake.on("getStageActors", r -> callResult(OTHER, OTHER, ZERO));
        JSONObject data = perform(post("/chain-tx/" + id + "/verify"), token, 200).getJSONObject("data");
        assertEquals("CONFLICT", data.getStr("conclusion"));
        ChainTx row = chainTxMapper.selectById(id);
        assertEquals("FAILED", row.getState());
        assertTrue(row.getErrorReason().contains(OTHER), row.getErrorReason());
        assertNull(row.getInflightKey());
    }

    @Test
    void 查证_没有哈希_同一账户以不同数据写入_FAILED冲突() throws Exception {
        UserAccount distributor = seedUser(UserRole.DISTRIBUTOR);
        String token = login(distributor);
        String tn = traceNumber();
        Long id = unknownDistribution(tn, token, 10);

        fake.on("getStageActors", r -> callResult(OTHER, distributor.getChainAddress(), ZERO));
        fake.on("getAgroFoodInfoByDistributor", r -> callResult(distributorChainData(11, 1790529911979L)));
        JSONObject data = perform(post("/chain-tx/" + id + "/verify"), token, 200).getJSONObject("data");
        assertEquals("CONFLICT", data.getStr("conclusion"));
        assertEquals("FAILED", chainTxMapper.selectById(id).getState());
    }

    @Test
    void 查证_阶段未写入_释放业务键_允许显式重新提交_原记录迟到上链再查证判为冲突() throws Exception {
        UserAccount retailer = seedUser(UserRole.RETAILER);
        String token = login(retailer);
        String tn = traceNumber();
        fake.on("addTraceInfoByRetailer", r -> receiptTimeout());
        Long oldId = perform(post("/retailer/add").contentType(MediaType.APPLICATION_JSON)
                .content(retailerBody(tn)), token, 202).getJSONObject("data").getLong("id");

        // 查证之前重复提交：拒绝
        perform(post("/retailer/add").contentType(MediaType.APPLICATION_JSON).content(retailerBody(tn)), token, 409);

        fake.on("getStageActors", r -> callResult(OTHER, OTHER, ZERO));
        JSONObject data = perform(post("/chain-tx/" + oldId + "/verify"), token, 200).getJSONObject("data");
        assertEquals("NOT_WRITTEN", data.getStr("conclusion"));
        ChainTx old = chainTxMapper.selectById(oldId);
        assertEquals("UNKNOWN", old.getState(), "原交易之后是否上链仍未知，不能标成失败");
        assertEquals("NOT_WRITTEN", old.getVerifyResult());
        assertNull(old.getInflightKey());

        // 用户显式重新提交：放行并确认成功
        fake.on("addTraceInfoByRetailer", r -> receiptSuccess(r.user, fake.nextHash(), fake.nextBlock()));
        Long newId = perform(post("/retailer/add").contentType(MediaType.APPLICATION_JSON)
                .content(retailerBody(tn)), token, 200).getJSONObject("data").getLong("id");
        assertNotEquals(oldId, newId);

        // 链上零售阶段现在由本账户以相同数据写入（其实是新交易写的）：原记录再查证不能冒领
        fake.on("getStageActors", r -> callResult(OTHER, OTHER, retailer.getChainAddress()));
        fake.on("getAgroFoodInfoByRetailer", r -> callResult(retailerChainData(1790529912995L)));
        JSONObject again = perform(post("/chain-tx/" + oldId + "/verify"), token, 200).getJSONObject("data");
        assertEquals("CONFLICT", again.getStr("conclusion"));
        assertTrue(chainTxMapper.selectById(oldId).getErrorReason().contains("#" + newId));
        assertEquals("CONFIRMED", chainTxMapper.selectById(newId).getState());
    }

    @Test
    void 查证_溯源号在链上不存在_生产阶段未写入() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        String token = login(producer);
        String tn = traceNumber();
        fake.on("newAgroFood", r -> receiptTimeout());
        Long id = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn)), token, 202).getJSONObject("data").getLong("id");

        fake.on("getStageActors", r -> callRevert("Trace: traceNumber does not exist"));
        JSONObject data = perform(post("/chain-tx/" + id + "/verify"), token, 200).getJSONObject("data");
        assertEquals("NOT_WRITTEN", data.getStr("conclusion"));
    }

    @Test
    void 查证与查看_只能是签名者本人或管理员() throws Exception {
        UserAccount producer = seedUser(UserRole.PRODUCER);
        UserAccount stranger = seedUser(UserRole.PRODUCER);
        String tn = traceNumber();
        fake.on("newAgroFood", r -> receiptTimeout());
        Long id = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn)), login(producer), 202).getJSONObject("data").getLong("id");

        String strangerToken = login(stranger);
        perform(get("/chain-tx/" + id), strangerToken, 403);
        perform(post("/chain-tx/" + id + "/verify"), strangerToken, 403);
        assertTrue(fake.requestsFor("getStageActors").isEmpty());

        JSONObject asAdmin = perform(get("/chain-tx/" + id), loginAdmin(), 200).getJSONObject("data");
        assertEquals("UNKNOWN", asAdmin.getStr("state"));
    }

    // ---------- 工具方法 ----------

    private Long unknownDistribution(String tn, String token, long price) throws Exception {
        fake.on("addTraceInfoByDistributor", r -> receiptTimeout());
        Long id = perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, price)), token, 202).getJSONObject("data").getLong("id");
        assertEquals("UNKNOWN", chainTxMapper.selectById(id).getState());
        return id;
    }

    private List<ChainTx> rows(String tn) {
        return chainTxMapper.selectList(new LambdaQueryWrapper<ChainTx>()
                .eq(ChainTx::getTraceNumber, tn).orderByAsc(ChainTx::getId));
    }

    private static String withoutStatus(JSONObject receipt) {
        receipt.remove("status");
        return receipt.toString();
    }

    private static String traceNumber() {
        return "LC-" + SEQ.incrementAndGet();
    }

    private static List<Object> producerParams(String tn) {
        return Arrays.asList(tn, "农场A", "苹果", "烟台", "红富士", "B001", "QmCid", "2026-01-01");
    }

    // getAgroFoodInfo 的读回值：写入参数去掉溯源号 + 时间戳
    private static Object[] producerChainData(long timestamp) {
        return new Object[]{"农场A", "苹果", "烟台", "红富士", "B001", "QmCid", "2026-01-01", timestamp};
    }

    private static Object[] distributorChainData(long price, long timestamp) {
        return new Object[]{"仓配", "冷藏", "货车", "D01", "济南", price, 100, "QmR", timestamp};
    }

    private static Object[] retailerChainData(long timestamp) {
        return new Object[]{"门店", 20, 5, 7, "INV-1", "2026-02-01", timestamp};
    }

    private static String producerBody(String tn) {
        return "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"农场A\",\"productName\":\"苹果\","
                + "\"productionLocation\":\"烟台\",\"variety\":\"红富士\",\"productionBatch\":\"B001\","
                + "\"productionCert\":\"QmCid\",\"productTime\":\"2026-01-01\"}";
    }

    private static String distributorBody(String tn, long price) {
        return "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"仓配\",\"storageCondition\":\"冷藏\","
                + "\"transportMethod\":\"货车\",\"distributeBatch\":\"D01\",\"storageLocation\":\"济南\","
                + "\"distributePrice\":" + price + ",\"distributeQuantity\":100,\"inspectionReport\":\"QmR\"}";
    }

    private static String retailerBody(String tn) {
        return "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"门店\",\"salePrice\":20,\"saleQuantity\":5,"
                + "\"shelfLife\":7,\"invoiceNo\":\"INV-1\",\"saleTime\":\"2026-02-01\"}";
    }

    private UserAccount seedUser(UserRole role) {
        int n = SEQ.incrementAndGet();
        UserAccount user = new UserAccount();
        user.setUsername("lc_" + role.name().toLowerCase() + "_" + n);
        user.setPasswordHash(authService.hashPassword(USER_PASSWORD));
        user.setRole(role.name());
        user.setChainAddress(String.format("0x%040x", 0x5000 + n));
        user.setCompanyName("公司" + n);
        user.setEnabled(true);
        user.setCreatedAt(new Date());
        user.setUpdatedAt(new Date());
        userAccountMapper.insert(user);
        return user;
    }

    private String login(UserAccount user) throws Exception {
        return login(user.getUsername(), USER_PASSWORD);
    }

    private String loginAdmin() throws Exception {
        return login("admin", ADMIN_PASSWORD);
    }

    private String login(String username, String password) throws Exception {
        String content = JSONUtil.createObj().set("username", username).set("password", password).toString();
        MvcResult result = mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isOk()).andReturn();
        return body(result).getJSONObject("data").getStr("token");
    }

    private JSONObject perform(MockHttpServletRequestBuilder builder, String token, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(builder.header("Authorization", "Bearer " + token))
                .andExpect(status().is(expectedStatus)).andReturn();
        return body(result);
    }

    private static JSONObject body(MvcResult result) throws Exception {
        return JSONUtil.parseObj(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
