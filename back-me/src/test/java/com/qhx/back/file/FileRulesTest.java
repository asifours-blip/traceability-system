package com.qhx.back.file;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文件名清洗与按内容判定类型 */
class FileRulesTest {

    @Test
    void 文件名清洗_去掉路径_控制字符_保留字符_开头的点() {
        assertEquals("evilx.png", FileNames.sanitize("../../C:\\evil\"<x>.png"));
        assertEquals("报告 2026.pdf", FileNames.sanitize("  报告\t\t2026.pdf\u0000 "));
        assertEquals("passwd", FileNames.sanitize("/etc/.passwd"));
        assertEquals("file", FileNames.sanitize("..."));
        assertEquals("file", FileNames.sanitize(null));
        assertEquals("ab.png", FileNames.sanitize("a\u202eb.png"));
        String longName = FileNames.sanitize("a".repeat(300) + ".jpeg");
        assertEquals(FileNames.MAX_LENGTH, longName.length());
        assertTrue(longName.endsWith(".jpeg"));
        assertEquals("_.pdf", FileNames.asciiFallback("报.pdf"));
    }

    @Test
    void 类型按文件头判定_不看扩展名() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0};
        assertEquals(FileType.PNG, FileType.sniff(png, png.length).get());
        byte[] jpg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};
        assertEquals(FileType.JPEG, FileType.sniff(jpg, jpg.length).get());
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(FileType.WEBP, FileType.sniff(webp, webp.length).get());
        byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
        assertEquals(FileType.PDF, FileType.sniff(pdf, pdf.length).get());
        for (String bad : new String[]{"<html><script>", "GIF89a....", "<svg xmlns=", "MZ\u0090\u0000", "PK\u0003\u0004"}) {
            byte[] b = bad.getBytes(StandardCharsets.ISO_8859_1);
            assertFalse(FileType.sniff(b, b.length).isPresent(), bad);
        }
        // 文件头被截断（只有 4 字节）不能判成 PNG
        assertFalse(FileType.sniff(png, 4).isPresent());
        assertTrue(FileType.JPEG.matchesExtension("JPG"));
        assertFalse(FileType.PNG.matchesExtension("pdf"));
    }
}
