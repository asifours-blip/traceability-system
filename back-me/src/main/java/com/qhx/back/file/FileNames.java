package com.qhx.back.file;

import java.text.Normalizer;

/**
 * 上传文件名清洗。文件名只用于展示和下载时的 Content-Disposition，存储与读取都按 CID，不拼路径。
 */
public final class FileNames {

    public static final int MAX_LENGTH = 100;

    private FileNames() {
    }

    /**
     * 去掉路径部分（浏览器可能带 C:\fakepath\ 或 ../）、控制字符、Windows 保留字符与引号，折叠空白，
     * 去掉开头的点（隐藏文件 / ..），限制长度并保留扩展名；清洗后为空则用 "file"。
     */
    public static String sanitize(String original) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = Normalizer.normalize(name, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            // 制表符、换行等空白先折成空格，其余控制字符去掉
            if (Character.isWhitespace(cp)) {
                sb.append(' ');
                continue;
            }
            if (Character.isISOControl(cp) || "<>:\"/\\|?*;".indexOf(cp) >= 0
                    || Character.getType(cp) == Character.FORMAT || Character.getType(cp) == Character.PRIVATE_USE) {
                continue;
            }
            sb.appendCodePoint(cp);
        }
        name = sb.toString().replaceAll(" {2,}", " ").trim();
        while (name.startsWith(".")) {
            name = name.substring(1).trim();
        }
        if (name.length() > MAX_LENGTH) {
            String ext = extension(name);
            String keep = ext == null || ext.length() > 10 ? "" : "." + ext;
            name = name.substring(0, MAX_LENGTH - keep.length()).trim() + keep;
        }
        return name.isEmpty() ? "file" : name;
    }

    /** 扩展名（不含点），没有则 null */
    public static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1);
    }

    /** 下载用的 ASCII 兜底文件名：非 ASCII 字符替换成下划线（完整名字放在 filename* 里） */
    public static String asciiFallback(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '_');
        }
        return sb.toString();
    }
}
