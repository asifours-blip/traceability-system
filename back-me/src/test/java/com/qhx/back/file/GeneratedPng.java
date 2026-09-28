package com.qhx.back.file;

import java.io.InputStream;

/**
 * 按需生成的「大 PNG」：PNG 文件头 + 伪随机字节，任意大小都不占内存；同样的 size/seed 每次生成完全相同的内容。
 * 用于证明上传链路是流式的。
 */
public final class GeneratedPng extends InputStream {

    private static final byte[] HEAD = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};

    private final long size;
    private long pos;
    private long state;

    public GeneratedPng(long size, long seed) {
        this.size = size;
        this.state = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
    }

    private byte next() {
        if (pos < HEAD.length) {
            return HEAD[(int) pos];
        }
        // xorshift64
        state ^= state << 13;
        state ^= state >>> 7;
        state ^= state << 17;
        return (byte) state;
    }

    @Override
    public int read() {
        if (pos >= size) {
            return -1;
        }
        byte b = next();
        pos++;
        return b & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        if (pos >= size) {
            return -1;
        }
        int n = (int) Math.min(len, size - pos);
        for (int i = 0; i < n; i++) {
            b[off + i] = next();
            pos++;
        }
        return n;
    }
}
