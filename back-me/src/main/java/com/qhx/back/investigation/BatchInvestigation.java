package com.qhx.back.investigation;

import com.qhx.back.chain.TraceStage;
import com.qhx.back.trace.ChainTraceReader;
import com.qhx.back.trace.TraceFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Deterministic leads from one authorised detail read; never a recall decision. */
public final class BatchInvestigation {
    private BatchInvestigation() { }

    public static Map<String, Object> fromDetail(Map<String, Object> detail) {
        String chainError = (String) detail.get("chainError");
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> stages = maps(detail.get("stages"));
        boolean anyOnChain = stages.stream().anyMatch(stage -> Boolean.TRUE.equals(stage.get("onChain")));
        String chainRecordStatus = chainError != null ? "UNAVAILABLE" : anyOnChain ? "AVAILABLE" : "NOT_CREATED";
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (String partyName : Arrays.asList("producer", "distributor", "retailer")) {
            Map<String, Object> party = map(detail.get(partyName));
            evidence.add(evidence(detail, "LEDGER", null, null, "trace_batch." + partyName + "_id->user_account",
                    party, null, null, null));
        }
        for (Map<String, Object> stage : stages) {
            evidence.add(txEvidence(detail, stage));
            Map<String, Object> fileStatus = map(stage.get("file"));
            if (fileStatus != null) {
                evidence.add(evidence(detail, "LEDGER", null, (String) stage.get("stage"), "file.state",
                        fileStatus.get("state"), null, null, null));
                if (fileStatus.containsKey("available")) {
                    evidence.add(evidence(detail, "FILE_PROBE", null, (String) stage.get("stage"), "file.available",
                            fileStatus.get("available"), null, null, null));
                }
            }
            if (chainError == null) {
                String name = (String) stage.get("stage");
                evidence.add(evidence(detail, "CHAIN_READ", Boolean.TRUE.equals(stage.get("onChain"))
                                ? TraceStage.valueOf(name).readFunction() : "getStageActors", name,
                        Boolean.TRUE.equals(stage.get("onChain")) ? "data" : "onChain",
                        Boolean.TRUE.equals(stage.get("onChain")) ? stage.get("data") : false,
                        stage.get("writer"), null, null));
            }
        }
        if (chainError == null && "NOT_CREATED".equals(chainRecordStatus)) {
            evidence.add(evidence(detail, "CHAIN_READ", "getStageActors", null, "traceNumber", "NOT_CREATED",
                    null, null, null));
        }
        if (chainError == null) {
            if ("V3".equals(detail.get("contractVersion"))) {
                designation(issues, detail, "distributor", "chainDesignatedDistributor", "DISTRIBUTION");
                designation(issues, detail, "retailer", "chainDesignatedRetailer", "RETAIL");
            }
            for (Map<String, Object> stage : stages) {
                String name = (String) stage.get("stage");
                TraceStage kind = TraceStage.valueOf(name);
                Map<String, Object> file = map(stage.get("file"));
                if (file == null) continue;
                String code = "FILE_MISSING".equals(file.get("errorCode")) ? "FILE_MISSING"
                        : "CONFLICT".equals(file.get("state")) ? "FILE_BINDING_CONFLICT"
                        : "IPFS_UNAVAILABLE".equals(file.get("errorCode")) ? "FILE_UNVERIFIED" : null;
                if (code == null) continue;
                String field = TraceFields.fileField(kind).orElse(null);
                Map<String, Object> data = map(stage.get("data"));
                String message = "FILE_MISSING".equals(code) ? "链上登记的文件在存储节点缺失"
                        : "FILE_UNVERIFIED".equals(code) ? "存储服务不可用，文件内容尚未核验"
                        : "链上 CID 与本系统文件绑定不一致";
                issue(issues, code, name, message,
                        Arrays.asList(evidence(detail, "CHAIN_READ", kind.readFunction(), name, field,
                                data == null ? null : data.get(field), stage.get("writer"), null, null),
                                evidence(detail, "FILE_BINDING_CONFLICT".equals(code) ? "LEDGER" : "FILE_PROBE", null, name,
                                        "FILE_BINDING_CONFLICT".equals(code) ? "file.state" : "file.errorCode",
                                        "FILE_BINDING_CONFLICT".equals(code) ? file.get("state") : file.get("errorCode"),
                                        null, null, null), txEvidence(detail, stage)));
            }
        }
        for (Map<String, Object> stage : stages) {
            if ("PENDING".equals(stage.get("status")) || "UNKNOWN".equals(stage.get("txState"))) {
                issue(issues, "TX_UNRESOLVED", (String) stage.get("stage"), "本系统交易结果未决，需人工查证",
                        Arrays.asList(txEvidence(detail, stage)));
            }
        }
        List<String> codes = issues.stream().map(item -> (String) item.get("code")).distinct().collect(Collectors.toList());
        boolean fileUnverified = codes.contains("FILE_UNVERIFIED");
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("scope", "SINGLE_BATCH_ONLY");
        draft.put("traceNumber", detail.get("traceNumber"));
        draft.put("reviewState", chainError != null || fileUnverified ? "INCONCLUSIVE" : codes.isEmpty() ? "NO_RULE_FINDINGS" : "HUMAN_REVIEW_REQUIRED");
        draft.put("issueCodes", codes);
        draft.put("suggestedAction", chainError != null ? "先恢复链上读取，再核查该批次；当前不能确认链上事实"
                : fileUnverified ? "先恢复存储服务并核验文件；当前不能判定文件缺失。其他线索仍需人工复核"
                : codes.isEmpty() ? "规则未发现异常；如有外部线索仍需人工核查" : "核对证据与该批次交接记录，再由负责人决定是否扩大调查范围");
        draft.put("downstreamScope", "UNKNOWN");
        draft.put("inventory", "UNKNOWN");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "batch-investigation/v1");
        result.put("traceNumber", detail.get("traceNumber"));
        result.put("contractVersion", detail.get("contractVersion"));
        result.put("contractAddress", detail.get("contractAddress"));
        result.put("status", chainError == null && !fileUnverified ? "ASSESSED" : "INCONCLUSIVE");
        result.put("chainRecordStatus", chainRecordStatus);
        result.put("chainError", chainError);
        result.put("evidence", evidence);
        result.put("issues", issues);
        result.put("recallDraft", draft);
        result.put("basis", "Deterministic rules over one authorised detail read; no chain-wide scan, transaction, notification or business write");
        return result;
    }

    private static void designation(List<Map<String, Object>> issues, Map<String, Object> detail,
                                    String partyName, String chainField, String stage) {
        Map<String, Object> party = map(detail.get(partyName));
        String chainAddress = (String) detail.get(chainField);
        String ledgerAddress = party == null ? null : (String) party.get("chainAddress");
        if (chainAddress == null || ChainTraceReader.ZERO_ADDRESS.equalsIgnoreCase(chainAddress)
                || chainAddress.equalsIgnoreCase(ledgerAddress)) return;
        boolean unmapped = ledgerAddress == null;
        issue(issues, unmapped ? "DESIGNATION_UNMAPPED" : "DESIGNATION_MISMATCH", stage,
                unmapped ? "链上有指定地址，但本系统未映射当前参与者地址" : "链上指定地址与当前台账参与者地址不一致", Arrays.asList(
                evidence(detail, "CHAIN_READ", "getDesignations", stage, chainField, chainAddress, null, null, null),
                evidence(detail, "LEDGER", null, stage, "trace_batch." + partyName + "_id->user_account.chain_address",
                        ledgerAddress, null, null, null)));
    }

    private static Map<String, Object> txEvidence(Map<String, Object> detail, Map<String, Object> stage) {
        return evidence(detail, "LEDGER", null, (String) stage.get("stage"), "chain_tx.state",
                stage.get("txState"), null, stage.get("txHash"), stage.get("blockNumber"));
    }

    private static Map<String, Object> evidence(Map<String, Object> detail, String source, String readFunction,
                                                 String stage, String field, Object value, Object writer,
                                                 Object txHash, Object blockNumber) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source", source);
        item.put("traceNumber", detail.get("traceNumber"));
        item.put("contractVersion", detail.get("contractVersion"));
        item.put("contractAddress", detail.get("contractAddress"));
        item.put("readFunction", readFunction);
        item.put("stage", stage);
        item.put("field", field);
        item.put("value", value);
        item.put("writer", writer);
        item.put("txHash", txHash);
        item.put("blockNumber", blockNumber);
        return item;
    }

    private static void issue(List<Map<String, Object>> issues, String code, String stage, String message,
                              List<Map<String, Object>> evidence) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("stage", stage);
        item.put("message", message);
        item.put("evidence", evidence);
        issues.add(item);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return value == null ? null : (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) { return (List<Map<String, Object>>) value; }
}
