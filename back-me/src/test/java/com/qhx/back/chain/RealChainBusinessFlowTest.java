package com.qhx.back.chain;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.qhx.back.service.IPFSService;
import com.qhx.back.task.IotDataSimulatorTask;
import io.ipfs.api.IPFS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 完整后端（Spring 上下文 + H2 + 真实本地隔离链）跑业务闭环：
 * 管理员建号（链上已有角色则跳过交易 / 没有则真实发 addDistributor）→ 生产并指定分销商 → 非指定分销商被 403
 * → 指定分销商写分销并指定零售商 → 零售校验失败 400 → 零售上链 → 消费者公开视图 → 读回公开文件 → 追加更正。
 * IPFS 用内存替身（本地隔离链环境里没有 IPFS 守护进程）；文件 CID 仍按链上该阶段的字段读取。
 * 只有设置了 E2E_SMOKE_FILE 才运行；CI 的离线测试不设置，自动跳过。
 */
@EnabledIfEnvironmentVariable(named = "E2E_SMOKE_FILE", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:real_chain_flow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/auth-schema.sql,classpath:db/chain-tx-schema.sql,classpath:db/business-schema.sql",
        "auth.bootstrap-admin.username=admin",
        "auth.bootstrap-admin.password=" + RealChainBusinessFlowTest.PASSWORD,
})
@AutoConfigureMockMvc
class RealChainBusinessFlowTest {

    static final String PASSWORD = "real-chain-test-password";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 'r', 'e', 'a', 'l'};
    private static final Map<String, byte[]> FILES = new ConcurrentHashMap<>();

    @MockBean
    IPFS ipfs;
    @MockBean
    IPFSService ipfsService;
    @MockBean
    IotDataSimulatorTask iotDataSimulatorTask;
    @Autowired
    MockMvc mvc;

    private static JSONObject smoke() {
        return JSONUtil.parseObj(FileUtil.readString(new File(System.getenv("E2E_SMOKE_FILE")), StandardCharsets.UTF_8));
    }

    @DynamicPropertySource
    static void chain(DynamicPropertyRegistry registry) {
        JSONObject smoke = smoke();
        String owner = smoke.getJSONObject("accounts").getStr("owner");
        registry.add("webase-front.url", () -> smoke.getJSONObject("meta").getStr("frontUrl"));
        registry.add("webase-front.group-id", () -> smoke.getJSONObject("meta").getInt("groupId"));
        registry.add("contract.address", () -> smoke.getStr("contractAddress"));
        registry.add("contract.owner", () -> owner);
        registry.add("auth.bootstrap-admin.address", () -> owner);
    }

    @Test
    void 真实链_指定交接_三阶段_消费者查询_读回公开文件() throws Exception {
        JSONObject acc = smoke().getJSONObject("accounts");
        String run = String.valueOf(System.currentTimeMillis() % 100000000);
        when(ipfsService.saveFileBase64(anyString())).thenAnswer(inv -> {
            String cid = "QmReal" + run + FILES.size();
            FILES.put(cid, Base64.getDecoder().decode((String) inv.getArgument(0)));
            return cid;
        });
        when(ipfsService.loadFile(anyString())).thenAnswer(inv -> FILES.get((String) inv.getArgument(0)));

        String admin = login("admin");
        // 建号：smoke 已给 producer/distributor/retailer 授过角色 → 跳过授权交易
        for (String[] u : new String[][]{{"p" + run, "PRODUCER", "producer"}, {"d" + run, "DISTRIBUTOR", "distributor"}, {"r" + run, "RETAILER", "retailer"}}) {
            JSONObject created = perform(post("/admin/users").contentType(MediaType.APPLICATION_JSON)
                    .content(userBody(u[0], u[1], acc.getStr(u[2]))), admin, 200).getJSONObject("data");
            log("建号 " + u[0] + " " + u[1] + " roleState=" + created.getStr("roleState"));
            assertEquals("ALREADY_ON_CHAIN", created.getStr("roleState"));
        }
        // outsider 链上没有角色 → 真实发 addDistributor，回执确认后启用
        JSONObject outsider = perform(post("/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(userBody("o" + run, "DISTRIBUTOR", acc.getStr("outsider"))), admin, 200).getJSONObject("data");
        log("建号 o" + run + " DISTRIBUTOR roleState=" + outsider.getStr("roleState") + " txId=" + outsider.getStr("roleTxId"));
        assertEquals("GRANTED_BY_TX", outsider.getStr("roleState"));

        String p = login("p" + run);
        String d = login("d" + run);
        String r = login("r" + run);
        String o = login("o" + run);
        String tn = "RC" + run;

        String cert = perform(post("/uploadBase64").contentType(MediaType.APPLICATION_JSON)
                .content("{\"file\":\"" + Base64.getEncoder().encodeToString(PNG) + "\"}"), p, 200).getJSONObject("data").getStr("hash");
        JSONObject prod = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON).content(
                "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"烟台果园\",\"productName\":\"苹果\",\"productionLocation\":\"山东烟台\","
                        + "\"variety\":\"红富士\",\"productionBatch\":\"B-" + run + "\",\"productionCert\":\"" + cert + "\","
                        + "\"productTime\":\"2026-09-20\",\"distributorUsername\":\"d" + run + "\"}"), p, 200).getJSONObject("data");
        logTx("生产", prod);

        // outsider 在链上已是分销商，合约会接受它的写入；是后端的交接规则拒绝了它
        JSONObject denied = perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distBody(tn, "r" + run, cert)), o, 403);
        log("非指定分销商写分销 → 403：" + denied.getStr("mes"));

        JSONObject dist = perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distBody(tn, "r" + run, cert)), d, 200).getJSONObject("data");
        logTx("分销", dist);

        JSONObject bad = perform(post("/retailer/add").contentType(MediaType.APPLICATION_JSON)
                .content(retailBody(tn, 501, "2026-09-19")), r, 400);
        log("零售数量超过分销数量、日期早于生产 → 400：" + bad.getJSONObject("data").getJSONArray("errors"));

        JSONObject retail = perform(post("/retailer/add").contentType(MediaType.APPLICATION_JSON)
                .content(retailBody(tn, 120, "2026-09-27")), r, 200).getJSONObject("data");
        logTx("零售", retail);

        JSONObject corr = perform(post("/batches/" + tn + "/corrections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"RETAIL\",\"reason\":\"保质期复核\",\"content\":{\"shelfLife\":\"12\"}}"), r, 200).getJSONObject("data");
        log("零售写入者追加更正 #" + corr.getLong("id"));
        perform(post("/batches/" + tn + "/corrections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"RETAIL\",\"reason\":\"x\",\"content\":{\"shelfLife\":\"1\"}}"), d, 403);
        log("非写入者更正 → 403");

        // 消费者（免登录）
        MvcResult pubRes = mvc.perform(get("/trace/detail/" + tn)).andReturn();
        assertEquals(200, pubRes.getResponse().getStatus());
        JSONObject pub = JSONUtil.parseObj(pubRes.getResponse().getContentAsString(StandardCharsets.UTF_8)).getJSONObject("data");
        String pubText = pub.toString();
        for (String hidden : List.of("distributePrice", "distributeQuantity", "storageLocation", "salePrice", "saleQuantity", "invoiceNo", cert, acc.getStr("producer"))) {
            assertFalse(pubText.contains("\"" + hidden + "\"") || pubText.contains(hidden), "公开视图不应包含 " + hidden);
        }
        assertEquals("苹果", pub.getJSONObject("producer").getStr("productName"));
        for (int i = 0; i < 3; i++) {
            assertNotNull(pub.getJSONArray("stages").getJSONObject(i).getStr("txHash"));
        }
        log("消费者公开视图字段：producer=" + pub.getJSONObject("producer").keySet() + " distributor=" + pub.getJSONObject("distributor").keySet()
                + " retailer=" + pub.getJSONObject("retailer").keySet());
        log("消费者视图零售更正：" + pub.getJSONArray("stages").getJSONObject(2).getJSONArray("corrections"));

        MvcResult file = mvc.perform(get("/trace/" + tn + "/file/production")).andReturn();
        assertEquals(200, file.getResponse().getStatus());
        assertArrayEquals(PNG, file.getResponse().getContentAsByteArray());
        log("读回公开文件 production：" + file.getResponse().getContentType() + " " + file.getResponse().getContentAsByteArray().length + " 字节（CID 由链上 getAgroFoodInfo 读出）");
        assertEquals(401, mvc.perform(get("/fileBase64/" + cert)).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(get("/trace/" + tn + "/file/retail")).andReturn().getResponse().getStatus());
        log("按 CID 直接读取（未登录）→ 401；零售阶段无文件 → 400");
    }

    private static String userBody(String username, String role, String address) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"" + role
                + "\",\"chainAddress\":\"" + address + "\",\"companyName\":\"" + role + "-" + username + "\"}";
    }

    private static String distBody(String tn, String retailer, String cid) {
        return "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"济南冷链\",\"storageCondition\":\"0-4℃\",\"transportMethod\":\"冷链车\","
                + "\"distributeBatch\":\"D1\",\"storageLocation\":\"济南仓\",\"distributePrice\":12,\"distributeQuantity\":500,"
                + "\"inspectionReport\":\"" + cid + "\",\"retailerUsername\":\"" + retailer + "\"}";
    }

    private static String retailBody(String tn, int quantity, String date) {
        return "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"历下超市\",\"salePrice\":25,\"saleQuantity\":" + quantity
                + ",\"shelfLife\":15,\"invoiceNo\":\"INV-1\",\"saleTime\":\"" + date + "\"}";
    }

    private String login(String username) throws Exception {
        MvcResult res = mvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        return JSONUtil.parseObj(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).getJSONObject("data").getStr("token");
    }

    private JSONObject perform(MockHttpServletRequestBuilder builder, String token, int status) throws Exception {
        MvcResult res = mvc.perform(builder.header("Authorization", "Bearer " + token)).andReturn();
        String body = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(status, res.getResponse().getStatus(), body);
        return JSONUtil.parseObj(body);
    }

    private static void logTx(String stage, JSONObject tx) {
        assertEquals("CONFIRMED", tx.getStr("state"));
        assertTrue(tx.getLong("blockNumber") > 0);
        log(stage + "上链 CONFIRMED hash=" + tx.getStr("txHash") + " block=" + tx.getLong("blockNumber") + " signer=" + tx.getStr("signer"));
    }

    private static void log(String s) {
        System.out.println("[real-chain-flow] " + s);
    }
}
