package com.qhx.back.trace;

import com.qhx.back.chain.TraceStage;
import com.qhx.back.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceValidatorTest {

    @Test
    void 溯源号格式_兼容已有样例_拒绝小写空格斜杠() {
        for (String ok : List.of("SY60202600001", "LC-3", "E2E-20260928", "SY20260928001")) {
            assertTrue(TraceValidator.isTraceNumber(ok), ok);
        }
        for (String bad : List.of("sy001", "SY 001", "SY/001", "S1", "1SY001", "-SY001", "")) {
            assertFalse(TraceValidator.isTraceNumber(bad), bad);
        }
    }

    @Test
    void 正整数_拒绝零负数小数与非数字() {
        assertOk(v -> v.positiveInt("n", new BigDecimal("10")));
        assertOk(v -> v.positiveInt("n", new BigDecimal("10.00")));
        assertOk(v -> v.positiveInt("n", "7"));
        for (Object bad : List.of(BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("1.5"), "abc", "", new BigDecimal("1000000000001"))) {
            assertError("n", v -> v.positiveInt("n", bad));
        }
        assertError("n", v -> v.positiveInt("n", null));
    }

    @Test
    void 日期_严格格式_不能晚于今天_不能早于上一阶段() {
        assertOk(v -> v.date("d", "2026-01-31"));
        for (String bad : List.of("2026-02-30", "2026-1-5", "2026/01/05", "20260105")) {
            assertError("d", v -> v.date("d", bad));
        }
        assertError("d", v -> v.date("d", LocalDate.now(TraceValidator.ZONE).plusDays(2).toString()));
        assertError("saleTime", v -> v.notBefore("saleTime", "2026-01-01", "生产日期", "2026-01-02"));
        assertOk(v -> v.notBefore("saleTime", "2026-01-02", "生产日期", "2026-01-02"));
    }

    @Test
    void 数量不能超过上一阶段() {
        assertError("saleQuantity", v -> v.notMoreThan("saleQuantity", new BigDecimal("101"), "分销数量", 100));
        assertOk(v -> v.notMoreThan("saleQuantity", new BigDecimal("100"), "分销数量", 100));
    }

    @Test
    void 按字段清单校验一个阶段_收集全部错误() {
        Map<String, Object> values = new HashMap<>();
        values.put("companyName", "门店");
        values.put("salePrice", new BigDecimal("0"));
        ValidationException e = assertThrows(ValidationException.class,
                () -> TraceValidator.create().stageFields(TraceStage.RETAIL, values).throwIfInvalid());
        List<String> fields = e.getErrors().stream().map(m -> m.get("field")).collect(Collectors.toList());
        assertEquals(List.of("salePrice", "saleQuantity", "shelfLife", "invoiceNo", "saleTime"), fields);
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("salePrice"));
    }

    @Test
    void 字段清单与合约参数一一对应_价格数量与单据号不公开() {
        assertEquals(7, TraceFields.of(TraceStage.PRODUCTION).size());
        assertEquals(8, TraceFields.of(TraceStage.DISTRIBUTION).size());
        assertEquals(6, TraceFields.of(TraceStage.RETAIL).size());
        for (String hidden : List.of("distributePrice", "distributeQuantity", "storageLocation", "inspectionReport")) {
            assertFalse(TraceFields.find(TraceStage.DISTRIBUTION, hidden).get().isPublic, hidden);
        }
        for (String hidden : List.of("salePrice", "saleQuantity", "invoiceNo")) {
            assertFalse(TraceFields.find(TraceStage.RETAIL, hidden).get().isPublic, hidden);
        }
        assertFalse(TraceFields.find(TraceStage.PRODUCTION, "productionCert").get().isPublic);
        assertFalse(TraceFields.fileField(TraceStage.RETAIL).isPresent());
    }

    private interface Check {
        void apply(TraceValidator v);
    }

    private static void assertOk(Check check) {
        TraceValidator v = TraceValidator.create();
        check.apply(v);
        v.throwIfInvalid();
    }

    private static void assertError(String field, Check check) {
        TraceValidator v = TraceValidator.create();
        check.apply(v);
        assertTrue(v.hasError(field), "期望字段 " + field + " 报错");
    }
}
