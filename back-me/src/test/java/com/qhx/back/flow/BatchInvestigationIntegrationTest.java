package com.qhx.back.flow;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qhx.back.enums.UserRole;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.TraceBatch;
import com.qhx.back.support.FakeWeBaseFront;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:investigation_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
})
class BatchInvestigationIntegrationTest extends FlowTestSupport {
    @Test
    void authorizedSingleReadAndNoBusinessWrites() throws Exception {
        Party producer = party(UserRole.PRODUCER);
        Party distributor = party(UserRole.DISTRIBUTOR);
        Party outsider = party(UserRole.RETAILER);
        String traceNumber = produced(producer, distributor);
        String route = "/batches/" + traceNumber + "/investigation";
        long txBefore = chainTxMapper.selectCount(new LambdaQueryWrapper<ChainTx>()
                .eq(ChainTx::getTraceNumber, traceNumber));
        fake.reset();
        JSONObject report = perform(get(route), producer.token, 200).getJSONObject("data");
        assertEquals("AVAILABLE", report.getStr("chainRecordStatus"));
        assertEquals(0, report.getJSONArray("issues").size(), report.toString());
        assertTrue(report.getJSONArray("evidence").size() >= 9);
        assertEquals(1, fake.requestsFor("getStageActors").size(), "one detail read, not a second chain read");
        assertEquals(txBefore, chainTxMapper.selectCount(new LambdaQueryWrapper<ChainTx>()
                .eq(ChainTx::getTraceNumber, traceNumber)));
        assertTrue(fake.requestsFor("addTraceInfoByDistributor").isEmpty());
        perform(get(route), distributor.token, 200);
        perform(get(route), outsider.token, 403);
        perform(get(route), login("admin", ADMIN_PASSWORD), 200);
    }

    @Test
    void designationReadFailureIsInconclusiveWithoutMixedChainEvidence() throws Exception {
        Party producer = party(UserRole.PRODUCER);
        Party distributor = party(UserRole.DISTRIBUTOR);
        String traceNumber = produced(producer, distributor);
        fake.on("getDesignations", request -> FakeWeBaseFront.frontError(500, "temporary unavailable"));
        JSONObject report = perform(get("/batches/" + traceNumber + "/investigation"), producer.token, 200)
                .getJSONObject("data");
        assertEquals("INCONCLUSIVE", report.getStr("status"));
        assertEquals("UNAVAILABLE", report.getStr("chainRecordStatus"));
        assertEquals(0, report.getJSONArray("issues").size());
        assertFalse(report.getJSONArray("evidence").stream()
                .anyMatch(item -> "CHAIN_READ".equals(((JSONObject) item).getStr("source"))));
    }

    @Test
    void mismatchAndBoundAddressDriftAreReportedWithoutWriting() throws Exception {
        Party producer = party(UserRole.PRODUCER);
        Party distributor = party(UserRole.DISTRIBUTOR);
        String traceNumber = produced(producer, distributor);
        String route = "/batches/" + traceNumber + "/investigation";
        assertTrue(fake.redesignateV3(traceNumber, producer.user.getChainAddress(),
                "0x7777777777777777777777777777777777777777"));
        JSONObject report = perform(get(route), producer.token, 200).getJSONObject("data");
        assertEquals("DESIGNATION_MISMATCH", report.getJSONArray("issues").getJSONObject(0).getStr("code"));
        TraceBatch batch = traceBatchMapper.selectOne(new LambdaQueryWrapper<TraceBatch>()
                .eq(TraceBatch::getTraceNumber, traceNumber));
        batch.setContractAddress("0x2222222222222222222222222222222222222222");
        traceBatchMapper.updateById(batch);
        perform(get(route), producer.token, 409);
    }
}
