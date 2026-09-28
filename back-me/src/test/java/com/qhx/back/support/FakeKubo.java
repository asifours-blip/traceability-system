package com.qhx.back.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * kubo HTTP RPC 的本地替身：响应结构与错误文本按 kubo 0.29.0 实测（docs/files.md「kubo 接口核验」）构造。
 * 内容落在临时目录的文件里、请求体边收边写盘，替身本身也不把大文件放进内存。
 * CID 不是真实的 IPFS CID（用 "QmFake" + sha256 前 40 位），只保证「同内容同 CID、字母数字」。
 */
public class FakeKubo implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Path dir;
    private final Set<String> pins = ConcurrentHashMap.newKeySet();
    private final Map<String, Path> blobs = new ConcurrentHashMap<>();
    private final AtomicInteger adds = new AtomicInteger();
    /** 打开后 cat 返回与存入内容不同的字节，用于测试「CID 与内容核对」 */
    private volatile boolean corruptCat;

    public FakeKubo() throws IOException {
        // 放在构建目录 target/ 下，不写系统临时目录
        Path base = Path.of(System.getProperty("basedir", "."), "target");
        Files.createDirectories(base);
        dir = Files.createTempDirectory(base, "fake-kubo");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v0/add", safe(this::add));
        server.createContext("/api/v0/cat", safe(this::cat));
        server.createContext("/api/v0/block/stat", safe(this::blockStat));
        server.createContext("/api/v0/pin/add", safe(this::pinAdd));
        server.createContext("/api/v0/pin/rm", safe(this::pinRm));
        server.createContext("/api/v0/pin/ls", safe(this::pinLs));
        server.createContext("/api/v0/repo/gc", safe(this::gc));
        server.createContext("/api/v0/version", ex -> json(ex, 200, "{\"Version\":\"fake\"}"));
        server.setExecutor(executor);
        server.start();
    }

    /** 替身自身出错时打印并返回 500，不让 HttpServer 静默断开连接 */
    private static com.sun.net.httpserver.HttpHandler safe(com.sun.net.httpserver.HttpHandler h) {
        return ex -> {
            try {
                h.handle(ex);
            } catch (Throwable t) {
                t.printStackTrace();
                json(ex, 500, "{\"Message\":\"fake kubo error: " + t + "\",\"Code\":0,\"Type\":\"error\"}");
            }
        };
    }

    public String apiUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void corruptCat(boolean on) {
        corruptCat = on;
    }

    public int addCount() {
        return adds.get();
    }

    public boolean has(String cid) {
        return blobs.containsKey(cid);
    }

    public boolean pinned(String cid) {
        return pins.contains(cid);
    }

    public long sizeOf(String cid) throws IOException {
        return Files.size(blobs.get(cid));
    }

    public String sha256Of(String cid) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(blobs.get(cid))) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    /** 直接放入内容（模拟本系统上线前由别的途径存进 IPFS 的旧文件） */
    public String put(byte[] content, boolean pin) throws Exception {
        String cid = cidOf(hex(MessageDigest.getInstance("SHA-256").digest(content)));
        Path p = dir.resolve(cid);
        Files.write(p, content);
        blobs.put(cid, p);
        if (pin) {
            pins.add(cid);
        }
        return cid;
    }

    /** 与 `ipfs pin rm` + `ipfs repo gc` 等效 */
    public void unpinAndGc(String cid) throws IOException {
        pins.remove(cid);
        gcNow();
    }

    private void gcNow() throws IOException {
        for (String cid : blobs.keySet()) {
            if (!pins.contains(cid)) {
                Files.deleteIfExists(blobs.remove(cid));
            }
        }
    }

    // ---------------------------------------------------------------- 处理器

    private void add(HttpExchange ex) throws IOException {
        String ctype = ex.getRequestHeaders().getFirst("Content-Type");
        if (ctype == null || !ctype.startsWith("multipart/form-data; boundary=")) {
            json(ex, 400, "{\"Message\":\"file argument 'path' is required\",\"Code\":1,\"Type\":\"error\"}");
            return;
        }
        String boundary = ctype.substring("multipart/form-data; boundary=".length());
        Path tmp = Files.createTempFile(dir, "upload", ".part");
        try (InputStream in = ex.getRequestBody(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        long total = Files.size(tmp);
        long start;
        try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "r")) {
            byte[] head = new byte[(int) Math.min(8192, total)];
            raf.readFully(head);
            String h = new String(head, StandardCharsets.ISO_8859_1);
            int idx = h.indexOf("\r\n\r\n");
            if (!h.startsWith("--" + boundary) || idx < 0) {
                Files.delete(tmp);
                json(ex, 400, "{\"Message\":\"bad multipart\",\"Code\":1,\"Type\":\"error\"}");
                return;
            }
            start = idx + 4;
        }
        long end = total - ("\r\n--" + boundary + "--\r\n").length();
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException(e);
        }
        Path content = Files.createTempFile(dir, "content", ".bin");
        try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "r"); OutputStream out = Files.newOutputStream(content)) {
            raf.seek(start);
            byte[] buf = new byte[65536];
            long left = end - start;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    break;
                }
                md.update(buf, 0, n);
                out.write(buf, 0, n);
                left -= n;
            }
        }
        Files.delete(tmp);
        String cid = cidOf(hex(md.digest()));
        Path target = dir.resolve(cid);
        Files.move(content, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        blobs.put(cid, target);
        pins.add(cid);
        adds.incrementAndGet();
        json(ex, 200, "{\"Name\":\"file\",\"Hash\":\"" + cid + "\",\"Size\":\"" + (end - start) + "\"}");
    }

    private void cat(HttpExchange ex) throws IOException {
        String cid = arg(ex);
        Path p = cid == null ? null : blobs.get(cid);
        if (p == null) {
            missing(ex, cid);
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "text/plain");
        ex.getResponseHeaders().add("X-Stream-Output", "1");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody(); InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536];
            int n;
            boolean first = true;
            while ((n = in.read(buf)) != -1) {
                if (corruptCat && first) {
                    buf[0] ^= 0x01;
                }
                first = false;
                out.write(buf, 0, n);
            }
        }
    }

    private void blockStat(HttpExchange ex) throws IOException {
        String cid = arg(ex);
        Path p = cid == null ? null : blobs.get(cid);
        if (p == null) {
            missing(ex, cid);
            return;
        }
        json(ex, 200, "{\"Key\":\"" + cid + "\",\"Size\":" + Math.min(Files.size(p), 262144) + "}");
    }

    private void pinAdd(HttpExchange ex) throws IOException {
        String cid = arg(ex);
        if (cid == null || !blobs.containsKey(cid)) {
            missing(ex, cid);
            return;
        }
        pins.add(cid);
        json(ex, 200, "{\"Pins\":[\"" + cid + "\"]}");
    }

    private void pinRm(HttpExchange ex) throws IOException {
        String cid = arg(ex);
        if (cid != null && pins.remove(cid)) {
            json(ex, 200, "{\"Pins\":[\"" + cid + "\"]}");
        } else {
            json(ex, 500, "{\"Message\":\"not pinned or pinned indirectly\",\"Code\":0,\"Type\":\"error\"}");
        }
    }

    private void pinLs(HttpExchange ex) throws IOException {
        String cid = arg(ex);
        if (cid != null && pins.contains(cid)) {
            json(ex, 200, "{\"Keys\":{\"" + cid + "\":{\"Type\":\"recursive\",\"Name\":\"\"}}}");
        } else {
            json(ex, 500, "{\"Message\":\"path '/ipfs/" + cid + "' is not pinned\",\"Code\":0,\"Type\":\"error\"}");
        }
    }

    private void gc(HttpExchange ex) throws IOException {
        gcNow();
        json(ex, 200, "");
    }

    private static void missing(HttpExchange ex, String cid) throws IOException {
        json(ex, 500, "{\"Message\":\"block was not found locally (offline): ipld: could not find " + cid + "\",\"Code\":0,\"Type\":\"error\"}");
    }

    private static String arg(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String kv : q.split("&")) {
            if (kv.startsWith("arg=")) {
                return URLDecoder.decode(kv.substring(4), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        // 请求体可能还没读完（如 pin/rm），先读掉；已读完并关闭的忽略
        try {
            ex.getRequestBody().readAllBytes();
        } catch (IOException ignored) {
            // 已关闭
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String cidOf(String sha) {
        return "QmFake" + sha.substring(0, 40);
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            for (Path p : blobs.values()) {
                Files.deleteIfExists(p);
            }
            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                s.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // 临时目录清理失败不影响测试结果
        }
    }
}
