package com.qhx.back.file;

import com.qhx.back.support.FakeKubo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KuboClient 对 kubo RPC 的约定：add / cat / pin / 缺失与不可用的区分。替身的响应按 kubo 0.29.0 实测构造。
 */
class KuboClientTest {

    @Test
    void 上传读回_取消pin后回收_缺失明确区分() throws Exception {
        try (FakeKubo kubo = new FakeKubo()) {
            KuboClient client = new KuboClient(kubo.apiUrl(), 2000, 5000);
            byte[] content = "hello-kubo".getBytes(StandardCharsets.UTF_8);
            String cid = client.add(new ByteArrayInputStream(content));
            assertTrue(kubo.pinned(cid));
            try (InputStream in = client.cat(cid)) {
                assertArrayEquals(content, in.readAllBytes());
            }
            assertTrue(client.has(cid));
            assertTrue(client.isPinned(cid));

            client.unpin(cid);
            client.unpin(cid); // 幂等：未 pin 视为成功
            assertFalse(client.isPinned(cid));
            kubo.unpinAndGc(cid);
            assertFalse(client.has(cid));
            IpfsException missing = assertThrows(IpfsException.class, () -> client.cat(cid));
            assertEquals(IpfsException.Kind.MISSING, missing.getKind());
        }
    }

    @Test
    void 节点不可用_UNAVAILABLE() {
        // 端口 9 没有服务
        KuboClient client = new KuboClient("http://127.0.0.1:9", 500, 500);
        IpfsException e = assertThrows(IpfsException.class, () -> client.cat("QmX"));
        assertEquals(IpfsException.Kind.UNAVAILABLE, e.getKind());
        assertEquals(IpfsException.Kind.UNAVAILABLE,
                assertThrows(IpfsException.class, () -> client.add(new ByteArrayInputStream(new byte[]{1}))).getKind());
    }

    @Test
    void 错误分类按实测文本() {
        assertEquals(IpfsException.Kind.MISSING, KuboClient.error(500,
                "{\"Message\":\"block was not found locally (offline): ipld: could not find QmX\",\"Code\":0,\"Type\":\"error\"}").getKind());
        assertEquals(IpfsException.Kind.ERROR, KuboClient.error(500,
                "{\"Message\":\"invalid path \\\"QmX\\\": path does not have enough components\",\"Code\":0,\"Type\":\"error\"}").getKind());
        assertEquals(IpfsException.Kind.UNAVAILABLE, KuboClient.error(502, "").getKind());
    }
}
