package com.qhx.back.chain;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONArray;

import java.util.List;

/**
 * 交易参数摘要：每个参数转成字符串后组成 JSON 数组再取 sha256。
 * 数值统一按十进制字符串比较，所以后端 DTO 的 Long 10 与链上读回的 10 摘要相同。
 */
public final class ParamsDigest {

    private ParamsDigest() {
    }

    public static String of(List<?> params) {
        JSONArray normalized = new JSONArray();
        for (Object p : params) {
            normalized.add(p == null ? "" : String.valueOf(p));
        }
        return DigestUtil.sha256Hex(normalized.toString());
    }
}
