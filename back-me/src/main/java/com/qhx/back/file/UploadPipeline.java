package com.qhx.back.file;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 上传核心流程，全程流式，内存占用与文件大小无关（只有固定大小的缓冲区）：
 * <ol>
 *   <li>检查：读一遍来源流，计字节数（超过上限立即停止）、算 SHA-256、取文件头按内容判定类型，并与扩展名核对；
 *       不合格的文件不会写进 IPFS；</li>
 *   <li>写入：重新打开来源流，分块发给 kubo add（pin=true），同时再算一次 SHA-256，防止两次读取之间文件被换掉；</li>
 *   <li>核对：用拿到的 CID 从 kubo cat 回来，逐字节算 SHA-256 与长度，必须与第 1 步一致，才算上传成功。</li>
 * </ol>
 * 来源必须可以重复打开（Spring 的 MultipartFile 超过阈值即落盘，getInputStream 每次从临时文件重新读）。
 * 不依赖 Spring，便于在独立 JVM 里做内存上限测试。
 */
public class UploadPipeline {

    /** 可重复打开的来源 */
    public interface Source {
        InputStream open() throws IOException;
    }

    public static final class Stored {
        public final String cid;
        public final String sha256;
        public final long size;
        public final FileType type;
        public final String fileName;

        Stored(String cid, String sha256, long size, FileType type, String fileName) {
            this.cid = cid;
            this.sha256 = sha256;
            this.size = size;
            this.type = type;
            this.fileName = fileName;
        }
    }

    private static final int BUFFER = 64 * 1024;

    private final KuboClient kubo;
    private final long maxBytes;

    public UploadPipeline(KuboClient kubo, long maxBytes) {
        this.kubo = kubo;
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    /**
     * @param originalName 客户端给的文件名（会清洗；扩展名必须与内容一致，没有扩展名则按内容补上）
     */
    public Stored store(Source source, String originalName) {
        String name = FileNames.sanitize(originalName);

        // 1. 检查
        byte[] head = new byte[FileType.HEAD_BYTES];
        int headLen = 0;
        long size = 0;
        MessageDigest digest = sha256();
        try (InputStream in = source.open()) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (headLen < head.length) {
                    int take = Math.min(n, head.length - headLen);
                    System.arraycopy(buf, 0, head, headLen, take);
                    headLen += take;
                }
                size += n;
                if (size > maxBytes) {
                    throw new FileRejectedException(413, "FILE_TOO_LARGE", "文件超过大小上限 " + human(maxBytes));
                }
                digest.update(buf, 0, n);
            }
        } catch (IOException e) {
            throw new FileRejectedException(400, "FILE_UNREADABLE", "读取上传文件失败：" + e.getMessage());
        }
        if (size == 0) {
            throw new FileRejectedException(400, "FILE_EMPTY", "文件为空");
        }
        final int sniffedLen = headLen;
        FileType type = FileType.sniff(head, sniffedLen).orElseThrow(() -> new FileRejectedException(415, "FILE_TYPE_NOT_ALLOWED",
                "不支持的文件类型：只接受 PNG、JPEG、WEBP 图片或 PDF（按文件内容判定，与扩展名无关）"));
        String ext = FileNames.extension(name);
        if (ext == null) {
            name = FileNames.sanitize(name + "." + type.defaultExtension());
        } else if (!type.matchesExtension(ext)) {
            throw new FileRejectedException(415, "FILE_EXTENSION_MISMATCH",
                    "扩展名 ." + ext + " 与文件内容（" + type.mime + "）不符");
        }
        String sha = hex(digest.digest());

        // 2. 写入
        String cid;
        MessageDigest sent = sha256();
        long[] sentBytes = {0};
        try (InputStream in = new CountingStream(new DigestInputStream(source.open(), sent), sentBytes)) {
            cid = kubo.add(in);
        } catch (IOException e) {
            throw new FileRejectedException(400, "FILE_UNREADABLE", "读取上传文件失败：" + e.getMessage());
        } catch (IpfsException e) {
            throw new FileRejectedException(503, "IPFS_UNAVAILABLE", "文件存储服务不可用，请稍后重试（" + e.getMessage() + "）");
        }
        if (sentBytes[0] != size || !hex(sent.digest()).equals(sha)) {
            throw new FileRejectedException(409, "FILE_CHANGED", "上传过程中文件内容发生变化，请重新上传", cid, null);
        }

        // 3. 核对：CID 对应的内容必须就是刚才检查过的那份
        MessageDigest back = sha256();
        long backBytes = 0;
        try (InputStream in = kubo.cat(cid)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                backBytes += n;
                if (backBytes > size) {
                    break;
                }
                back.update(buf, 0, n);
            }
        } catch (IOException | IpfsException e) {
            throw new FileRejectedException(502, "IPFS_VERIFY_FAILED", "已写入 IPFS，但读回核对失败：" + e.getMessage(), cid, e);
        }
        if (backBytes != size || !hex(back.digest()).equals(sha)) {
            throw new FileRejectedException(502, "IPFS_VERIFY_FAILED",
                    "IPFS 返回的 CID 与上传内容不一致（长度或 SHA-256 不符），已放弃本次上传", cid, null);
        }
        return new Stored(cid, sha, size, type, name);
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static String human(long bytes) {
        return bytes % (1024 * 1024) == 0 ? bytes / (1024 * 1024) + " MB" : bytes + " 字节";
    }

    /** 统计实际读出的字节数 */
    private static final class CountingStream extends FilterInputStream {
        private final long[] counter;

        CountingStream(InputStream in, long[] counter) {
            super(in);
            this.counter = counter;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                counter[0]++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                counter[0] += n;
            }
            return n;
        }
    }
}
