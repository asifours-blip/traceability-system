package com.qhx.back.chain;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.qhx.back.file.KuboClient;
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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 真实环境：本地隔离链（FISCO BCOS 2.7.2 + WeBASE-Front v1.5.5）+ 真实 kubo 0.29.0（离线、独立 repo）+ 完整 Spring 后端（H2）。
 * 上传 → 上链 → 绑定 → 消费者读回 → 重启 IPFS 后仍能读回 → pin rm + repo gc 后返回明确的缺失错误 → 读模型重建（含真实链上的旧批次）。
 * 需要 E2E_SMOKE_FILE（链与账户）、E2E_IPFS_API_URL（kubo RPC）、E2E_IPFS_RESTART（重启 kubo 的命令）；CI 不设置，自动跳过。
 * 由 scripts/local-chain/files-e2e.sh 驱动，输出以 [files-e2e] 开头的行写入 docs/artifacts/。
 */
@EnabledIfEnvironmentVariable(named = "E2E_IPFS_API_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "E2E_SMOKE_FILE", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:real_chain_files;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/auth-schema.sql,classpath:db/chain-tx-schema.sql,classpath:db/business-schema.sql",
        "auth.bootstrap-admin.username=admin",
        "auth.bootstrap-admin.password=" + RealChainFilesE2ETest.PASSWORD,
        "file.orphan.cleanup-enabled=false",
})
@AutoConfigureMockMvc
class RealChainFilesE2ETest {

    static final String PASSWORD = "real-chain-files-password";

    @MockBean
    IotDataSimulatorTask iotDataSimulatorTask;
    @Autowired
    MockMvc mvc;

    private static JSONObject smoke() {
        return JSONUtil.parseObj(FileUtil.readString(new File(System.getenv("E2E_SMOKE_FILE")), StandardCharsets.UTF_8));
    }

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) {
        JSONObject smoke = smoke();
        String owner = smoke.getJSONObject("accounts").getStr("owner");
        registry.add("webase-front.url", () -> smoke.getJSONObject("meta").getStr("frontUrl"));
        registry.add("webase-front.group-id", () -> smoke.getJSONObject("meta").getInt("groupId"));
        registry.add("contract.address", () -> smoke.getStr("contractAddress"));
        registry.add("contract.owner", () -> owner);
        registry.add("auth.bootstrap-admin.address", () -> owner);
        registry.add("ipfs.api-url", () -> System.getenv("E2E_IPFS_API_URL"));
    }

    @Test
    void 真实链与真实kubo_上传上链绑定_读回_重启后读回_GC后明确缺失_读模型重建() throws Exception {
        JSONObject acc = smoke().getJSONObject("accounts");
        String run = String.valueOf(System.currentTimeMillis() % 100000000);
        KuboClient kubo = new KuboClient(System.getenv("E2E_IPFS_API_URL"), 5000, 60000);
        log("kubo " + kubo.version() + " @ " + System.getenv("E2E_IPFS_API_URL") + "；合约 " + smoke().getStr("contractAddress"));

        String admin = login("admin");
        for (String[] u : new String[][]{{"fp" + run, "PRODUCER", "producer"}, {"fd" + run, "DISTRIBUTOR", "distributor"}, {"fr" + run, "RETAILER", "retailer"}}) {
            JSONObject created = perform(post("/admin/users").contentType(MediaType.APPLICATION_JSON)
                    .content(userBody(u[0], u[1], acc.getStr(u[2]))), admin, 200).getJSONObject("data");
            assertEquals("ALREADY_ON_CHAIN", created.getStr("roleState"));
        }
        String p = login("fp" + run);
        String d = login("fd" + run);

        // 1. 上传：真实 kubo，服务端算 SHA-256，读回核对
        byte[] cert = pngOf(9 * 1024 * 1024 + 123, run);
        JSONObject up = perform(multipart("/upload").file(new MockMultipartFile("file", "生产认证-" + run + ".png", "image/png", cert)), p, 200)
                .getJSONObject("data");
        String cid = up.getStr("cid");
        assertEquals(sha256(cert), up.getStr("sha256"));
        assertTrue(kubo.isPinned(cid));
        log("上传 " + cert.length + " 字节 → cid=" + cid + " sha256=" + up.getStr("sha256") + " status=" + up.getStr("status") + " 已 pin=" + kubo.isPinned(cid));
        // 伪造扩展名同样在真实环境被拒
        JSONObject fakeExt = perform(multipart("/upload").file(new MockMultipartFile("file", "cert.png", "image/png",
                "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))), p, 415);
        log("伪造扩展名（HTML 改名 .png）→ 415 " + fakeExt.getJSONObject("data").getStr("errorCode"));

        // 2. 上链：回执 CONFIRMED 后才绑定
        String tn = "FE" + run;
        JSONObject tx = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON).content(
                "{\"traceNumber\":\"" + tn + "\",\"companyName\":\"烟台果园\",\"productName\":\"苹果\",\"productionLocation\":\"山东烟台\","
                        + "\"variety\":\"红富士\",\"productionBatch\":\"B-" + run + "\",\"productionCert\":\"" + cid + "\","
                        + "\"productTime\":\"2026-09-20\",\"distributorUsername\":\"fd" + run + "\"}"), p, 200).getJSONObject("data");
        assertEquals("CONFIRMED", tx.getStr("state"));
        log("生产上链 CONFIRMED hash=" + tx.getStr("txHash") + " block=" + tx.getLong("blockNumber"));
        JSONObject stage = perform(get("/batches/" + tn), p, 200).getJSONObject("data").getJSONArray("stages").getJSONObject(0);
        assertEquals(cid, stage.getJSONObject("data").getStr("productionCert"));
        JSONObject file = stage.getJSONObject("file");
        assertEquals("BOUND", file.getStr("state"));
        assertTrue(file.getBool("available"));
        log("链上 productionCert=" + stage.getJSONObject("data").getStr("productionCert") + "；文件状态 " + file.getStr("state")
                + " bindSource=" + file.getStr("bindSource") + " available=" + file.getBool("available"));

        // 3. 消费者读回
        byte[] first = readPublic(tn, 200);
        assertArrayEquals(cert, first);
        log("消费者读回：HTTP 200，" + first.length + " 字节，sha256=" + sha256(first) + "，公开详情 fileState=" + publicFileState(tn));

        // 4. 重启 IPFS 后仍能读回（pin 住的内容持久化在 repo 里）
        restartIpfs();
        log("kubo 重启后：" + kubo.version() + "，pin 仍在=" + kubo.isPinned(cid));
        byte[] afterRestart = readPublic(tn, 200);
        assertArrayEquals(cert, afterRestart);
        log("重启后读回：HTTP 200，sha256=" + sha256(afterRestart));

        // 5. 删除 pin 并 GC：明确的缺失错误码，不是 500，也不是空图片
        kubo.unpin(cid);
        String gc = rpc("repo/gc?quiet=true");
        assertFalse(kubo.has(cid));
        log("pin rm + repo gc 完成（回收 " + gc.split("\n").length + " 个块）；本地是否还有该 CID：" + kubo.has(cid));
        MvcResult missing = mvc.perform(get("/trace/" + tn + "/file/production")).andReturn();
        assertEquals(410, missing.getResponse().getStatus());
        assertTrue(missing.getResponse().getContentType().startsWith("application/json"));
        JSONObject body = JSONUtil.parseObj(missing.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("FILE_MISSING", body.getJSONObject("data").getStr("errorCode"));
        log("GC 后公开读取：HTTP 410 " + missing.getResponse().getContentType() + " " + body);
        assertEquals("MISSING", publicFileState(tn));
        log("GC 后公开详情 fileState=MISSING；批次详情 file=" + perform(get("/batches/" + tn), p, 200).getJSONObject("data")
                .getJSONArray("stages").getJSONObject(0).getJSONObject("file"));

        // 6. 读模型重建：真实链上的全部溯源号（含此前 smoke / 其他测试直接写入的旧批次）
        JSONObject rebuild = perform(post("/admin/read-model/rebuild"), admin, 200).getJSONObject("data");
        log("第一次重建：" + rebuild);
        JSONObject again = perform(post("/admin/read-model/rebuild"), admin, 200).getJSONObject("data");
        log("第二次重建：" + again);
        assertEquals(0, again.getJSONObject("rows").getInt("created"));
        assertEquals(0, again.getJSONObject("rows").getInt("updated"));
        assertEquals(0, again.getInt("batchesBackfilled"));
        assertTrue(again.getJSONArray("errors").isEmpty(), again.toString());
        JSONObject unclaimed = perform(get("/admin/read-model/unclaimed").param("size", "100"), admin, 200).getJSONObject("data");
        log("未认领 / 部分认领（" + unclaimed.getInt("total") + "）：");
        unclaimed.getJSONArray("records").forEach(o -> {
            JSONObject r = (JSONObject) o;
            log("  " + r.getStr("traceNumber") + " " + r.getStr("claimStatus") + "：" + r.getStr("claimNote"));
        });
        JSONObject list = perform(get("/batches").param("size", "100"), p, 200).getJSONObject("data");
        log("生产商 fp" + run + " 的批次列表（分页，total=" + list.getInt("total") + "）："
                + list.getJSONArray("records").stream().map(o -> ((JSONObject) o).getStr("traceNumber")).reduce((a, b) -> a + ", " + b).orElse(""));
    }

    private byte[] readPublic(String tn, int status) throws Exception {
        MvcResult res = mvc.perform(get("/trace/" + tn + "/file/production")).andReturn();
        assertEquals(status, res.getResponse().getStatus(), res.getResponse().getContentAsString());
        assertEquals("image/png", res.getResponse().getContentType());
        assertEquals("nosniff", res.getResponse().getHeader("X-Content-Type-Options"));
        return res.getResponse().getContentAsByteArray();
    }

    private String publicFileState(String tn) throws Exception {
        MvcResult res = mvc.perform(get("/trace/detail/" + tn)).andReturn();
        return JSONUtil.parseObj(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .getJSONObject("data").getJSONObject("producer").getStr("fileState");
    }

    private static void restartIpfs() throws Exception {
        Process proc = new ProcessBuilder("bash", "-c", System.getenv("E2E_IPFS_RESTART")).redirectErrorStream(true).start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, proc.waitFor(), out);
        for (String line : out.split("\n")) {
            log("  " + line.trim());
        }
    }

    private static String rpc(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(System.getenv("E2E_IPFS_API_URL") + "/api/v0/" + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setReadTimeout(120000);
        assertEquals(200, conn.getResponseCode());
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** PNG 文件头 + 确定性伪随机内容 */
    private static byte[] pngOf(int size, String seed) {
        byte[] b = new byte[size];
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(head, 0, b, 0, head.length);
        long s = seed.hashCode() | 1L;
        for (int i = head.length; i < size; i++) {
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            b[i] = (byte) s;
        }
        return b;
    }

    private static String sha256(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static String userBody(String username, String role, String address) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"" + role
                + "\",\"chainAddress\":\"" + address + "\",\"companyName\":\"" + role + "-" + username + "\"}";
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
        System.out.println("[files-e2e] " + s);
    }
}
