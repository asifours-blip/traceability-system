package com.qhx.back.flow;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qhx.back.chain.TraceStage;
import com.qhx.back.client.WeBaseClient;
import com.qhx.back.enums.UserRole;
import com.qhx.back.mapper.TraceBatchMapper;
import com.qhx.back.mapper.TraceReadModelMapper;
import com.qhx.back.model.FileObject;
import com.qhx.back.model.TraceAssignmentLog;
import com.qhx.back.model.TraceBatch;
import com.qhx.back.model.TraceReadModel;
import com.qhx.back.model.UserAccount;
import com.qhx.back.trace.ChainTraceReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.qhx.back.support.FakeWeBaseFront.receiptRevert;
import static com.qhx.back.support.FakeWeBaseFront.receiptTimeout;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 阶段 4：文件存储（上传校验、SHA-256 与 CID 核对、与交易状态机衔接的绑定、孤儿清理、缺失文件、公开读取）
 * 与查询（读模型重建、旧批次回填与未认领、分页边界）。
 * WeBASE-Front 替身打开合约模拟，kubo 替身按实测响应构造；子类决定数据库（H2 / 真实 MySQL）。
 */
abstract class FilesAndQueriesTestBase extends FlowTestSupport {

    @Autowired
    TraceReadModelMapper readModelMapper;
    @Autowired
    TraceBatchMapper traceBatchMapper;
    @Autowired
    WeBaseClient weBaseClient;

    // ================================================================ 上传校验

    @Test
    void 上传_超大_空文件_伪造扩展名_类型不在白名单_均被拒绝且不写入IPFS() throws Exception {
        Party p = party(UserRole.PRODUCER);
        int adds = kubo.addCount();

        byte[] big = new byte[MAX_BYTES + 1];
        System.arraycopy(png("x"), 0, big, 0, 8);
        assertEquals("FILE_TOO_LARGE", upload(p, "big.png", big, 413).getJSONObject("data").getStr("errorCode"));
        assertEquals("FILE_EMPTY", upload(p, "empty.png", new byte[0], 400).getJSONObject("data").getStr("errorCode"));
        // 伪造扩展名：HTML / SVG / 可执行文件改名成 .png
        for (String fake : new String[]{"<html><script>alert(1)</script></html>", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>", "MZ\u0090\u0000PE"}) {
            JSONObject body = upload(p, "cert.png", fake.getBytes(StandardCharsets.ISO_8859_1), 415);
            assertEquals("FILE_TYPE_NOT_ALLOWED", body.getJSONObject("data").getStr("errorCode"), fake);
        }
        // 内容是 PDF、扩展名写 .png：内容合法但与扩展名不符
        assertEquals("FILE_EXTENSION_MISMATCH", upload(p, "cert.png", pdf("x"), 415).getJSONObject("data").getStr("errorCode"));
        // GIF 不在白名单
        assertEquals("FILE_TYPE_NOT_ALLOWED", upload(p, "a.gif", "GIF89a\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1), 415)
                .getJSONObject("data").getStr("errorCode"));

        assertEquals(adds, kubo.addCount(), "被拒绝的文件不应写进 IPFS");
        assertEquals(0L, fileObjectMapper.selectCount(new LambdaQueryWrapper<FileObject>().eq(FileObject::getUploaderId, p.user.getId())));

        // 正好等于上限：通过
        byte[] edge = new byte[MAX_BYTES];
        System.arraycopy(png("edge"), 0, edge, 0, 8);
        assertEquals(MAX_BYTES, upload(p, "edge.png", edge, 200).getJSONObject("data").getLong("size"));

        // 零售商没有上传文件的业务，未登录 401
        upload(party(UserRole.RETAILER), "r.png", png("r"), 403);
        mvc.perform(multipart("/upload").file(new org.springframework.mock.web.MockMultipartFile("file", "a.png", "image/png", png("anon"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void 上传_服务端计算SHA256_CID读回核对_文件名清洗() throws Exception {
        Party p = party(UserRole.PRODUCER);
        byte[] content = png("sha-" + RUN);
        JSONObject data = upload(p, "../../C:\\evil\"<x>.PNG", content, 200).getJSONObject("data");
        String cid = data.getStr("cid");
        assertEquals(sha256(content), data.getStr("sha256"));
        assertEquals(content.length, data.getLong("size"));
        assertEquals("image/png", data.getStr("mimeType"));
        assertEquals("evilx.PNG", data.getStr("fileName"));
        assertEquals("UPLOADED", data.getStr("status"));
        // IPFS 里该 CID 的内容就是上传的内容，且已 pin
        assertEquals(sha256(content), kubo.sha256Of(cid));
        assertTrue(kubo.pinned(cid));
        FileObject row = fileObjectMapper.selectById(data.getLong("id"));
        assertEquals(cid, row.getCid());
        assertEquals(sha256(content), row.getSha256());
        assertEquals(p.user.getId(), row.getUploaderId());
        // 没有扩展名：按内容补上
        assertEquals("scan.pdf", upload(p, "scan", pdf("scan-" + RUN), 200).getJSONObject("data").getStr("fileName"));

        // 读回的内容与上传不一致（CID 对不上内容）：502，不登记、取消 pin
        kubo.corruptCat(true);
        byte[] other = png("corrupt-" + RUN);
        long before = fileObjectMapper.selectCount(new LambdaQueryWrapper<FileObject>().eq(FileObject::getUploaderId, p.user.getId()));
        assertEquals("IPFS_VERIFY_FAILED", upload(p, "c.png", other, 502).getJSONObject("data").getStr("errorCode"));
        assertEquals(before, fileObjectMapper.selectCount(new LambdaQueryWrapper<FileObject>().eq(FileObject::getUploaderId, p.user.getId())));
        assertFalse(kubo.pinned(FakeCid.of(other)), "核对失败的内容应取消 pin");
    }

    @Test
    void 阶段交易只能引用本账号上传且未被占用的文件() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party p2 = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        String othersCid = cert(p2);
        JSONObject body = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(traceNumber(), d.user.getUsername(), "2026-01-01", othersCid)), p.token, 400);
        assertFields(body, "productionCert");
        body = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(traceNumber(), d.user.getUsername(), "2026-01-01", "QmNeverUploaded" + RUN)), p.token, 400);
        assertFields(body, "productionCert");
        assertTrue(fake.requestsFor("newAgroFood").isEmpty());

        // 已绑定的文件不能再用于另一个批次
        String cid = cert(p);
        perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(traceNumber(), d.user.getUsername(), "2026-01-01", cid)), p.token, 200);
        body = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(traceNumber(), d.user.getUsername(), "2026-01-01", cid)), p.token, 400);
        assertTrue(body.getStr("mes").contains("已绑定"), body.toString());
    }

    // ================================================================ 绑定与交易状态机

    @Test
    void 交易FAILED或UNKNOWN不绑定_CONFIRMED后才绑定_未绑定不能公开读取() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        byte[] content = png("bind-" + RUN);
        String cid = upload(p, "cert.png", content, 200).getJSONObject("data").getStr("cid");

        // 1. 交易 FAILED（revert）：文件保持 UPLOADED
        String tn1 = traceNumber();
        fake.on("newAgroFood", r -> receiptRevert(r.user, fake.nextHash(), fake.nextBlock(), "ProducerRole: caller does not have the Producer role"));
        perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn1, d.user.getUsername(), "2026-01-01", cid)), p.token, 403);
        FileObject row = onlyRow(cid, p.user);
        assertEquals("UPLOADED", row.getStatus());
        assertNull(row.getBoundKey());

        // 2. 交易 UNKNOWN（其实已上链，但回执超时）：不绑定；链上已有 CID 也不能公开读取
        String tn2 = traceNumber();
        fake.on("newAgroFood", r -> {
            fake.applyToContract(r);
            return receiptTimeout();
        });
        Long txId = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(tn2, d.user.getUsername(), "2026-01-01", cid)), p.token, 202).getJSONObject("data").getLong("id");
        assertEquals("UNKNOWN", chainTxMapper.selectById(txId).getState());
        row = onlyRow(cid, p.user);
        assertEquals("UPLOADED", row.getStatus());
        assertEquals(tn2, row.getClaimTraceNumber());
        MvcResult notBound = mvc.perform(get("/trace/" + tn2 + "/file/production")).andReturn();
        assertEquals(404, notBound.getResponse().getStatus());
        assertEquals("FILE_NOT_BOUND", body(notBound).getJSONObject("data").getStr("errorCode"));
        assertTrue(notBound.getResponse().getContentType().startsWith("application/json"));
        // 未决交易占用中：同一文件不能拿去给别的批次；过期了也不会被当成孤儿
        JSONObject busy = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(traceNumber(), d.user.getUsername(), "2026-01-01", cid)), p.token, 400);
        assertTrue(busy.getStr("mes").contains(tn2), busy.toString());
        age(row);
        perform(post("/admin/files/cleanup-orphans"), login("admin", ADMIN_PASSWORD), 200);
        assertEquals("UPLOADED", fileObjectMapper.selectById(row.getId()).getStatus());
        assertEquals("NOT_BOUND", body(mvc.perform(get("/trace/detail/" + tn2)).andReturn())
                .getJSONObject("data").getJSONObject("producer").getStr("fileState"));

        // 3. 查证确认 CONFIRMED：此时才绑定，公开读取按链上 CID 返回原内容
        fake.reset();
        JSONObject verified = perform(post("/chain-tx/" + txId + "/verify"), p.token, 200).getJSONObject("data");
        assertEquals("STATE_CONFIRMED", verified.getStr("conclusion"));
        row = fileObjectMapper.selectById(row.getId());
        assertEquals("BOUND", row.getStatus());
        assertEquals(tn2, row.getTraceNumber());
        assertEquals(TraceStage.PRODUCTION.code(), row.getStage());
        assertEquals(txId, row.getChainTxId());
        assertEquals("TX_CONFIRMED", row.getBindSource());
        MvcResult file = mvc.perform(get("/trace/" + tn2 + "/file/production")).andReturn();
        assertEquals(200, file.getResponse().getStatus());
        assertArrayEquals(content, file.getResponse().getContentAsByteArray());
        assertEquals("AVAILABLE", body(mvc.perform(get("/trace/detail/" + tn2)).andReturn())
                .getJSONObject("data").getJSONObject("producer").getStr("fileState"));
    }

    @Test
    void 公开读取_图片内联_PDF按附件下载_带nosniff与正确的Content_Type() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        Party r = party(UserRole.RETAILER);
        String tn = produced(p, d);
        byte[] report = pdf("report-" + RUN);
        String reportCid = upload(d, "质检报告 2026.pdf", report, 200).getJSONObject("data").getStr("cid");
        perform(post("/distributor/add").contentType(MediaType.APPLICATION_JSON)
                .content(distributorBody(tn, r.user.getUsername(), "10", "100", reportCid)), d.token, 200);

        MvcResult res = mvc.perform(get("/trace/" + tn + "/file/distribution")).andReturn();
        assertEquals(200, res.getResponse().getStatus());
        assertEquals("application/pdf", res.getResponse().getContentType());
        assertEquals("nosniff", res.getResponse().getHeader("X-Content-Type-Options"));
        String disposition = res.getResponse().getHeader("Content-Disposition");
        assertTrue(disposition.startsWith("attachment;"), disposition);
        assertTrue(disposition.contains("filename*=UTF-8''%E8%B4%A8%E6%A3%80%E6%8A%A5%E5%91%8A%202026.pdf"), disposition);
        assertEquals(report.length, res.getResponse().getContentLength());
        assertArrayEquals(report, res.getResponse().getContentAsByteArray());

        MvcResult img = mvc.perform(get("/trace/" + tn + "/file/production")).andReturn();
        assertEquals("image/png", img.getResponse().getContentType());
        assertTrue(img.getResponse().getHeader("Content-Disposition").startsWith("inline;"));
        assertEquals("nosniff", img.getResponse().getHeader("X-Content-Type-Options"));
    }

    @Test
    void IPFS里文件缺失_公开读取返回410和明确错误码_详情提示缺失() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        String tn = produced(p, d);
        FileObject bound = fileObjectMapper.selectOne(new LambdaQueryWrapper<FileObject>().eq(FileObject::getBoundKey, tn + ":1"));
        assertNotNull(bound);

        // 与 `ipfs pin rm` + `ipfs repo gc` 等效
        kubo.unpinAndGc(bound.getCid());
        MvcResult res = mvc.perform(get("/trace/" + tn + "/file/production")).andReturn();
        assertEquals(410, res.getResponse().getStatus());
        assertTrue(res.getResponse().getContentType().startsWith("application/json"), "不能返回空图片");
        JSONObject body = body(res);
        assertEquals("FILE_MISSING", body.getJSONObject("data").getStr("errorCode"));
        assertTrue(body.getStr("mes").contains("文件缺失"), body.toString());

        JSONObject file = perform(get("/batches/" + tn), p.token, 200).getJSONObject("data")
                .getJSONArray("stages").getJSONObject(0).getJSONObject("file");
        assertEquals("BOUND", file.getStr("state"));
        assertFalse(file.getBool("available"));
        assertEquals("FILE_MISSING", file.getStr("errorCode"));
        assertEquals("MISSING", body(mvc.perform(get("/trace/detail/" + tn)).andReturn())
                .getJSONObject("data").getJSONObject("producer").getStr("fileState"));
    }

    // ================================================================ 孤儿清理

    @Test
    void 孤儿清理_超期未绑定标ORPHANED并取消pin_共享CID保留_已清理的不能再用() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        String admin = login("admin", ADMIN_PASSWORD);

        // a：上传后一直没用
        String cidA = cert(p);
        // 同一内容上传两次：一条绑定到批次，另一条闲置
        byte[] shared = png("shared-" + RUN);
        String cidB = upload(p, "b.png", shared, 200).getJSONObject("data").getStr("cid");
        upload(p, "b2.png", shared, 200);
        perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(traceNumber(), d.user.getUsername(), "2026-01-01", cidB)), p.token, 200);
        FileObject a = onlyRow(cidA, p.user);
        FileObject bound = fileObjectMapper.selectList(new LambdaQueryWrapper<FileObject>().eq(FileObject::getCid, cidB)
                .eq(FileObject::getStatus, "BOUND")).get(0);
        FileObject b2 = fileObjectMapper.selectList(new LambdaQueryWrapper<FileObject>().eq(FileObject::getCid, cidB)
                .eq(FileObject::getStatus, "UPLOADED")).get(0);
        // 期限内的不动
        JSONObject first = perform(post("/admin/files/cleanup-orphans"), admin, 200).getJSONObject("data");
        assertEquals("UPLOADED", fileObjectMapper.selectById(a.getId()).getStatus());
        assertNotNull(first.get("deadline"));

        age(a);
        age(b2);
        age(bound);
        perform(post("/admin/files/cleanup-orphans"), admin, 200);
        a = fileObjectMapper.selectById(a.getId());
        assertEquals("ORPHANED", a.getStatus());
        assertNotNull(a.getOrphanedAt());
        assertTrue(a.getUnpinned());
        assertFalse(kubo.pinned(cidA), "孤儿文件应从本地 IPFS 取消 pin");
        b2 = fileObjectMapper.selectById(b2.getId());
        assertEquals("ORPHANED", b2.getStatus());
        assertTrue(kubo.pinned(cidB), "同一 CID 仍被已绑定的记录引用，不能取消 pin");
        assertTrue(b2.getUnpinNote().contains("保留"), b2.getUnpinNote());
        assertEquals("BOUND", fileObjectMapper.selectById(bound.getId()).getStatus(), "BOUND 不受清理影响");

        // 再跑一次：幂等
        perform(post("/admin/files/cleanup-orphans"), admin, 200);
        assertEquals("ORPHANED", fileObjectMapper.selectById(a.getId()).getStatus());
        // 已清理的文件不能再用于上链
        JSONObject body = perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(traceNumber(), d.user.getUsername(), "2026-01-01", cidA)), p.token, 400);
        assertTrue(body.getStr("mes").contains("已被清理"), body.toString());
        // 只有管理员能手动触发
        perform(post("/admin/files/cleanup-orphans"), p.token, 403);
    }

    // ================================================================ 读模型

    @Test
    void 读模型重建_旧批次按链上写入者回填_匹配不上进未认领_幂等_与逐条读链一致() throws Exception {
        String admin = login("admin", ADMIN_PASSWORD);
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        String appTn = produced(p, d);

        // 本功能上线前直接写在链上的旧批次（不经后端，没有 trace_batch / chain_tx / file_object）
        UserAccount legacyP = account(UserRole.PRODUCER);
        UserAccount legacyD = account(UserRole.DISTRIBUTOR);
        UserAccount wrongRole = account(UserRole.DISTRIBUTOR);
        String strangerP = address();
        String strangerD = address();
        byte[] legacyPng = png("legacy-" + RUN);
        String legacyCid = kubo.put(legacyPng, false);
        String goneCid = "QmGone" + RUN;
        String l1 = "LG" + RUN + "-1";
        String l2 = "LG" + RUN + "-2";
        String l3 = "LG" + RUN + "-3";
        String l4 = "LG" + RUN + "-4";
        assertTrue(fake.applyToContract(legacyP.getChainAddress(), "newAgroFood", l1, "砀山果园", "酥梨", "安徽砀山", "砀山酥梨", "LB1", legacyCid, "2025-10-01"));
        assertTrue(fake.applyToContract(legacyD.getChainAddress(), "addTraceInfoByDistributor", l1, "宿州仓配", "冷藏", "货车", "LD1", "宿州", 8, 50, goneCid));
        assertTrue(fake.applyToContract(strangerP, "newAgroFood", l2, "无主农场", "桃", "某地", "水蜜桃", "LB2", goneCid, "2025-09-01"));
        assertTrue(fake.applyToContract(legacyP.getChainAddress(), "newAgroFood", l3, "砀山果园", "苹果", "安徽砀山", "红富士", "LB3", goneCid, "2025-10-02"));
        assertTrue(fake.applyToContract(strangerD, "addTraceInfoByDistributor", l3, "陌生仓配", "常温", "货车", "LD3", "某地", 5, 20, goneCid));
        assertTrue(fake.applyToContract(wrongRole.getChainAddress(), "newAgroFood", l4, "角色不对", "葡萄", "某地", "巨峰", "LB4", goneCid, "2025-08-01"));
        // 链上已不存在的残留行（例如换过合约）
        TraceReadModel stale = new TraceReadModel();
        stale.setTraceNumber("STALE-" + RUN);
        stale.setStageReached(1);
        stale.setClaimStatus("CLAIMED");
        stale.setSyncedAt(new Date());
        readModelMapper.insert(stale);
        List<String> mine = Arrays.asList(appTn, l1, l2, l3, l4);

        JSONObject report = perform(post("/admin/read-model/rebuild"), admin, 200).getJSONObject("data");
        System.out.println("[read-model] 第一次重建：" + report);
        assertEquals(5, report.getInt("onChain"));
        assertEquals(2, report.getInt("batchesBackfilled"));
        assertTrue(report.getInt("removedStale") >= 1);
        assertNull(readModelMapper.selectById("STALE-" + RUN));
        assertEquals(1, report.getJSONObject("files").getInt("IMPORTED"));
        assertEquals(1, report.getJSONObject("files").getInt("ALREADY_BOUND"));
        // l1 分销、l2、l3 两个阶段、l4 引用的 CID 在本地 IPFS 里都没有
        assertEquals(5, report.getJSONObject("files").getInt("MISSING"));
        assertTrue(report.getJSONArray("errors").isEmpty(), report.toString());
        JSONObject claims = report.getJSONObject("claims");
        assertEquals(2, claims.getInt("CLAIMED"));
        assertEquals(1, claims.getInt("PARTIAL"));
        assertEquals(2, claims.getInt("UNCLAIMED"));

        // 回填：旧批次归属到链上写入者对应的账号；分销商、交接历史一并补上
        TraceBatch b1 = traceBatchMapper.selectOne(new LambdaQueryWrapper<TraceBatch>().eq(TraceBatch::getTraceNumber, l1));
        assertEquals(legacyP.getId(), b1.getProducerId());
        assertEquals(legacyD.getId(), b1.getDistributorId());
        assertEquals("酥梨", b1.getProductName());
        TraceAssignmentLog backfillLog = assignmentLogMapper.selectOne(new LambdaQueryWrapper<TraceAssignmentLog>()
                .eq(TraceAssignmentLog::getTraceNumber, l1));
        assertTrue(backfillLog.getReason().contains("回填"));
        assertNull(traceBatchMapper.selectOne(new LambdaQueryWrapper<TraceBatch>().eq(TraceBatch::getTraceNumber, l2)));
        // 旧批次出现在对应角色的列表里，阶段状态按链上事实显示
        Party legacyPParty = new Party(legacyP, login(legacyP.getUsername(), USER_PASSWORD));
        JSONObject row1 = find(batches(legacyPParty), l1);
        assertNotNull(row1, "回填后生产商应能在列表里看到旧批次");
        assertEquals("CONFIRMED", row1.getJSONObject("stages").getJSONObject("DISTRIBUTION").getStr("status"));
        assertNotNull(find(batches(new Party(legacyD, login(legacyD.getUsername(), USER_PASSWORD))), l1));
        assertNotNull(find(batches(legacyPParty), l3));

        // 旧文件：本地 IPFS 里有的导入并 pin 住，可公开读取；找不到的不登记
        assertTrue(kubo.pinned(legacyCid));
        MvcResult legacyFile = mvc.perform(get("/trace/" + l1 + "/file/production")).andReturn();
        assertEquals(200, legacyFile.getResponse().getStatus());
        assertArrayEquals(legacyPng, legacyFile.getResponse().getContentAsByteArray());
        assertEquals(404, mvc.perform(get("/trace/" + l1 + "/file/distribution")).andReturn().getResponse().getStatus());

        // 未认领：l2（没有账号）、l3（分销写入者对不上）、l4（地址是分销商账号）；已认领的不在其中
        JSONObject page = perform(get("/admin/read-model/unclaimed").param("size", "100"), admin, 200).getJSONObject("data");
        Map<String, JSONObject> unclaimed = page.getJSONArray("records").stream().map(o -> (JSONObject) o)
                .collect(Collectors.toMap(o -> o.getStr("traceNumber"), o -> o));
        assertEquals("UNCLAIMED", unclaimed.get(l2).getStr("claimStatus"));
        assertTrue(unclaimed.get(l2).getStr("claimNote").contains("没有对应账号"));
        assertEquals("PARTIAL", unclaimed.get(l3).getStr("claimStatus"));
        assertTrue(unclaimed.get(l3).getStr("claimNote").contains("分销"));
        assertTrue(unclaimed.get(l4).getStr("claimNote").contains("不是生产商"));
        assertFalse(unclaimed.containsKey(l1));
        assertFalse(unclaimed.containsKey(appTn));
        perform(get("/admin/read-model/unclaimed"), p.token, 403);
        perform(post("/admin/read-model/rebuild"), p.token, 403);

        // 幂等：再重建一次，没有任何写入，快照完全相同
        String before = snapshot(mine);
        JSONObject again = perform(post("/admin/read-model/rebuild"), admin, 200).getJSONObject("data");
        System.out.println("[read-model] 第二次重建：" + again);
        assertEquals(0, again.getJSONObject("rows").getInt("created"));
        assertEquals(0, again.getJSONObject("rows").getInt("updated"));
        assertEquals(5, again.getJSONObject("rows").getInt("unchanged"));
        assertEquals(0, again.getInt("batchesBackfilled"));
        assertEquals(0, again.getInt("partnersBackfilled"));
        assertEquals(0, again.getInt("removedStale"));
        assertNull(again.getJSONObject("files").get("IMPORTED"));
        assertEquals(before, snapshot(mine));

        // 与逐条读链的结果一致
        ChainTraceReader chain = new ChainTraceReader(weBaseClient);
        List<String> onChain = chain.list();
        assertEquals(new HashSet<>(mine), new HashSet<>(onChain));
        for (String tn : onChain) {
            TraceReadModel row = readModelMapper.selectById(tn);
            assertNotNull(row, tn);
            List<String> actors = chain.actors(tn);
            int reached = 0;
            for (TraceStage stage : TraceStage.values()) {
                String actor = chain.actor(actors, stage);
                Map<String, Object> data = chain.stage(tn, stage);
                String stored = stage == TraceStage.PRODUCTION ? row.getProductionData()
                        : stage == TraceStage.DISTRIBUTION ? row.getDistributionData() : row.getRetailData();
                String storedActor = stage == TraceStage.PRODUCTION ? row.getProducerAddress()
                        : stage == TraceStage.DISTRIBUTION ? row.getDistributorAddress() : row.getRetailerAddress();
                assertEquals(actor == null ? null : actor.toLowerCase(), storedActor, tn + " " + stage);
                if (data == null) {
                    assertNull(stored, tn + " " + stage);
                } else {
                    reached = stage.code();
                    // 逐字段、同顺序、同类型：读模型里存的就是读链结果的 JSON
                    assertEquals(JSONUtil.toJsonStr(data), stored, tn + " " + stage);
                }
            }
            assertEquals(reached, row.getStageReached(), tn);
        }

        // 之后补建了账号：再重建即认领
        UserAccount late = new UserAccount();
        late.setUsername("late" + RUN);
        late.setPasswordHash(authService.hashPassword(USER_PASSWORD));
        late.setRole(UserRole.PRODUCER.name());
        late.setChainAddress(strangerP);
        late.setCompanyName("补建");
        late.setEnabled(true);
        late.setCreatedAt(new Date());
        late.setUpdatedAt(new Date());
        userAccountMapper.insert(late);
        perform(post("/admin/read-model/rebuild"), admin, 200);
        assertEquals("CLAIMED", readModelMapper.selectById(l2).getClaimStatus());
        assertEquals(late.getId(), traceBatchMapper.selectOne(new LambdaQueryWrapper<TraceBatch>()
                .eq(TraceBatch::getTraceNumber, l2)).getProducerId());
    }

    @Test
    void 消费者详情从读模型取_不再读链() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        String tn = produced(p, d);
        fake.reset();
        JSONObject data = body(mvc.perform(get("/trace/detail/" + tn)).andReturn()).getJSONObject("data");
        assertEquals("苹果", data.getJSONObject("producer").getStr("productName"));
        assertEquals("READ_MODEL", data.getStr("source"));
        assertTrue(fake.requests().isEmpty(), "消费者详情不应读链：" + fake.requests());
        // 读模型里没有、链上也没有：读一次链确认后 404
        assertEquals(404, mvc.perform(get("/trace/detail/NOPE-" + RUN)).andReturn().getResponse().getStatus());
    }

    // ================================================================ 分页

    @Test
    void 分页边界_批次列表_待我处理_关键字转义() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        List<String> tns = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tns.add(produced(p, d));
        }
        // 第 4 个：交易失败，批次已建档但链上没有
        String failed = traceNumber();
        fake.on("newAgroFood", r -> receiptRevert(r.user, fake.nextHash(), fake.nextBlock(), "Trace: traceNumber already exists"));
        perform(post("/producer/add").contentType(MediaType.APPLICATION_JSON)
                .content(producerBody(failed, d.user.getUsername(), "2026-01-01", cert(p))), p.token, 409);
        fake.reset();

        JSONObject p1 = page(p, "/batches", "page", "1", "size", "2");
        assertEquals(4, p1.getInt("total"));
        assertEquals(2, p1.getJSONArray("records").size());
        assertEquals(1, p1.getInt("page"));
        assertEquals(2, p1.getInt("size"));
        // 按建档倒序：第一条是最后建的
        assertEquals(failed, p1.getJSONArray("records").getJSONObject(0).getStr("traceNumber"));
        JSONObject p2 = page(p, "/batches", "page", "2", "size", "2");
        assertEquals(2, p2.getJSONArray("records").size());
        assertEquals(tns.get(0), p2.getJSONArray("records").getJSONObject(1).getStr("traceNumber"));
        // 超出末页：空列表，total 照常
        JSONObject p3 = page(p, "/batches", "page", "3", "size", "2");
        assertEquals(0, p3.getJSONArray("records").size());
        assertEquals(4, p3.getInt("total"));
        assertEquals(4, page(p, "/batches", "page", "1", "size", "100").getJSONArray("records").size());
        // 非法参数 400
        assertFields(perform(get("/batches").param("page", "0"), p.token, 400), "page");
        assertFields(perform(get("/batches").param("size", "0"), p.token, 400), "size");
        assertFields(perform(get("/batches").param("size", "101"), p.token, 400), "size");
        assertFields(perform(get("/batches").param("page", "-1").param("size", "10"), p.token, 400), "page");

        // 待我处理：生产商看链上还没有的；分销商看已生产未分销的
        JSONObject todo = page(p, "/batches", "todo", "true");
        assertEquals(1, todo.getInt("total"));
        assertEquals(failed, todo.getJSONArray("records").getJSONObject(0).getStr("traceNumber"));
        assertEquals(3, page(d, "/batches", "todo", "true").getInt("total"));

        // 关键字：精确命中一个；% 被转义，不会匹配全部
        assertEquals(1, page(p, "/batches", "keyword", tns.get(1)).getInt("total"));
        assertEquals(0, page(p, "/batches", "keyword", "%").getInt("total"));
        assertEquals(0, page(p, "/batches", "keyword", "_").getInt("total"));
    }

    @Test
    void 分页边界_消费者查询只含公开字段() throws Exception {
        Party p = party(UserRole.PRODUCER);
        Party d = party(UserRole.DISTRIBUTOR);
        String tn = produced(p, d);
        fake.reset();

        JSONObject res = body(mvc.perform(get("/trace/search").param("keyword", tn)).andReturn()).getJSONObject("data");
        assertEquals(1, res.getInt("total"));
        JSONObject row = res.getJSONArray("records").getJSONObject(0);
        assertEquals(new HashSet<>(Arrays.asList("traceNumber", "productName", "companyName", "productionLocation", "variety",
                "productTime", "stageReached", "productionTimestamp")), row.keySet());
        assertFalse(row.toString().contains(p.user.getChainAddress()));
        assertTrue(fake.requests().isEmpty(), "查询不应读链");

        JSONObject beyond = body(mvc.perform(get("/trace/search").param("keyword", tn).param("page", "2")).andReturn()).getJSONObject("data");
        assertEquals(0, beyond.getJSONArray("records").size());
        assertEquals(1, beyond.getInt("total"));
        assertEquals(0, body(mvc.perform(get("/trace/search").param("keyword", "%")).andReturn()).getJSONObject("data").getInt("total"));
        assertEquals(400, mvc.perform(get("/trace/search").param("size", "101")).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(get("/trace/search").param("page", "0")).andReturn().getResponse().getStatus());
    }

    // ================================================================ 工具

    /** 本测试用的 CID 规则与替身一致，便于在上传失败时仍能算出 CID */
    static final class FakeCid {
        static String of(byte[] content) throws Exception {
            return "QmFake" + sha256(content).substring(0, 40);
        }
    }

    static String sha256(byte[] content) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(content)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    FileObject onlyRow(String cid, UserAccount uploader) {
        List<FileObject> rows = fileObjectMapper.selectList(new LambdaQueryWrapper<FileObject>()
                .eq(FileObject::getCid, cid).eq(FileObject::getUploaderId, uploader.getId()));
        assertEquals(1, rows.size(), rows.toString());
        return rows.get(0);
    }

    /** 把上传时间改到 25 小时前（默认期限 24 小时） */
    void age(FileObject row) {
        fileObjectMapper.update(null, new LambdaUpdateWrapper<FileObject>()
                .set(FileObject::getCreatedAt, new Date(System.currentTimeMillis() - 25 * 3600_000L))
                .eq(FileObject::getId, row.getId()));
    }

    JSONObject page(Party who, String path, String... params) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b = get(path);
        for (int i = 0; i < params.length; i += 2) {
            b.param(params[i], params[i + 1]);
        }
        return perform(b, who.token, 200).getJSONObject("data");
    }

    /** 读模型行 + 归属 + 交接历史条数 + 已绑定文件，用于比较两次重建前后是否完全一致 */
    String snapshot(List<String> tns) {
        Set<String> set = new HashSet<>(tns);
        StringBuilder sb = new StringBuilder();
        readModelMapper.selectBatchIds(tns).stream().sorted((a, b) -> a.getTraceNumber().compareTo(b.getTraceNumber()))
                .forEach(r -> sb.append(r).append('\n'));
        traceBatchMapper.selectList(new LambdaQueryWrapper<TraceBatch>().in(TraceBatch::getTraceNumber, set).orderByAsc(TraceBatch::getId))
                .forEach(b -> sb.append(b).append('\n'));
        sb.append("assignments=").append(assignmentLogMapper.selectCount(new LambdaQueryWrapper<TraceAssignmentLog>()
                .in(TraceAssignmentLog::getTraceNumber, set))).append('\n');
        fileObjectMapper.selectList(new LambdaQueryWrapper<FileObject>().in(FileObject::getTraceNumber, set).orderByAsc(FileObject::getId))
                .forEach(f -> sb.append(f).append('\n'));
        return sb.toString();
    }
}
