package com.qhx.back.chain;

import cn.hutool.json.JSONArray;
import com.qhx.back.client.WeBaseClient;
import com.qhx.back.exception.WeBaseFrontException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 没有回执可查时，靠合约 v2「每个溯源号的每个阶段只能写一次」读链判断一笔阶段交易的下落：
 * 读 getStageActors 得到该阶段的写入者，再读该阶段的数据与本次提交的参数摘要比对。
 */
public class StageProbe {

    public enum Conclusion {
        /** 该阶段已由本次签名地址以相同数据写入：本次交易已上链 */
        WRITTEN_BY_SIGNER,
        /** 该阶段已被其他地址写入，或同一地址以不同数据写入：本次交易不可能再生效 */
        CONFLICT,
        /** 该阶段尚未写入 */
        NOT_WRITTEN
    }

    public static final class Result {
        public final Conclusion conclusion;
        public final String note;

        Result(Conclusion conclusion, String note) {
            this.conclusion = conclusion;
            this.note = note;
        }
    }

    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    // 实测只读调用 revert 时返回 ["Call contract return error: Trace: traceNumber does not exist"]
    private static final String NOT_EXIST = "Trace: traceNumber does not exist";

    private final WeBaseClient client;

    public StageProbe(WeBaseClient client) {
        this.client = client;
    }

    public Result probe(TraceStage stage, String traceNumber, String signer, String paramsDigest) {
        JSONArray actors = client.call("getStageActors", Collections.singletonList(traceNumber));
        if (actors != null && actors.size() == 1 && String.valueOf(actors.get(0)).endsWith(NOT_EXIST)) {
            return new Result(Conclusion.NOT_WRITTEN, "链上没有该溯源号，" + stage + " 阶段未写入");
        }
        if (actors == null || actors.size() != 3) {
            throw new WeBaseFrontException("无法解析 getStageActors 的返回：" + actors);
        }
        String actor = actors.getStr(stage.actorIndex());
        if (actor == null || ZERO_ADDRESS.equalsIgnoreCase(actor)) {
            return new Result(Conclusion.NOT_WRITTEN, stage + " 阶段在链上尚未写入");
        }
        if (!actor.equalsIgnoreCase(signer)) {
            return new Result(Conclusion.CONFLICT, stage + " 阶段已由 " + actor + " 写入，本次交易未生效");
        }
        JSONArray data = client.call(stage.readFunction(), Collections.singletonList(traceNumber));
        if (data == null || data.size() < 2) {
            throw new WeBaseFrontException("无法解析 " + stage.readFunction() + " 的返回：" + data);
        }
        // 读回值 = 写入参数去掉 traceNumber，末尾多一个 timestamp
        List<Object> onChain = new ArrayList<>();
        onChain.add(traceNumber);
        onChain.addAll(data.subList(0, data.size() - 1));
        if (ParamsDigest.of(onChain).equals(paramsDigest)) {
            return new Result(Conclusion.WRITTEN_BY_SIGNER, stage + " 阶段已由本账户以相同数据写入");
        }
        return new Result(Conclusion.CONFLICT, stage + " 阶段已由本账户以不同数据写入，本次交易未生效");
    }
}
