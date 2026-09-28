package com.qhx.back.trace;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.qhx.back.chain.TxOutcome;
import com.qhx.back.client.WeBaseClient;
import com.qhx.back.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * getAgroFoodList 的解析，输入逐字取自本地真实链（docs/artifacts/webase-string-array-2026-09-28.json）。
 */
class ChainTraceReaderListTest {

    private static ChainTraceReader reader(String raw) {
        return new ChainTraceReader(new WeBaseClient() {
            public JSONArray call(String funcName) {
                return call(funcName, Collections.emptyList());
            }

            public JSONArray call(String funcName, List<Object> params) {
                return JSONUtil.parseArray(raw);
            }

            public TxOutcome sendTransaction(String funcName, List<Object> params) {
                throw new UnsupportedOperationException();
            }

            public TxOutcome queryReceipt(String txHash) {
                throw new UnsupportedOperationException();
            }
        });
    }

    @Test
    void 实测结构_单元素数组里是JSON数组字符串() {
        assertEquals(Collections.emptyList(), reader("[\"[ ]\"]").list());
        assertEquals(Collections.singletonList("LIST-115239"), reader("[\"[ \\\"LIST-115239\\\" ]\"]").list());
        assertEquals(Arrays.asList("LIST-115239", "LIST-q\"x, ]y\\z 中"),
                reader("[\"[ \\\"LIST-115239\\\", \\\"LIST-q\\\\\\\"x, ]y\\\\\\\\z 中\\\" ]\"]").list());
    }

    @Test
    void 无法解析或调用失败_抛错不返回空列表() {
        assertEquals(502, assertThrows(BusinessException.class, () -> reader("[\"not json\"]").list()).getStatus());
        assertEquals(502, assertThrows(BusinessException.class, () -> reader("[\"a\",\"b\"]").list()).getStatus());
        assertEquals(502, assertThrows(BusinessException.class,
                () -> reader("[\"Call contract return error: boom\"]").list()).getStatus());
    }
}
