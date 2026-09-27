package com.qhx.back.chain;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 按实测的 WeBASE-Front v1.5.5 契约解析 /trans/handle 与 /{groupId}/web3/transactionReceipt/{hash} 的响应。
 * 纯函数，无 HTTP。每条规则的依据见 docs/webase-front-contract.md。
 */
public final class WeBaseResponses {

    /** Error(string) 的函数选择器：revert("...") 的 output 以它开头 */
    private static final String ERROR_SELECTOR = "08c379a0";
    private static final Pattern TX_HASH = Pattern.compile("^0x[0-9a-fA-F]{64}$");

    /**
     * 实测确认在签名/发送之前就失败的 WeBASE-Front 错误码（HTTP 422）：
     * 201015 user's privateKey is null；201151 参数无法按 ABI 编码 / 函数不存在。
     * 其他 422 错误码无法确认交易是否已发出，一律按 UNKNOWN 处理。
     */
    static final Set<Integer> PRE_SEND_CODES = Set.of(201015, 201151);

    private WeBaseResponses() {
    }

    /** 解析 POST /trans/handle 对非 constant 函数的响应。 */
    public static TxOutcome classifyTransHandle(int httpStatus, String body) {
        if (httpStatus == 200) {
            return classifyReceiptBody(body, null);
        }
        if (httpStatus == 422) {
            JSONObject err = parseObject(body);
            Integer code = err == null ? null : err.getInt("code");
            String message = err == null ? StrUtil.maxLength(body, 200) : err.getStr("errorMessage");
            if (code != null && PRE_SEND_CODES.contains(code)) {
                return TxOutcome.rejected(code, message);
            }
            return TxOutcome.unknown(null, null, "WeBASE-Front 返回 422（code=" + code + "，" + message + "），无法确认交易是否已发出");
        }
        if (httpStatus >= 400 && httpStatus < 500) {
            // 400 参数绑定失败、404 路径不对等：请求在进入发交易逻辑之前就被拒绝
            JSONObject err = parseObject(body);
            String message = err == null ? StrUtil.maxLength(body, 200) : err.getStr("errorMessage");
            return TxOutcome.rejected(err == null ? null : err.getInt("code"), "WeBASE-Front 返回 HTTP " + httpStatus + "：" + message);
        }
        return TxOutcome.unknown(null, null, "WeBASE-Front 返回 HTTP " + httpStatus + "，无法确认交易是否已发出");
    }

    /**
     * 解析 GET /{groupId}/web3/transactionReceipt/{hash}。
     * 查不到回执时 v1.5.5 返回 HTTP 500 {"code":500,"errorMessage":null}（内部空指针），与其他服务端错误无法区分，统一视为「暂时拿不到回执」。
     */
    public static TxOutcome classifyReceiptQuery(int httpStatus, String body, String expectedHash) {
        if (httpStatus != 200) {
            return TxOutcome.unknown(expectedHash, null, "WeBASE-Front 查回执返回 HTTP " + httpStatus + "（查不到回执或服务端错误）");
        }
        if (StrUtil.isBlank(body)) {
            return TxOutcome.unknown(expectedHash, null, "WeBASE-Front 查回执返回空响应");
        }
        TxOutcome outcome = classifyReceiptBody(body, expectedHash);
        if (outcome.getTxHash() != null && !outcome.getTxHash().equalsIgnoreCase(expectedHash)) {
            return TxOutcome.unknown(expectedHash, null, "回执中的交易哈希与查询的哈希不一致");
        }
        return outcome;
    }

    private static TxOutcome classifyReceiptBody(String body, String fallbackHash) {
        JSONObject receipt = parseObject(body);
        if (receipt == null) {
            return TxOutcome.unknown(fallbackHash, null, "WeBASE-Front 响应不是 JSON 对象：" + StrUtil.maxLength(body, 120));
        }
        String hash = receipt.getStr("transactionHash");
        String status = receipt.getStr("status");
        String message = receipt.getStr("message");
        if (hash == null || !TX_HASH.matcher(hash).matches()) {
            // 实测：共识停滞时 WeBASE 等满 transMaxWait 后返回 status=50001、transactionHash=null、message=Transaction receipt timeout，
            // 这笔交易之后仍可能被打包，不能当作失败
            return TxOutcome.unknown(fallbackHash, status,
                    "WeBASE-Front 未返回交易哈希（status=" + status + "，" + message + "）");
        }
        Long block = parseBlockNumber(receipt.getStr("blockNumber"));
        if (status == null || block == null || block <= 0) {
            return TxOutcome.unknown(hash, status, "回执缺少 status 或块高，无法判定");
        }
        if ("0x0".equalsIgnoreCase(status)) {
            if (Boolean.TRUE.equals(receipt.getBool("statusOK"))) {
                return TxOutcome.confirmed(hash, block, status);
            }
            return TxOutcome.unknown(hash, status, "回执 status=0x0 但 statusOK 不为 true，无法判定");
        }
        String reason = decodeRevertReason(receipt.getStr("output"));
        if (StrUtil.isBlank(reason)) {
            reason = StrUtil.isNotBlank(message) ? message : "回执 status=" + status + "（" + receipt.getStr("statusMsg") + "）";
        }
        return TxOutcome.reverted(hash, block, status, reason);
    }

    /** 解码 revert("...") 产生的 Error(string) ABI 编码；不是这种格式时返回 null。 */
    public static String decodeRevertReason(String output) {
        if (output == null) {
            return null;
        }
        String hex = output.startsWith("0x") || output.startsWith("0X") ? output.substring(2) : output;
        if (!hex.toLowerCase().startsWith(ERROR_SELECTOR) || hex.length() < 8 + 128) {
            return null;
        }
        try {
            String data = hex.substring(8);
            int offset = new BigInteger(data.substring(0, 64), 16).intValueExact();
            int lenStart = offset * 2;
            int length = new BigInteger(data.substring(lenStart, lenStart + 64), 16).intValueExact();
            int strStart = lenStart + 64;
            if (length < 0 || strStart + length * 2 > data.length()) {
                return null;
            }
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                bytes[i] = (byte) Integer.parseInt(data.substring(strStart + i * 2, strStart + i * 2 + 2), 16);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 实测 /trans/handle 返回十进制字符串（如 "3"），SDK 内部日志里是 0x 十六进制；两种都接受。 */
    static Long parseBlockNumber(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return Long.parseLong(value.substring(2), 16);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JSONObject parseObject(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return JSONUtil.parseObj(trimmed);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
