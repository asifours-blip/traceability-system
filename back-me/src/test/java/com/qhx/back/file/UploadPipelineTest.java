package com.qhx.back.file;

import com.qhx.back.support.FakeKubo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 上传流程里不依赖数据库的分支：IPFS 不可用、读回内容与 CID 不符、上传过程中文件被换掉 */
class UploadPipelineTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};

    @Test
    void IPFS不可用_503() {
        UploadPipeline pipeline = new UploadPipeline(new KuboClient("http://127.0.0.1:9", 500, 500), 1024);
        FileRejectedException e = assertThrows(FileRejectedException.class,
                () -> pipeline.store(() -> new ByteArrayInputStream(PNG), "a.png"));
        assertEquals(503, e.getStatus());
        assertEquals("IPFS_UNAVAILABLE", e.getErrorCode());
    }

    @Test
    void 读回内容与上传内容不一致_502_带回CID供取消pin() throws Exception {
        try (FakeKubo kubo = new FakeKubo()) {
            kubo.corruptCat(true);
            UploadPipeline pipeline = new UploadPipeline(new KuboClient(kubo.apiUrl(), 2000, 5000), 1024);
            FileRejectedException e = assertThrows(FileRejectedException.class,
                    () -> pipeline.store(() -> new ByteArrayInputStream(PNG), "a.png"));
            assertEquals(502, e.getStatus());
            assertEquals("IPFS_VERIFY_FAILED", e.getErrorCode());
            assertNotNull(e.getCid());
        }
    }

    @Test
    void 两次读取之间文件被替换_拒绝() throws Exception {
        try (FakeKubo kubo = new FakeKubo()) {
            UploadPipeline pipeline = new UploadPipeline(new KuboClient(kubo.apiUrl(), 2000, 5000), 1024);
            byte[] other = PNG.clone();
            other[10] = 9;
            int[] opens = {0};
            FileRejectedException e = assertThrows(FileRejectedException.class,
                    () -> pipeline.store(() -> new ByteArrayInputStream(opens[0]++ == 0 ? PNG : other), "a.png"));
            assertEquals("FILE_CHANGED", e.getErrorCode());
        }
    }

    @Test
    void 通过_返回服务端计算的SHA256与读回核对过的CID() throws Exception {
        try (FakeKubo kubo = new FakeKubo()) {
            UploadPipeline pipeline = new UploadPipeline(new KuboClient(kubo.apiUrl(), 2000, 5000), 1024);
            UploadPipeline.Stored s = pipeline.store(() -> new ByteArrayInputStream(PNG), "scan");
            assertEquals(kubo.sha256Of(s.cid), s.sha256);
            assertEquals(PNG.length, s.size);
            assertEquals("scan.png", s.fileName);
            assertFalse(s.type.mime.isEmpty());
        }
    }
}
