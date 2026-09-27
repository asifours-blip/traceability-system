package com.qhx.back.trace;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import com.qhx.back.chain.TraceStage;
import com.qhx.back.client.WeBaseClient;
import com.qhx.back.exception.BusinessException;
import com.qhx.back.exception.WeBaseFrontException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读链上的阶段数据与写入者。链上是阶段数据的唯一来源；读不到时抛 503，不拿链下副本冒充。
 */
public class ChainTraceReader {

    public static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    // 实测只读调用 revert 时返回 ["Call contract return error: Trace: traceNumber does not exist"]
    private static final String CALL_ERROR = "Call contract return error";
    private static final String NOT_EXIST = "Trace: traceNumber does not exist";

    private final WeBaseClient client;

    public ChainTraceReader(WeBaseClient client) {
        this.client = client;
    }

    /** getStageActors：(producer, distributor, retailer)；溯源号在链上不存在时返回 null */
    public List<String> actors(String traceNumber) {
        JSONArray result = read("getStageActors", traceNumber);
        if (result == null) {
            return null;
        }
        if (result.size() != 3) {
            throw new BusinessException(502, "无法解析链上 getStageActors 的返回：" + StrUtil.maxLength(String.valueOf(result), 200));
        }
        return result.toList(String.class);
    }

    /** 某阶段在链上的写入者；未写入或溯源号不存在返回 null */
    public String actor(List<String> actors, TraceStage stage) {
        if (actors == null) {
            return null;
        }
        String a = actors.get(stage.actorIndex());
        return a == null || ZERO_ADDRESS.equalsIgnoreCase(a) ? null : a;
    }

    /**
     * 读某阶段的数据，按 TraceFields 字段名组装，另带 timestamp（上链时间，毫秒）。
     * 溯源号不存在或该阶段未写入（timestamp 为 0）时返回 null。
     */
    public Map<String, Object> stage(String traceNumber, TraceStage stage) {
        JSONArray result = read(stage.readFunction(), traceNumber);
        if (result == null) {
            return null;
        }
        List<TraceFields.Field> fields = TraceFields.of(stage);
        if (result.size() != fields.size() + 1) {
            throw new BusinessException(502, "无法解析链上 " + stage.readFunction() + " 的返回：" + StrUtil.maxLength(String.valueOf(result), 200));
        }
        long timestamp = parseLong(result.getStr(fields.size()));
        if (timestamp == 0) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            TraceFields.Field f = fields.get(i);
            String raw = result.getStr(i);
            data.put(f.name, f.type == TraceFields.Type.POSITIVE_INT ? (Object) parseLong(raw) : raw);
        }
        data.put("timestamp", timestamp);
        return data;
    }

    private JSONArray read(String function, String traceNumber) {
        JSONArray result;
        try {
            result = client.call(function, Collections.singletonList(traceNumber));
        } catch (WeBaseFrontException e) {
            throw new BusinessException(503, "读取链上数据失败，请稍后重试（" + e.mes + "）");
        }
        if (result != null && result.size() == 1 && String.valueOf(result.get(0)).startsWith(CALL_ERROR)) {
            if (String.valueOf(result.get(0)).endsWith(NOT_EXIST)) {
                return null;
            }
            throw new BusinessException(502, "链上只读调用失败：" + result.get(0));
        }
        if (result == null) {
            throw new BusinessException(502, "链上 " + function + " 没有返回数据");
        }
        return result;
    }

    private static long parseLong(String s) {
        if (StrUtil.isBlank(s)) {
            return 0;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(502, "无法解析链上数值：" + s);
        }
    }
}
