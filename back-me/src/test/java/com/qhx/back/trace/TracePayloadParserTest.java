package com.qhx.back.trace;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracePayloadParserTest {

    @Test
    void 解析生产信息() {
        JSONArray raw = new JSONArray();
        raw.add("农场A");
        raw.add("苹果");
        raw.add("烟台");
        raw.add("红富士");
        raw.add("B001");
        raw.add("QmCidCert");
        raw.add("2026-01-01");
        raw.add(1700000000L);
        JSONObject parsed = TracePayloadParser.parseProducer("SY1", raw);
        assertEquals("SY1", parsed.getStr("traceNumber"));
        assertEquals("农场A", parsed.getStr("companyName"));
        assertEquals("苹果", parsed.getStr("productName"));
        assertEquals("QmCidCert", parsed.getStr("productionCert"));
        assertEquals(1700000000L, parsed.getLong("timestamp"));
    }

    @Test
    void 生产信息必须是8元组_否则视为未找到() {
        JSONArray revertLike = new JSONArray();
        revertLike.add("Trace:traceNumber is not exists!");
        assertNull(TracePayloadParser.parseProducer("SY-missing", revertLike));
        assertNull(TracePayloadParser.parseProducer("SY-missing", null));

        JSONArray seven = new JSONArray();
        for (int i = 0; i < 7; i++) {
            seven.add("x");
        }
        assertNull(TracePayloadParser.parseProducer("SY-short", seven));
    }

    @Test
    void 分销空公司名视为未录入() {
        JSONArray raw = new JSONArray();
        raw.add("");
        assertNull(TracePayloadParser.parseDistributor("SY1", raw));
    }

    @Test
    void 解析分销与零售() {
        JSONArray dist = new JSONArray();
        dist.add("仓配公司");
        dist.add("冷藏");
        dist.add("货车");
        dist.add("D01");
        dist.add("济南仓");
        dist.add(10L);
        dist.add(100L);
        dist.add("QmReport");
        dist.add(11L);
        JSONObject d = TracePayloadParser.parseDistributor("SY1", dist);
        assertEquals("仓配公司", d.getStr("companyName"));
        assertEquals("QmReport", d.getStr("inspectionReport"));
        assertEquals(10L, d.getLong("distributePrice"));

        JSONArray ret = new JSONArray();
        ret.add("门店");
        ret.add(20L);
        ret.add(5L);
        ret.add(7L);
        ret.add("INV-1");
        ret.add("2026-02-01");
        ret.add(12L);
        JSONObject r = TracePayloadParser.parseRetailer("SY1", ret);
        assertEquals("门店", r.getStr("companyName"));
        assertEquals(20L, r.getLong("salePrice"));
        assertEquals("INV-1", r.getStr("invoiceNo"));
    }

    @Test
    void 组装详情_缺环节填空对象() {
        JSONObject producer = new JSONObject();
        producer.set("companyName", "农场A");
        JSONObject detail = TracePayloadParser.assembleDetail("SY1", producer, null, null);
        assertEquals("SY1", detail.getStr("traceNumber"));
        assertEquals("农场A", detail.getJSONObject("producer").getStr("companyName"));
        assertTrue(detail.getJSONObject("distributor").isEmpty());
        assertTrue(detail.getJSONObject("retailer").isEmpty());
        assertNull(TracePayloadParser.assembleDetail("SY1", null, null, null));
        assertNotNull(detail);
    }
}
