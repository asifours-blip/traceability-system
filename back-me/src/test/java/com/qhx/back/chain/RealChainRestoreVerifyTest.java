package com.qhx.back.chain;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.qhx.back.task.IotDataSimulatorTask;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 备份/恢复演练的校验步骤，不单独跑数据准备：数据由 RealChainBusinessFlowTest
 * 在同一条链上跑过一遍（产出批次 RC&lt;E2E_RESTORE_VERIFY_RUN&gt;），备份/恢复脚本
 * 把 MySQL 与 kubo 换成全新实例后，这里只读——证明「批次、链上溯源号、文件 CID 三者对得上，
 * 消费者页能正常读回」，不重新发任何链上交易。
 * <p>
 * 需要的环境变量：
 * E2E_SMOKE_FILE       同一条链的 smoke 记录（合约地址不变，恢复不涉及链本身）
 * E2E_RESTORE_VERIFY_RUN  RealChainBusinessFlowTest 那次运行用的 run 号（日志里 "RC&lt;run&gt;"）
 * E2E_MYSQL_URL        恢复出来的全新 MySQL（如 jdbc:mysql://127.0.0.1:13307/trace_it）
 * E2E_IPFS_API_URL     恢复出来的全新 kubo（如 http://127.0.0.1:5202）
 */
@EnabledIfEnvironmentVariable(named = "E2E_RESTORE_VERIFY_RUN", matches = ".+")
@SpringBootTest(properties = {
        "auth.bootstrap-admin.username=admin",
        "auth.bootstrap-admin.password=" + RealChainRestoreVerifyTest.PASSWORD,
})
@AutoConfigureMockMvc
class RealChainRestoreVerifyTest {

    static final String PASSWORD = "real-chain-test-password";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 'r', 'e', 'a', 'l'};

    @MockBean
    IotDataSimulatorTask iotDataSimulatorTask;
    @Autowired
    MockMvc mvc;

    private static JSONObject smoke() {
        return JSONUtil.parseObj(FileUtil.readString(new File(System.getenv("E2E_SMOKE_FILE")), StandardCharsets.UTF_8));
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        JSONObject smoke = smoke();
        String owner = smoke.getJSONObject("accounts").getStr("owner");
        registry.add("webase-front.url", () -> smoke.getJSONObject("meta").getStr("frontUrl"));
        registry.add("webase-front.group-id", () -> smoke.getJSONObject("meta").getInt("groupId"));
        registry.add("contract.address", () -> smoke.getStr("contractAddress"));
        registry.add("contract.owner", () -> owner);
        registry.add("auth.bootstrap-admin.address", () -> owner);

        String kuboUrl = System.getenv("E2E_IPFS_API_URL");
        if (kuboUrl == null || kuboUrl.isEmpty()) {
            throw new IllegalStateException("恢复校验需要 E2E_IPFS_API_URL 指向恢复出来的 kubo");
        }
        registry.add("ipfs.api-url", () -> kuboUrl);

        String mysqlUrl = System.getenv("E2E_MYSQL_URL");
        if (mysqlUrl == null || mysqlUrl.isEmpty()) {
            throw new IllegalStateException("恢复校验需要 E2E_MYSQL_URL 指向恢复出来的 MySQL");
        }
        registry.add("spring.datasource.url", () -> mysqlUrl);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("E2E_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("E2E_MYSQL_PASSWORD", ""));
        // 恢复出来的库已经有数据（不是空库），这里保持 schema init 幂等即可，不清空重灌
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations",
                () -> "classpath:db/auth-schema.sql,classpath:db/chain-tx-schema.sql,classpath:db/business-schema.sql");
    }

    @Test
    void 恢复后批次_溯源号_文件CID对得上_消费者页能读回() throws Exception {
        String run = System.getenv("E2E_RESTORE_VERIFY_RUN");
        String tn = "RC" + run;
        byte[] expectedCert = png("cert-" + run);
        log("校验批次 " + tn + "（恢复出来的 MySQL=" + System.getenv("E2E_MYSQL_URL") + " kubo=" + System.getenv("E2E_IPFS_API_URL") + "）");

        // 管理员在恢复库里已存在（AdminBootstrap 只在没有 ADMIN 时才建号），直接登录
        String admin = login("admin");

        // 读模型重建：全部从链上重新核对一遍，恢复出来的 MySQL 应该已经和链上一致（幂等，created/updated 都是 0）
        JSONObject rebuild = perform(post("/admin/read-model/rebuild"), admin, 200).getJSONObject("data");
        log("重建读模型（恢复库）：" + rebuild);
        assertEquals(0, rebuild.getJSONObject("rows").getInt("created"), "恢复出来的读模型不应该缺行：" + rebuild);
        assertEquals(0, rebuild.getJSONObject("rows").getInt("updated"), "恢复出来的读模型不应该跟链上对不上：" + rebuild);

        // 消费者公开视图：批次、溯源号能查到，字段与生产时一致
        MvcResult pubRes = mvc.perform(get("/trace/detail/" + tn)).andReturn();
        assertEquals(200, pubRes.getResponse().getStatus(), pubRes.getResponse().getContentAsString());
        JSONObject pub = JSONUtil.parseObj(pubRes.getResponse().getContentAsString(StandardCharsets.UTF_8)).getJSONObject("data");
        assertEquals("苹果", pub.getJSONObject("producer").getStr("productName"));
        log("消费者公开视图（恢复库）：producer=" + pub.getJSONObject("producer"));

        // 文件 CID：恢复出来的 kubo 里还能读到同一个 CID 的同一份内容，字节级一致
        MvcResult file = mvc.perform(get("/trace/" + tn + "/file/production")).andReturn();
        assertEquals(200, file.getResponse().getStatus());
        assertArrayEquals(expectedCert, file.getResponse().getContentAsByteArray(), "恢复出来的 kubo 里文件内容必须和备份前字节一致");
        log("文件读回（恢复库 + 恢复 kubo）：" + file.getResponse().getContentAsByteArray().length + " 字节，与备份前一致");
    }

    private static byte[] png(String tag) {
        byte[] body = tag.getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[PNG.length + body.length];
        System.arraycopy(PNG, 0, b, 0, PNG.length);
        System.arraycopy(body, 0, b, PNG.length, body.length);
        return b;
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

    private static void log(String s) {
        System.out.println("[restore-verify] " + s);
    }
}
