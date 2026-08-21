package com.qhx.back.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.qhx.back.client.WeBaseClient;
import com.qhx.back.context.AddressContext;
import com.qhx.back.model.Result;
import com.qhx.back.exception.WeBaseFrontException;
import com.qhx.back.model.to.DistributorTo;
import com.qhx.back.model.to.ProducerTo;
import com.qhx.back.model.to.RetailerTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceControllerTest {

    @Mock
    private WeBaseClient weBaseClient;

    private TraceController controller;

    @BeforeEach
    void setUp() {
        controller = new TraceController();
        ReflectionTestUtils.setField(controller, "weBaseClient", weBaseClient);
        AddressContext.clear();
    }

    @AfterEach
    void tearDown() {
        AddressContext.clear();
    }

    @Test
    void 查询详情_Mock合约返回() {
        stubProducer("SY1");
        when(weBaseClient.call(eq("getAgroFoodInfoByDistributor"), eq(List.of("SY1"))))
                .thenReturn(emptyCompany());
        when(weBaseClient.call(eq("getAgroFoodInfoByRetailer"), eq(List.of("SY1"))))
                .thenReturn(emptyCompany());

        Result result = controller.getTrace("SY1");
        assertEquals(200, result.getCode());
        JSONObject data = (JSONObject) result.getData();
        assertEquals("农场A", data.getJSONObject("producer").getStr("companyName"));
        assertEquals(true, data.getJSONObject("distributor").isEmpty());
    }

    @Test
    void 无此溯源号_抛错() {
        JSONArray missing = new JSONArray();
        missing.add("not exists");
        when(weBaseClient.call(eq("getAgroFoodInfo"), eq(List.of("NOPE"))))
                .thenReturn(missing);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> controller.getTrace("NOPE"));
        assertEquals("未找到该溯源信息", ex.getMessage());
    }

    @Test
    void 列表对每个编号打三次查询() {
        JSONArray inner = new JSONArray();
        inner.add("SY1");
        inner.add("SY2");
        JSONArray wrapped = new JSONArray();
        wrapped.add(inner);
        when(weBaseClient.call("getAgroFoodList")).thenReturn(wrapped);
        stubProducer("SY1");
        stubProducer("SY2");
        when(weBaseClient.call(eq("getAgroFoodInfoByDistributor"), eq(List.of("SY1"))))
                .thenReturn(emptyCompany());
        when(weBaseClient.call(eq("getAgroFoodInfoByDistributor"), eq(List.of("SY2"))))
                .thenReturn(emptyCompany());
        when(weBaseClient.call(eq("getAgroFoodInfoByRetailer"), eq(List.of("SY1"))))
                .thenReturn(emptyCompany());
        when(weBaseClient.call(eq("getAgroFoodInfoByRetailer"), eq(List.of("SY2"))))
                .thenReturn(emptyCompany());

        Result result = controller.getTraceList();
        JSONArray list = (JSONArray) result.getData();
        assertEquals(2, list.size());
        verify(weBaseClient).call(eq("getAgroFoodInfo"), eq(List.of("SY1")));
        verify(weBaseClient).call(eq("getAgroFoodInfo"), eq(List.of("SY2")));
        verify(weBaseClient).call(eq("getAgroFoodInfoByDistributor"), eq(List.of("SY1")));
        verify(weBaseClient).call(eq("getAgroFoodInfoByRetailer"), eq(List.of("SY2")));
    }

    @Test
    void 生产上链走newAgroFood() {
        AddressContext.setAddress("0x" + "b".repeat(40));
        ProducerTo to = new ProducerTo();
        to.setTraceNumber("SY1");
        to.setCompanyName("农场A");
        to.setProductName("苹果");
        to.setProductionLocation("烟台");
        to.setVariety("红富士");
        to.setProductionBatch("B001");
        to.setProductionCert("QmCid");
        to.setProductTime("2026-01-01");
        controller.addProducer(to);
        verify(weBaseClient).sendTransaction(
                eq("0x" + "b".repeat(40)),
                eq("newAgroFood"),
                eq(Arrays.asList("SY1", "农场A", "苹果", "烟台", "红富士", "B001", "QmCid", "2026-01-01"))
        );
    }

    @Test
    void 分销追加走addTraceInfoByDistributor() {
        AddressContext.setAddress("0x" + "c".repeat(40));
        DistributorTo to = new DistributorTo();
        to.setTraceNumber("SY1");
        to.setCompanyName("仓配");
        to.setStorageCondition("冷藏");
        to.setTransportMethod("货车");
        to.setDistributeBatch("D01");
        to.setStorageLocation("济南");
        to.setDistributePrice(10L);
        to.setDistributeQuantity(100L);
        to.setInspectionReport("QmR");
        controller.addDistributor(to);
        verify(weBaseClient).sendTransaction(
                eq("0x" + "c".repeat(40)),
                eq("addTraceInfoByDistributor"),
                eq(Arrays.asList("SY1", "仓配", "冷藏", "货车", "D01", "济南", 10L, 100L, "QmR"))
        );
    }

    @Test
    void 零售追加走addTraceInfoByRetailer() {
        AddressContext.setAddress("0x" + "d".repeat(40));
        RetailerTo to = new RetailerTo();
        to.setTraceNumber("SY1");
        to.setCompanyName("门店");
        to.setSalePrice(20L);
        to.setSaleQuantity(5L);
        to.setShelfLife(7L);
        to.setInvoiceNo("INV-1");
        to.setSaleTime("2026-02-01");
        controller.addRetailer(to);
        verify(weBaseClient).sendTransaction(
                eq("0x" + "d".repeat(40)),
                eq("addTraceInfoByRetailer"),
                eq(Arrays.asList("SY1", "门店", 20L, 5L, 7L, "INV-1", "2026-02-01"))
        );
    }

    @Test
    void 角色不足时WeBASE异常向上抛() {
        AddressContext.setAddress("0x" + "e".repeat(40));
        ProducerTo to = new ProducerTo();
        to.setTraceNumber("SY1");
        to.setCompanyName("农场A");
        to.setProductName("苹果");
        to.setProductionLocation("烟台");
        to.setVariety("红富士");
        to.setProductionBatch("B001");
        to.setProductionCert("QmCid");
        to.setProductTime("2026-01-01");
        org.mockito.Mockito.doThrow(new WeBaseFrontException("caller does not have the Producer role"))
                .when(weBaseClient)
                .sendTransaction(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq("newAgroFood"),
                        org.mockito.ArgumentMatchers.anyList());
        WeBaseFrontException ex = assertThrows(WeBaseFrontException.class, () -> controller.addProducer(to));
        assertEquals("caller does not have the Producer role", ex.getMessage());
    }

    private void stubProducer(String traceNumber) {
        JSONArray raw = new JSONArray();
        raw.add("农场A");
        raw.add("苹果");
        raw.add("烟台");
        raw.add("红富士");
        raw.add("B001");
        raw.add("QmCidCert");
        raw.add("2026-01-01");
        raw.add(1L);
        when(weBaseClient.call(eq("getAgroFoodInfo"), eq(List.of(traceNumber)))).thenReturn(raw);
    }

    private static JSONArray emptyCompany() {
        JSONArray raw = new JSONArray();
        raw.add("");
        return raw;
    }
}
