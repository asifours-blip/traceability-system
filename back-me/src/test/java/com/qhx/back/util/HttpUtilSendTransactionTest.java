package com.qhx.back.util;

import com.qhx.back.chain.TxOutcome;
import com.qhx.back.context.AddressContext;
import com.qhx.back.support.FakeWeBaseFront;
import com.qhx.back.support.RawHttpStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static com.qhx.back.support.FakeWeBaseFront.delayed;
import static com.qhx.back.support.FakeWeBaseFront.json;
import static com.qhx.back.support.FakeWeBaseFront.receiptRevert;
import static com.qhx.back.support.FakeWeBaseFront.receiptSuccess;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HttpUtil 对 WeBASE-Front 各种响应与传输故障的分类。走真实 HTTP（本地替身），不连链。
 */
class HttpUtilSendTransactionTest {

    private static final String SIGNER = "0x" + "a".repeat(40);
    private static final String HASH = "0x" + "12".repeat(32);

    private static FakeWeBaseFront fake;
    private HttpUtil util;

    @BeforeAll
    static void startFake() throws IOException {
        fake = new FakeWeBaseFront();
    }

    @AfterAll
    static void stopFake() {
        fake.close();
    }

    @BeforeEach
    void setUp() {
        fake.reset();
        util = newUtil(fake.baseUrl());
        AddressContext.setAddress(SIGNER);
    }

    @AfterEach
    void tearDown() throws IOException {
        util.close();
        AddressContext.clear();
    }

    private static HttpUtil newUtil(String url) {
        HttpUtil u = new HttpUtil();
        u.URL = url;
        u.CONTRACT_ADDRESS = "0x3d37f47620091952443a1df9c6b23a443e746beb";
        u.OWNER = "0x" + "0".repeat(40);
        u.CONTRACT_NAME = "Trace";
        u.CONTRACT_ABI = "[]";
        u.connectTimeoutMs = 1000;
        u.readTimeoutMs = 500;
        return u;
    }

    @Test
    void 成功回执_CONFIRMED_签名地址取自会话() {
        fake.on("newAgroFood", r -> receiptSuccess(r.user, HASH, 7));
        TxOutcome o = util.sendTransaction("newAgroFood", List.of("SY1"));
        assertEquals(TxOutcome.Kind.CONFIRMED, o.getKind());
        assertEquals(HASH, o.getTxHash());
        assertEquals(7L, o.getBlockNumber());
        assertEquals(SIGNER, fake.requests().get(0).user);
    }

    @Test
    void revert_REVERTED_带解码后的原因() {
        fake.on("addTraceInfoByDistributor", r -> receiptRevert(r.user, HASH, 8, "Trace: distribution already recorded"));
        TxOutcome o = util.sendTransaction("addTraceInfoByDistributor", List.of("SY1"));
        assertEquals(TxOutcome.Kind.REVERTED, o.getKind());
        assertEquals("Trace: distribution already recorded", o.getReason());
        assertEquals(HASH, o.getTxHash());
    }

    @Test
    void 读超时_UNKNOWN_且不自动重发() throws InterruptedException {
        fake.on("newAgroFood", r -> delayed(1500, receiptSuccess(r.user, HASH, 9)));
        TxOutcome o = util.sendTransaction("newAgroFood", List.of("SY1"));
        assertEquals(TxOutcome.Kind.UNKNOWN, o.getKind());
        assertTrue(o.getReason().contains("超时"), o.getReason());
        // 等替身把延迟的响应写完，确认客户端没有再发第二次
        Thread.sleep(1200);
        assertEquals(1, fake.requestsFor("newAgroFood").size());
    }

    @Test
    void 响应体中断_连接在响应体写完前断开_UNKNOWN() throws IOException {
        try (RawHttpStub stub = RawHttpStub.truncatedJson("{\"transactionHash\":\"" + HASH + "\",\"status\":\"0x", 600)) {
            HttpUtil viaStub = newUtil(stub.baseUrl());
            try {
                TxOutcome o = viaStub.sendTransaction("newAgroFood", List.of("SY1"));
                assertEquals(TxOutcome.Kind.UNKNOWN, o.getKind());
                assertTrue(o.getReason().contains("中断"), o.getReason());
                assertEquals(1, stub.requestCount());
            } finally {
                viaStub.close();
            }
        }
    }

    @Test
    void 坏JSON_UNKNOWN() {
        fake.on("newAgroFood", r -> json(200, "<html>gateway</html>"));
        assertEquals(TxOutcome.Kind.UNKNOWN, util.sendTransaction("newAgroFood", List.of("SY1")).getKind());
    }

    @Test
    void HTTP5xx_UNKNOWN() {
        fake.on("newAgroFood", r -> json(500, "{\"code\":500,\"errorMessage\":null}"));
        assertEquals(TxOutcome.Kind.UNKNOWN, util.sendTransaction("newAgroFood", List.of("SY1")).getKind());
        fake.on("newAgroFood", r -> json(503, "Service Unavailable"));
        assertEquals(TxOutcome.Kind.UNKNOWN, util.sendTransaction("newAgroFood", List.of("SY1")).getKind());
    }

    @Test
    void 连接被拒_NOT_SENT() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        HttpUtil down = newUtil("http://127.0.0.1:" + closedPort + "/WeBASE-Front");
        try {
            TxOutcome o = down.sendTransaction("newAgroFood", List.of("SY1"));
            assertEquals(TxOutcome.Kind.NOT_SENT, o.getKind());
        } finally {
            down.close();
        }
    }

    @Test
    void 会话没有绑定地址_拒绝发送且不请求WeBASE() {
        AddressContext.clear();
        assertThrows(IllegalStateException.class, () -> util.sendTransaction("newAgroFood", List.of()));
        assertTrue(fake.requests().isEmpty());
    }

    @Test
    void 按哈希查回执_找到与查不到() {
        fake.onReceipt(h -> h.equals(HASH) ? receiptSuccess(SIGNER, HASH, 10) : FakeWeBaseFront.receiptNotFound());
        TxOutcome found = util.queryReceipt(HASH);
        assertEquals(TxOutcome.Kind.CONFIRMED, found.getKind());
        assertEquals(10L, found.getBlockNumber());
        TxOutcome missing = util.queryReceipt("0x" + "34".repeat(32));
        assertEquals(TxOutcome.Kind.UNKNOWN, missing.getKind());
        assertEquals(List.of(HASH, "0x" + "34".repeat(32)), fake.receiptQueries());
    }
}
