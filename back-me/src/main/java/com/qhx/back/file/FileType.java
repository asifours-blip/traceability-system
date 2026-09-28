package com.qhx.back.file;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 允许上传的文件类型白名单。类型只按文件头（magic number）判定，扩展名只用来和内容核对，不能决定类型。
 * 生产认证、质检报告在业务上是扫描件或照片，所以只放行常见图片与 PDF；GIF / SVG / HTML 等一律拒绝
 * （SVG、HTML 可以携带脚本）。
 */
public enum FileType {
    PNG("image/png", true, "png"),
    JPEG("image/jpeg", true, "jpg", "jpeg"),
    WEBP("image/webp", true, "webp"),
    PDF("application/pdf", false, "pdf");

    /** 判定类型需要读的文件头长度 */
    public static final int HEAD_BYTES = 16;

    public final String mime;
    /** 图片可以内联展示；其他类型公开读取时一律按附件下载 */
    public final boolean image;
    public final List<String> extensions;

    FileType(String mime, boolean image, String... extensions) {
        this.mime = mime;
        this.image = image;
        this.extensions = Arrays.asList(extensions);
    }

    public String defaultExtension() {
        return extensions.get(0);
    }

    /** 按文件头识别；不在白名单内返回 empty */
    public static Optional<FileType> sniff(byte[] b, int len) {
        if (len >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0d && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a) {
            return Optional.of(PNG);
        }
        if (len >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return Optional.of(JPEG);
        }
        if (len >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Optional.of(WEBP);
        }
        if (len >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return Optional.of(PDF);
        }
        return Optional.empty();
    }

    public static Optional<FileType> ofMime(String mime) {
        return Arrays.stream(values()).filter(t -> t.mime.equals(mime)).findFirst();
    }

    /** 扩展名（不含点，大小写不敏感）是否与该类型一致 */
    public boolean matchesExtension(String ext) {
        return ext != null && extensions.contains(ext.toLowerCase(Locale.ROOT));
    }
}
