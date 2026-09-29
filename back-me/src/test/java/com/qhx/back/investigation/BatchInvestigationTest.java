package com.qhx.back.investigation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BatchInvestigationTest {
    @Test
    void normalBatchHasEvidenceWithoutInventedIssues() {
        Map<String, Object> report = BatchInvestigation.fromDetail(detail());
        assertEquals("AVAILABLE", report.get("chainRecordStatus"));
        assertTrue(items(report.get("issues")).isEmpty());
        assertEquals(9, items(report.get("evidence")).size());
        assertEquals("NO_RULE_FINDINGS", map(report.get("recallDraft")).get("reviewState"));
    }

    @Test
    void designatedMismatchAndUnmappedAreDifferentLeads() {
        Map<String, Object> detail = detail();
        detail.put("chainDesignatedDistributor", "0xother");
        Map<String, Object> report = BatchInvestigation.fromDetail(detail);
        assertEquals("DESIGNATION_MISMATCH", items(report.get("issues")).get(0).get("code"));
        assertEquals("getDesignations", items(items(report.get("issues")).get(0).get("evidence")).get(0).get("readFunction"));
        detail.put("distributor", null);
        report = BatchInvestigation.fromDetail(detail);
        assertEquals("DESIGNATION_UNMAPPED", items(report.get("issues")).get(0).get("code"));
        detail.put("chainDesignatedDistributor", "0x0000000000000000000000000000000000000000");
        assertTrue(items(BatchInvestigation.fromDetail(detail).get("issues")).isEmpty());
    }

    @Test
    void missingFileAndUnknownTransactionKeepTheirOwnProvenance() {
        Map<String, Object> detail = detail();
        Map<String, Object> production = items(detail.get("stages")).get(0);
        production.put("file", obj("errorCode", "FILE_MISSING"));
        production.put("txState", "UNKNOWN");
        production.put("status", "PENDING");
        production.put("txHash", "0xhash");
        production.put("blockNumber", 42L);
        List<Map<String, Object>> issues = items(BatchInvestigation.fromDetail(detail).get("issues"));
        assertEquals(Arrays.asList("FILE_MISSING", "TX_UNRESOLVED"), Arrays.asList(issues.get(0).get("code"), issues.get(1).get("code")));
        assertEquals("FILE_PROBE", items(issues.get(0).get("evidence")).get(1).get("source"));
        assertEquals("0xhash", items(issues.get(1).get("evidence")).get(0).get("txHash"));
    }

    @Test
    void storageUnavailableIsUnverifiedAndNotBoundRemainsVisible() {
        Map<String, Object> detail = detail();
        Map<String, Object> production = items(detail.get("stages")).get(0);
        production.put("file", obj("state", "BOUND", "errorCode", "IPFS_UNAVAILABLE", "available", null));
        Map<String, Object> report = BatchInvestigation.fromDetail(detail);
        assertEquals("INCONCLUSIVE", report.get("status"));
        assertEquals("FILE_UNVERIFIED", items(report.get("issues")).get(0).get("code"));
        assertTrue(String.valueOf(map(report.get("recallDraft")).get("suggestedAction")).contains("恢复存储服务"));
        assertEquals("FILE_PROBE", items(items(report.get("issues")).get(0).get("evidence")).get(1).get("source"));
        production.put("file", obj("state", "NOT_BOUND"));
        report = BatchInvestigation.fromDetail(detail);
        assertTrue(items(report.get("issues")).isEmpty());
        assertTrue(items(report.get("evidence")).stream().anyMatch(item ->
                "file.state".equals(item.get("field")) && "NOT_BOUND".equals(item.get("value"))));
    }

    @Test
    void unavailableChainCannotBeReportedAsChainEvidence() {
        Map<String, Object> detail = detail();
        detail.put("chainError", "read unavailable");
        Map<String, Object> report = BatchInvestigation.fromDetail(detail);
        assertEquals("INCONCLUSIVE", report.get("status"));
        assertEquals("UNAVAILABLE", report.get("chainRecordStatus"));
        assertTrue(items(report.get("evidence")).stream().noneMatch(item -> "CHAIN_READ".equals(item.get("source"))));
        assertTrue(items(report.get("issues")).isEmpty());
    }

    @Test
    void ledgerBatchWithoutChainRecordIsExplicit() {
        Map<String, Object> detail = detail();
        for (Map<String, Object> stage : items(detail.get("stages"))) stage.put("onChain", false);
        Map<String, Object> report = BatchInvestigation.fromDetail(detail);
        assertEquals("NOT_CREATED", report.get("chainRecordStatus"));
        assertTrue(items(report.get("issues")).isEmpty());
    }

    private static Map<String, Object> detail() {
        Map<String, Object> detail = obj("traceNumber", "BATCH-1", "contractVersion", "V3",
                "contractAddress", "0xcontract", "chainError", null,
                "producer", obj("chainAddress", "0xproducer"),
                "distributor", obj("chainAddress", "0xdistributor"), "retailer", null,
                "chainDesignatedDistributor", "0xdistributor",
                "chainDesignatedRetailer", "0x0000000000000000000000000000000000000000");
        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(obj("stage", "PRODUCTION", "onChain", true, "writer", "0xproducer",
                "data", obj("productionCert", "QmCert"), "status", "CONFIRMED", "txState", "CONFIRMED"));
        stages.add(obj("stage", "DISTRIBUTION", "onChain", false, "status", "NOT_STARTED"));
        stages.add(obj("stage", "RETAIL", "onChain", false, "status", "NOT_STARTED"));
        detail.put("stages", stages);
        return detail;
    }

    private static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Object value) { return (List<Map<String, Object>>) value; }
}
