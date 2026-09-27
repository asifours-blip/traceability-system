package com.qhx.back.chain;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.qhx.back.context.AddressContext;
import com.qhx.back.util.HttpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用后端自己的 HttpUtil / StageProbe 打真实的本地隔离链（scripts/local-chain/）。
 * 只有设置了 E2E_SMOKE_FILE（smoke.sh 生成的 last-smoke.json，含合约地址与托管账户地址）才运行；CI 不设置，自动跳过。
 */
@EnabledIfEnvironmentVariable(named = "E2E_SMOKE_FILE", matches = ".+")
class RealChainSmokeTest {

    private JSONObject accounts;
    private HttpUtil util;
    private final String tn = "JAVA-" + System.currentTimeMillis();

    @BeforeEach
    void setUp() {
        JSONObject smoke = JSONUtil.parseObj(FileUtil.readString(new File(System.getenv("E2E_SMOKE_FILE")), StandardCharsets.UTF_8));
        accounts = smoke.getJSONObject("accounts");
        util = new HttpUtil();
        util.URL = smoke.getJSONObject("meta").getStr("frontUrl");
        util.GROUP_ID = smoke.getJSONObject("meta").getInt("groupId");
        util.CONTRACT_ADDRESS = smoke.getStr("contractAddress");
        util.OWNER = accounts.getStr("owner");
        util.CONTRACT_NAME = "Trace";
        util.CONTRACT_ABI = FileUtil.readString(new File("../contracts/abi/Trace.json"), StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() throws IOException {
        AddressContext.clear();
        util.close();
    }

    @Test
    void 真实链_发交易分类_按哈希查回执_读链查证() {
        StageProbe probe = new StageProbe(util);
        List<Object> produce = Arrays.asList(tn, "农场J", "苹果", "烟台", "红富士", "B9", "QmJ", "2026-09-28");

        AddressContext.setAddress(accounts.getStr("producer"));
        TxOutcome ok = util.sendTransaction("newAgroFood", produce);
        assertEquals(TxOutcome.Kind.CONFIRMED, ok.getKind(), ok.toString());
        assertNotNull(ok.getTxHash());
        assertTrue(ok.getBlockNumber() > 0);
        System.out.println("[real-chain] newAgroFood " + ok);

        TxOutcome dup = util.sendTransaction("newAgroFood", produce);
        assertEquals(TxOutcome.Kind.REVERTED, dup.getKind(), dup.toString());
        assertEquals("Trace: traceNumber already exists", dup.getReason());
        assertEquals(409, ChainErrors.of(dup).status);
        System.out.println("[real-chain] duplicate newAgroFood " + dup);

        TxOutcome receipt = util.queryReceipt(ok.getTxHash());
        assertEquals(TxOutcome.Kind.CONFIRMED, receipt.getKind(), receipt.toString());
        assertEquals(ok.getBlockNumber(), receipt.getBlockNumber());
        assertEquals(TxOutcome.Kind.UNKNOWN, util.queryReceipt("0x" + "cd".repeat(32)).getKind());

        String producer = accounts.getStr("producer");
        assertEquals(StageProbe.Conclusion.WRITTEN_BY_SIGNER,
                probe.probe(TraceStage.PRODUCTION, tn, producer, ParamsDigest.of(produce)).conclusion);
        assertEquals(StageProbe.Conclusion.CONFLICT,
                probe.probe(TraceStage.PRODUCTION, tn, producer, ParamsDigest.of(List.of(tn, "别的数据"))).conclusion);
        assertEquals(StageProbe.Conclusion.CONFLICT,
                probe.probe(TraceStage.PRODUCTION, tn, accounts.getStr("outsider"), ParamsDigest.of(produce)).conclusion);
        assertEquals(StageProbe.Conclusion.NOT_WRITTEN,
                probe.probe(TraceStage.DISTRIBUTION, tn, accounts.getStr("distributor"), "x").conclusion);
        assertEquals(StageProbe.Conclusion.NOT_WRITTEN,
                probe.probe(TraceStage.PRODUCTION, tn + "-none", producer, "x").conclusion);

        // uint 参数用 Long 发出，链上读回是字符串 "10"：摘要必须一致
        List<Object> distribute = Arrays.asList(tn, "仓配J", "冷藏", "冷链车", "D9", "济南", 10L, 100L, "QmR");
        AddressContext.setAddress(accounts.getStr("distributor"));
        TxOutcome dist = util.sendTransaction("addTraceInfoByDistributor", distribute);
        assertEquals(TxOutcome.Kind.CONFIRMED, dist.getKind(), dist.toString());
        assertEquals(StageProbe.Conclusion.WRITTEN_BY_SIGNER,
                probe.probe(TraceStage.DISTRIBUTION, tn, accounts.getStr("distributor"), ParamsDigest.of(distribute)).conclusion);

        List<Object> retail = Arrays.asList(tn, "门店J", 20L, 5L, 7L, "INV-J", "2026-09-29");
        AddressContext.setAddress(accounts.getStr("retailer"));
        TxOutcome early = util.sendTransaction("addTraceInfoByRetailer", Arrays.asList(tn + "-none", "门店J", 20L, 5L, 7L, "INV-J", "2026-09-29"));
        assertEquals(TxOutcome.Kind.REVERTED, early.getKind(), early.toString());
        assertEquals(404, ChainErrors.of(early).status);
        TxOutcome ret = util.sendTransaction("addTraceInfoByRetailer", retail);
        assertEquals(TxOutcome.Kind.CONFIRMED, ret.getKind(), ret.toString());
        assertEquals(StageProbe.Conclusion.WRITTEN_BY_SIGNER,
                probe.probe(TraceStage.RETAIL, tn, accounts.getStr("retailer"), ParamsDigest.of(retail)).conclusion);

        // 签名地址在 WeBASE-Front 里没有私钥：发送前被拒绝
        AddressContext.setAddress("0x" + "1".repeat(40));
        TxOutcome rejected = util.sendTransaction("newAgroFood", Arrays.asList(tn + "-x", "c", "p", "l", "v", "b", "c", "t"));
        assertEquals(TxOutcome.Kind.REJECTED, rejected.getKind(), rejected.toString());
        assertEquals(201015, rejected.getFrontCode());
        System.out.println("[real-chain] unknown signer " + rejected);
    }
}
