package com.qhx.back.chain;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qhx.back.mapper.TraceBatchMapper;
import com.qhx.back.model.TraceBatch;
import com.qhx.back.model.TraceReadModel;
import com.qhx.back.service.ReadModelService;
import com.qhx.back.support.FakeKubo;
import com.qhx.back.task.IotDataSimulatorTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 隔离真实链验收：后端按批次合约绑定分流，新 v3 批次与旧 v2 批次均可走完整业务阶段。 */
@EnabledIfEnvironmentVariable(named = "E2E_V3_FILE", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:real_chain_v3;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/auth-schema.sql,classpath:db/chain-tx-schema.sql,classpath:db/business-schema.sql",
        "auth.bootstrap-admin.username=admin",
        "auth.bootstrap-admin.password=" + RealChainContractV3E2ETest.PASSWORD
})
@AutoConfigureMockMvc
class RealChainContractV3E2ETest {
    static final String PASSWORD = "real-chain-v3-password";
    private static FakeKubo kubo;
    @MockBean IotDataSimulatorTask iotDataSimulatorTask;
    @Autowired MockMvc mvc;
    @Autowired TraceBatchMapper batchMapper;
    @Autowired ReadModelService readModelService;

    private static JSONObject evidence() {
        return JSONUtil.parseObj(FileUtil.readString(new File(System.getenv("E2E_V3_FILE")), StandardCharsets.UTF_8));
    }

    @DynamicPropertySource
    static void chain(DynamicPropertyRegistry registry) {
        JSONObject source = evidence();
        String owner = source.getJSONObject("accounts").getStr("owner");
        registry.add("webase-front.url", () -> source.getStr("front"));
        registry.add("webase-front.group-id", () -> 1);
        registry.add("contract.address", () -> source.getStr("v2Address"));
        registry.add("contract.v3.address", () -> source.getStr("v3Address"));
        registry.add("contract.owner", () -> owner);
        registry.add("auth.bootstrap-admin.address", () -> owner);
        try {
            kubo = new FakeKubo();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        registry.add("ipfs.api-url", kubo::apiUrl);
    }

    @org.junit.jupiter.api.AfterAll
    static void closeKubo() {
        if (kubo != null) kubo.close();
    }

    @Test
    void backendRoutesOldV2AndNewV3BatchesOnRealChain() throws Exception {
        JSONObject source = evidence();
        JSONObject accounts = source.getJSONObject("accounts");
        String run = String.valueOf(System.currentTimeMillis());
        String admin = login("admin");
        for (String[] actor : new String[][]{{"p", "PRODUCER", "producer"}, {"d", "DISTRIBUTOR", "distributor"},
                {"r", "RETAILER", "retailer"}, {"o", "DISTRIBUTOR", "otherDistributor"}}) {
            JSONObject created = request(post("/admin/users").contentType(MediaType.APPLICATION_JSON)
                    .content(userBody(actor[0] + run, actor[1], accounts.getStr(actor[2]))), admin, 200).getJSONObject("data");
            assertEquals("ALREADY_ON_CHAIN", created.getStr("roleState"));
        }
        String producer = login("p" + run);
        String distributor = login("d" + run);
        String retailer = login("r" + run);
        String outsider = login("o" + run);
        String v2Trace = "BV2-" + run;
        String v3Trace = "BV3-" + run;

        TraceBatch legacy = new TraceBatch();
        legacy.setTraceNumber(v2Trace);
        legacy.setProductName("旧批次苹果");
        legacy.setProducerId(accountId("p" + run));
        legacy.setDistributorId(accountId("d" + run));
        legacy.setContractVersion("V2");
        legacy.setContractAddress(source.getStr("v2Address"));
        legacy.setCreatedAt(new Date());
        legacy.setUpdatedAt(new Date());
        batchMapper.insert(legacy);
        log("旧批次预绑定 V2 " + v2Trace + " address=" + legacy.getContractAddress());

        checkTx("v2 production before v3", request(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(production(v2Trace, "d" + run, upload(producer, "v2-cert"))), producer, 200).getJSONObject("data"), source, source.getStr("v2Address"));
        checkBinding(v2Trace, "V2", source.getStr("v2Address"), 1);

        checkTx("v3 production", request(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(production(v3Trace, "d" + run, upload(producer, "v3-cert"))), producer, 200).getJSONObject("data"), source, source.getStr("v3Address"));
        checkBinding(v3Trace, "V3", source.getStr("v3Address"), 1);

        request(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distribution(v3Trace, "r" + run, upload(outsider, "bypass-report"))), outsider, 403);
        log("v3 非指定分销商经后端拒绝 403");
        checkTx("v3 distribution", request(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distribution(v3Trace, "r" + run, upload(distributor, "v3-report"))), distributor, 200).getJSONObject("data"), source, source.getStr("v3Address"));
        checkTx("v3 retail", request(post("/retailer/add").contentType(MediaType.APPLICATION_JSON)
                .content(retail(v3Trace)), retailer, 200).getJSONObject("data"), source, source.getStr("v3Address"));
        checkBinding(v3Trace, "V3", source.getStr("v3Address"), 3);
        publicStages(v3Trace);

        checkTx("v2 distribution after v3", request(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distribution(v2Trace, "r" + run, upload(distributor, "v2-report"))), distributor, 200).getJSONObject("data"), source, source.getStr("v2Address"));
        checkTx("v2 retail after v3", request(post("/retailer/add").contentType(MediaType.APPLICATION_JSON)
                .content(retail(v2Trace)), retailer, 200).getJSONObject("data"), source, source.getStr("v2Address"));
        checkBinding(v2Trace, "V2", source.getStr("v2Address"), 3);
        publicStages(v2Trace);
    }

    @Autowired com.qhx.back.mapper.UserAccountMapper userMapper;
    private Long accountId(String username) {
        com.qhx.back.model.UserAccount account = userMapper.selectOne(new LambdaQueryWrapper<com.qhx.back.model.UserAccount>()
                .eq(com.qhx.back.model.UserAccount::getUsername, username));
        assertNotNull(account);
        return account.getId();
    }

    private void checkBinding(String trace, String version, String address, int stage) {
        TraceBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<TraceBatch>().eq(TraceBatch::getTraceNumber, trace));
        assertNotNull(batch);
        assertEquals(version, batch.getContractVersion());
        assertEquals(address.toLowerCase(), batch.getContractAddress().toLowerCase());
        TraceReadModel snapshot = readModelService.refresh(trace);
        assertNotNull(snapshot);
        assertEquals(stage, snapshot.getStageReached());
        log("read-model " + trace + " version=" + version + " address=" + address + " stageReached=" + snapshot.getStageReached()
                + " actors=" + snapshot.getProducerAddress() + "," + snapshot.getDistributorAddress() + "," + snapshot.getRetailerAddress());
    }

    private void publicStages(String trace) throws Exception {
        MvcResult result = mvc.perform(get("/trace/detail/" + trace)).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JSONObject view = JSONUtil.parseObj(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).getJSONObject("data");
        assertEquals(3, view.getJSONArray("stages").size());
        for (int i = 0; i < 3; i++) assertNotNull(view.getJSONArray("stages").getJSONObject(i).getStr("txHash"));
        log("consumer " + trace + " production=" + view.getJSONObject("producer").getStr("productName") + " stages=3");
    }

    private static void checkTx(String label, JSONObject tx, JSONObject source, String expectedAddress) {
        assertEquals("CONFIRMED", tx.getStr("state"));
        String hash = tx.getStr("txHash");
        Long block = tx.getLong("blockNumber");
        assertNotNull(hash);
        assertTrue(block != null && block > 0);
        JSONObject receipt = JSONUtil.parseObj(HttpRequest.get(source.getStr("front") + "/1/web3/transactionReceipt/" + hash)
                .timeout(20000).execute().body());
        assertEquals(hash, receipt.getStr("transactionHash"));
        assertEquals("0x0", receipt.getStr("status"));
        assertEquals(expectedAddress.toLowerCase(), receipt.getStr("to").toLowerCase());
        String receiptBlock = receipt.getStr("blockNumber");
        assertEquals(block.longValue(), receiptBlock.startsWith("0x")
                ? Long.parseLong(receiptBlock.substring(2), 16) : Long.parseLong(receiptBlock));
        log(label + " ChainTx=" + tx.getLong("id") + " hash=" + hash + " block=" + block + " receiptStatus=" + receipt.getStr("status") + " receiptTo=" + receipt.getStr("to"));
    }

    private String upload(String token, String tag) throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        byte[] suffix = tag.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[png.length + suffix.length];
        System.arraycopy(png, 0, bytes, 0, png.length);
        System.arraycopy(suffix, 0, bytes, png.length, suffix.length);
        return request(multipart("/upload").file(new MockMultipartFile("file", tag + ".png", "image/png", bytes)), token, 200)
                .getJSONObject("data").getStr("cid");
    }

    private static String userBody(String username, String role, String address) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"" + role
                + "\",\"chainAddress\":\"" + address + "\",\"companyName\":\"" + role + "\"}";
    }
    private static String production(String trace, String distributor, String cid) {
        return "{\"traceNumber\":\"" + trace + "\",\"companyName\":\"农场\",\"productName\":\"苹果\",\"productionLocation\":\"烟台\","
                + "\"variety\":\"红富士\",\"productionBatch\":\"P1\",\"productionCert\":\"" + cid + "\","
                + "\"productTime\":\"2026-09-28\",\"distributorUsername\":\"" + distributor + "\"}";
    }
    private static String distribution(String trace, String retailer, String cid) {
        return "{\"traceNumber\":\"" + trace + "\",\"companyName\":\"仓配\",\"storageCondition\":\"冷藏\","
                + "\"transportMethod\":\"冷链车\",\"distributeBatch\":\"D1\",\"storageLocation\":\"济南\","
                + "\"distributePrice\":10,\"distributeQuantity\":100,\"inspectionReport\":\"" + cid + "\","
                + "\"retailerUsername\":\"" + retailer + "\"}";
    }
    private static String retail(String trace) {
        return "{\"traceNumber\":\"" + trace + "\",\"companyName\":\"门店\",\"salePrice\":20,\"saleQuantity\":5,"
                + "\"shelfLife\":7,\"invoiceNo\":\"INV-1\",\"saleTime\":\"2026-09-29\"}";
    }
    private String login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return JSONUtil.parseObj(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).getJSONObject("data").getStr("token");
    }
    private JSONObject request(MockHttpServletRequestBuilder builder, String token, int status) throws Exception {
        MvcResult result = mvc.perform(builder.header("Authorization", "Bearer " + token)).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(status, result.getResponse().getStatus(), body);
        return JSONUtil.parseObj(body);
    }
    private static void log(String message) { System.out.println("[real-chain-v3] " + message); }
}
