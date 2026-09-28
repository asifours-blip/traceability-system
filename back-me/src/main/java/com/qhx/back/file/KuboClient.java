package com.qhx.back.file;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * kubo（go-ipfs）HTTP RPC 的最小客户端，只用 JDK 的 HttpURLConnection，全程流式：
 * 上传用分块传输（chunked）边读边发，读取直接返回响应流，不在内存里拼整个文件。
 * 响应结构按 kubo 0.29.0 实测（docs/files.md「kubo 接口核验」）：
 * <ul>
 *   <li>POST /api/v0/add → 200 {"Name","Hash","Size"}</li>
 *   <li>POST /api/v0/cat?offline=true → 200 原始字节；本地没有该块 → 500 {"Message":"block was not found locally (offline): ipld: could not find …"}</li>
 *   <li>POST /api/v0/pin/rm → 200 {"Pins":[…]}；未 pin → 500 {"Message":"not pinned or pinned indirectly"}</li>
 * </ul>
 * 所有读操作都带 offline=true：即使节点没用 --offline 启动，也不会为找一个缺失的块去连公网。
 */
public class KuboClient {

    private static final int BUFFER = 64 * 1024;

    private final String apiBase;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    /** apiUrl 形如 http://127.0.0.1:5201 */
    public KuboClient(String apiUrl, int connectTimeoutMs, int readTimeoutMs) {
        this.apiBase = apiUrl.replaceAll("/+$", "") + "/api/v0/";
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** 流式上传并 pin，返回 CID（v0，Qm 开头）。content 由调用方关闭。 */
    public String add(InputStream content) {
        String boundary = "----trace" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection conn = open("add?pin=true&cid-version=0&progress=false&quieter=true");
        conn.setDoOutput(true);
        // 分块传输：请求体边写边发，不会被 HttpURLConnection 缓冲成整块
        conn.setChunkedStreamingMode(BUFFER);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"file\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = content.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            }
            JSONObject body = JSONUtil.parseObj(readOk(conn));
            String hash = body.getStr("Hash");
            if (hash == null || hash.isEmpty()) {
                throw new IpfsException(IpfsException.Kind.ERROR, "kubo add 的响应里没有 Hash：" + body);
            }
            return hash;
        } catch (IOException e) {
            throw new IpfsException(IpfsException.Kind.UNAVAILABLE, "上传到 IPFS 失败：" + e.getMessage(), e);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 读取文件内容。返回的流由调用方关闭；本地没有该 CID 时抛 MISSING（在返回之前就判定，不会给出空流）。
     */
    public InputStream cat(String cid) {
        HttpURLConnection conn = open("cat?offline=true&arg=" + enc(cid));
        try {
            conn.setDoOutput(true);
            conn.getOutputStream().close();
            int status = conn.getResponseCode();
            if (status != 200) {
                throw error(status, readError(conn));
            }
            InputStream in = conn.getInputStream();
            return new FilterInputStream(in) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        conn.disconnect();
                    }
                }
            };
        } catch (IOException e) {
            conn.disconnect();
            throw new IpfsException(IpfsException.Kind.UNAVAILABLE, "读取 IPFS 失败：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            conn.disconnect();
            throw e;
        }
    }

    /** 本节点上是否有该 CID 的根块（block/stat，离线）；节点不可用时抛 UNAVAILABLE */
    public boolean has(String cid) {
        try {
            call("block/stat?offline=true&arg=" + enc(cid));
            return true;
        } catch (IpfsException e) {
            if (e.getKind() == IpfsException.Kind.MISSING) {
                return false;
            }
            throw e;
        }
    }

    /** 递归 pin（已有内容才能 pin 上；离线模式下缺块会报 MISSING） */
    public void pin(String cid) {
        call("pin/add?offline=true&recursive=true&arg=" + enc(cid));
    }

    /** 取消 pin；本来就没 pin 视为成功（幂等） */
    public void unpin(String cid) {
        try {
            call("pin/rm?recursive=true&arg=" + enc(cid));
        } catch (IpfsException e) {
            if (e.getKind() == IpfsException.Kind.ERROR && e.getMessage() != null && e.getMessage().contains("not pinned")) {
                return;
            }
            throw e;
        }
    }

    public boolean isPinned(String cid) {
        try {
            String body = call("pin/ls?type=recursive&arg=" + enc(cid));
            return body.contains(cid);
        } catch (IpfsException e) {
            if (e.getKind() == IpfsException.Kind.ERROR && e.getMessage() != null && e.getMessage().contains("is not pinned")) {
                return false;
            }
            throw e;
        }
    }

    public String version() {
        return JSONUtil.parseObj(call("version")).getStr("Version");
    }

    // ---------------------------------------------------------------- 内部

    private String call(String pathAndQuery) {
        HttpURLConnection conn = open(pathAndQuery);
        try {
            conn.setDoOutput(true);
            conn.getOutputStream().close();
            return readOk(conn);
        } catch (IOException e) {
            throw new IpfsException(IpfsException.Kind.UNAVAILABLE, "调用 IPFS 失败：" + e.getMessage(), e);
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection open(String pathAndQuery) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(apiBase + pathAndQuery).openConnection();
            // kubo 的 RPC 只接受 POST
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setUseCaches(false);
            return conn;
        } catch (IOException e) {
            throw new IpfsException(IpfsException.Kind.UNAVAILABLE, "无法连接 IPFS：" + e.getMessage(), e);
        }
    }

    private static String readOk(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        if (status != 200) {
            throw error(status, readError(conn));
        }
        try (InputStream in = conn.getInputStream()) {
            return readLimited(in, 1 << 20);
        }
    }

    private static String readError(HttpURLConnection conn) throws IOException {
        InputStream err = conn.getErrorStream();
        if (err == null) {
            return "";
        }
        try (InputStream in = err) {
            return readLimited(in, 8192);
        }
    }

    private static String readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1 && out.size() < limit) {
            out.write(buf, 0, Math.min(n, limit - out.size()));
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /** kubo 的错误体是 {"Message","Code","Type":"error"}；按 Message 区分「块不存在」 */
    static IpfsException error(int status, String body) {
        String message = body;
        try {
            if (body != null && body.trim().startsWith("{")) {
                message = JSONUtil.parseObj(body).getStr("Message", body);
            }
        } catch (RuntimeException ignored) {
            // 非 JSON，原样使用
        }
        String text = "kubo HTTP " + status + "：" + message;
        if (message != null && (message.contains("not found locally") || message.contains("could not find"))) {
            return new IpfsException(IpfsException.Kind.MISSING, text);
        }
        return new IpfsException(status >= 500 && (message == null || message.isEmpty())
                ? IpfsException.Kind.UNAVAILABLE : IpfsException.Kind.ERROR, text);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
