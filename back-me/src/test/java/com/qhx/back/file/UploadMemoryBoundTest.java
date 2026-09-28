package com.qhx.back.file;

import com.qhx.back.support.FakeKubo;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式上传的内存上限：在 -Xmx48m 的独立 JVM 里上传 192 MB 的文件（是堆上限的 4 倍），
 * 检查、写入 kubo、读回核对三步全部完成且 SHA-256 与内容一致。任何一步整块读入内存都会 OOM 失败。
 */
class UploadMemoryBoundTest {

    private static final long SIZE = 192L * 1024 * 1024;
    private static final String HEAP = "48m";
    private static final long SEED = 20260928L;

    @Test
    void 在48MB堆的JVM里流式上传192MB文件_不整块读入内存_核对通过() throws Exception {
        try (FakeKubo kubo = new FakeKubo()) {
            String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            ProcessBuilder pb = new ProcessBuilder(java, "-Xmx" + HEAP, "-XX:+UseSerialGC",
                    "-cp", System.getProperty("java.class.path"),
                    UploadMemoryProbe.class.getName(), kubo.apiUrl(), String.valueOf(SIZE), String.valueOf(SEED));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            String resultLine = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (line.startsWith("RESULT ")) {
                        resultLine = line;
                    }
                }
            }
            assertTrue(process.waitFor(10, TimeUnit.MINUTES), "子进程超时");
            assertEquals(0, process.exitValue(), "子进程失败（OOM 即说明整块读入了内存）：\n" + output);
            assertNotNull(resultLine, output.toString());
            System.out.println("[memory-bound] " + resultLine);

            Map<String, String> r = new HashMap<>();
            for (String kv : resultLine.substring("RESULT ".length()).split(" ")) {
                String[] p = kv.split("=", 2);
                r.put(p[0], p[1]);
            }
            String expectedSha = sha256(new GeneratedPng(SIZE, SEED));
            assertEquals(expectedSha, r.get("sha256"), "服务端算出的 SHA-256 与内容不符");
            assertEquals(SIZE, Long.parseLong(r.get("size")));
            // kubo 那一侧收到并存下的内容也逐字节一致
            assertEquals(SIZE, kubo.sizeOf(r.get("cid")));
            assertEquals(expectedSha, kubo.sha256Of(r.get("cid")));

            long maxHeap = Long.parseLong(r.get("maxHeap"));
            long peak = Math.max(Long.parseLong(r.get("sampledPeakHeap")), Long.parseLong(r.get("poolPeakSum")));
            assertTrue(maxHeap <= 64L * 1024 * 1024, "子进程堆上限应约为 " + HEAP + "，实际 " + maxHeap);
            assertTrue(SIZE >= 3 * maxHeap, "文件必须远大于堆上限才有证明力");
            assertTrue(peak < maxHeap, "堆峰值 " + peak + " 不应超过上限 " + maxHeap);
        }
    }

    private static String sha256(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) != -1) {
            md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
