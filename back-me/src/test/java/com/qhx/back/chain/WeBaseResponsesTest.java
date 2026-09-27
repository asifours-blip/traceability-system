package com.qhx.back.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 响应体取自本地真实链（FISCO BCOS 2.7.2 + WeBASE-Front v1.5.5）上的实际响应，
 * 只把 input / logsBloom 等与判定无关的长字段截短，字段结构与取值保持原样。
 */
class WeBaseResponsesTest {

    private static final String SUCCESS = "{\"transactionHash\":\"0xd46e73ad30e74c12e912eeeacc24592cae968519e6e2c21f8bbe57e1835a74e2\","
            + "\"transactionIndex\":\"0x0\",\"root\":\"0xa4831662c0fa60345afbc707a91720c8917629bf2eb79bf57b22007b1181c18a\","
            + "\"blockNumber\":\"3\",\"blockHash\":\"0x7200bb3f3458716f58ef3a6ff47c52568df9ad8d142c8c83963834ea511a253f\","
            + "\"from\":\"0x610202f6d5f86b43e76fa2e360530c49a99f252d\",\"to\":\"0x3d37f47620091952443a1df9c6b23a443e746beb\","
            + "\"gasUsed\":\"1998216\",\"remainGas\":null,\"contractAddress\":\"0x0000000000000000000000000000000000000000\","
            + "\"logs\":[],\"logsBloom\":\"0x00\",\"status\":\"0x0\",\"statusMsg\":\"None\",\"input\":\"0x0358bd37\","
            + "\"output\":\"0x0000000000000000000000001507c5db4ab618be36c18b2c005f8972dcb3d063\",\"txProof\":null,"
            + "\"receiptProof\":null,\"message\":\"Success\",\"statusOK\":true}";

    private static final String ALREADY_EXISTS_OUTPUT =
            "0x08c379a00000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000002154726163653a2074726163654e756d62657220616c72656164792065786973747300000000000000000000000000000000000000000000000000000000000000";

    private static final String REVERT = "{\"transactionHash\":\"0x7c7faf1b344b40f2dc8156e285a3837778a31dd4d214a47a525f168b50265fe9\","
            + "\"transactionIndex\":\"0x0\",\"root\":\"0x0000000000000000000000000000000000000000000000000000000000000000\","
            + "\"blockNumber\":\"4\",\"blockHash\":\"0xa2205c5e08d93ae00a26690544c6e2259c966e131cd29ea9b19838f2bd748f09\","
            + "\"from\":\"0x610202f6d5f86b43e76fa2e360530c49a99f252d\",\"to\":\"0x3d37f47620091952443a1df9c6b23a443e746beb\","
            + "\"gasUsed\":\"32038\",\"remainGas\":null,\"contractAddress\":\"0x0000000000000000000000000000000000000000\","
            + "\"logs\":[],\"logsBloom\":\"0x00\",\"status\":\"0x16\",\"statusMsg\":\"RevertInstruction\",\"input\":\"0x0358bd37\","
            + "\"output\":\"" + ALREADY_EXISTS_OUTPUT + "\",\"txProof\":null,\"receiptProof\":null,"
            + "\"message\":\"Trace: traceNumber already exists\",\"statusOK\":false}";

    private static final String RECEIPT_TIMEOUT = "{\"transactionHash\":null,\"transactionIndex\":null,\"root\":null,\"blockNumber\":\"0\","
            + "\"blockHash\":null,\"from\":null,\"to\":null,\"gasUsed\":\"0\",\"remainGas\":null,\"contractAddress\":null,"
            + "\"logs\":null,\"logsBloom\":null,\"status\":\"50001\",\"statusMsg\":null,\"input\":null,\"output\":null,"
            + "\"txProof\":null,\"receiptProof\":null,\"message\":\"Transaction receipt timeout\",\"statusOK\":false}";

    @Test
    void 成功回执_CONFIRMED_块高按十进制解析() {
        TxOutcome o = WeBaseResponses.classifyTransHandle(200, SUCCESS);
        assertEquals(TxOutcome.Kind.CONFIRMED, o.getKind());
        assertEquals("0xd46e73ad30e74c12e912eeeacc24592cae968519e6e2c21f8bbe57e1835a74e2", o.getTxHash());
        assertEquals(3L, o.getBlockNumber());
        assertEquals("0x0", o.getReceiptStatus());
    }

    @Test
    void revert回执_HTTP200但REVERTED_原因从output解码() {
        TxOutcome o = WeBaseResponses.classifyTransHandle(200, REVERT);
        assertEquals(TxOutcome.Kind.REVERTED, o.getKind());
        assertEquals(4L, o.getBlockNumber());
        assertEquals("0x16", o.getReceiptStatus());
        assertEquals("Trace: traceNumber already exists", o.getReason());
    }

    @Test
    void revert原因解码_真实output() {
        assertEquals("Trace: traceNumber already exists", WeBaseResponses.decodeRevertReason(ALREADY_EXISTS_OUTPUT));
        String role = "0x08c379a00000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000003450726f6475636572526f6c653a2063616c6c657220646f6573206e6f742068617665207468652050726f647563657220726f6c65000000000000000000000000";
        assertEquals("ProducerRole: caller does not have the Producer role", WeBaseResponses.decodeRevertReason(role));
        assertNull(WeBaseResponses.decodeRevertReason("0x"));
        assertNull(WeBaseResponses.decodeRevertReason("0x08c379a000"));
    }

    @Test
    void output不是Error编码时_回退到message() {
        String body = REVERT.replace(ALREADY_EXISTS_OUTPUT, "0x");
        TxOutcome o = WeBaseResponses.classifyTransHandle(200, body);
        assertEquals(TxOutcome.Kind.REVERTED, o.getKind());
        assertEquals("Trace: traceNumber already exists", o.getReason());
    }

    @Test
    void WeBASE回执超时50001_没有哈希_UNKNOWN而不是失败() {
        TxOutcome o = WeBaseResponses.classifyTransHandle(200, RECEIPT_TIMEOUT);
        assertEquals(TxOutcome.Kind.UNKNOWN, o.getKind());
        assertNull(o.getTxHash());
        assertEquals("50001", o.getReceiptStatus());
    }

    @Test
    void 签名用户不存在_422_201015_REJECTED() {
        TxOutcome o = WeBaseResponses.classifyTransHandle(422,
                "{\"code\":201015,\"data\":null,\"errorMessage\":\"user's privateKey is null\"}");
        assertEquals(TxOutcome.Kind.REJECTED, o.getKind());
        assertEquals(201015, o.getFrontCode());
        assertEquals("user's privateKey is null", o.getReason());
    }

    @Test
    void 参数错误_422_201151_REJECTED() {
        TxOutcome o = WeBaseResponses.classifyTransHandle(422, "{\"code\":201151,\"data\":null,\"errorMessage\":"
                + "\" cannot encode in encodeMethodFromString with appropriate interface ABI, make sure params match\"}");
        assertEquals(TxOutcome.Kind.REJECTED, o.getKind());
        assertEquals(201151, o.getFrontCode());
    }

    @Test
    void 未核验过的422错误码_无法确认是否发出_UNKNOWN() {
        TxOutcome o = WeBaseResponses.classifyTransHandle(422, "{\"code\":201999,\"data\":null,\"errorMessage\":\"x\"}");
        assertEquals(TxOutcome.Kind.UNKNOWN, o.getKind());
    }

    @Test
    void HTTP5xx_UNKNOWN() {
        assertEquals(TxOutcome.Kind.UNKNOWN,
                WeBaseResponses.classifyTransHandle(500, "{\"code\":500,\"errorMessage\":null}").getKind());
        assertEquals(TxOutcome.Kind.UNKNOWN, WeBaseResponses.classifyTransHandle(502, "<html>").getKind());
    }

    @Test
    void 坏JSON或半截JSON_UNKNOWN() {
        assertEquals(TxOutcome.Kind.UNKNOWN, WeBaseResponses.classifyTransHandle(200, "not-json").getKind());
        assertEquals(TxOutcome.Kind.UNKNOWN, WeBaseResponses.classifyTransHandle(200, SUCCESS.substring(0, 80)).getKind());
        assertEquals(TxOutcome.Kind.UNKNOWN, WeBaseResponses.classifyTransHandle(200, "").getKind());
    }

    @Test
    void 有哈希但缺status_UNKNOWN且保留哈希() {
        String body = SUCCESS.replace("\"status\":\"0x0\",", "");
        TxOutcome o = WeBaseResponses.classifyTransHandle(200, body);
        assertEquals(TxOutcome.Kind.UNKNOWN, o.getKind());
        assertEquals("0xd46e73ad30e74c12e912eeeacc24592cae968519e6e2c21f8bbe57e1835a74e2", o.getTxHash());
    }

    @Test
    void 查回执_找到_与发交易的回执同构() {
        String hash = "0xd46e73ad30e74c12e912eeeacc24592cae968519e6e2c21f8bbe57e1835a74e2";
        assertEquals(TxOutcome.Kind.CONFIRMED, WeBaseResponses.classifyReceiptQuery(200, SUCCESS, hash).getKind());
    }

    @Test
    void 查回执_不存在的哈希返回500_UNKNOWN() {
        String hash = "0x" + "ab".repeat(32);
        TxOutcome o = WeBaseResponses.classifyReceiptQuery(500, "{\"code\":500,\"errorMessage\":null}", hash);
        assertEquals(TxOutcome.Kind.UNKNOWN, o.getKind());
        assertEquals(hash, o.getTxHash());
    }

    @Test
    void 查回执_哈希不一致_UNKNOWN() {
        TxOutcome o = WeBaseResponses.classifyReceiptQuery(200, SUCCESS, "0x" + "cd".repeat(32));
        assertEquals(TxOutcome.Kind.UNKNOWN, o.getKind());
    }

    @Test
    void revert映射成业务错误码() {
        assertEquals(409, ChainErrors.of(TxOutcome.reverted("h", 1L, "0x16", "Trace: distribution already recorded")).status);
        assertEquals(409, ChainErrors.of(TxOutcome.reverted("h", 1L, "0x16", "Trace: retail already recorded")).status);
        assertEquals(409, ChainErrors.of(TxOutcome.reverted("h", 1L, "0x16", "Trace: traceNumber already exists")).status);
        assertEquals(409, ChainErrors.of(TxOutcome.reverted("h", 1L, "0x16", "Trace: distribution not recorded yet")).status);
        assertEquals(404, ChainErrors.of(TxOutcome.reverted("h", 1L, "0x16", "Trace: traceNumber does not exist")).status);
        assertEquals(403, ChainErrors.of(TxOutcome.reverted("h", 1L, "0x16",
                "DistributorRole: caller does not have the Distributor role")).status);
        assertEquals(403, ChainErrors.of(TxOutcome.reverted("h", 1L, "0x16", "Ownable: caller is not the owner")).status);
        assertEquals(422, ChainErrors.of(TxOutcome.reverted("h", 1L, "0x16", "something else")).status);
        assertEquals(202, ChainErrors.of(TxOutcome.unknown(null, "50001", "timeout")).status);
        assertEquals(503, ChainErrors.of(TxOutcome.notSent("refused")).status);
        assertEquals(502, ChainErrors.of(TxOutcome.rejected(201015, "user's privateKey is null")).status);
        assertEquals(400, ChainErrors.of(TxOutcome.rejected(201151, "encode")).status);
    }
}
