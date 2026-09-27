package com.qhx.back.chain;

/**
 * 合约 v2 的三个阶段。每个阶段对每个溯源号只能写一次，这是 UNKNOWN 交易可以靠读链查证的前提。
 */
public enum TraceStage {
    PRODUCTION(1, "newAgroFood", "getAgroFoodInfo"),
    DISTRIBUTION(2, "addTraceInfoByDistributor", "getAgroFoodInfoByDistributor"),
    RETAIL(3, "addTraceInfoByRetailer", "getAgroFoodInfoByRetailer");

    /** 与合约 STAGE_PRODUCED/DISTRIBUTED/RETAILED 一致 */
    private final int code;
    private final String writeFunction;
    private final String readFunction;

    TraceStage(int code, String writeFunction, String readFunction) {
        this.code = code;
        this.writeFunction = writeFunction;
        this.readFunction = readFunction;
    }

    public int code() {
        return code;
    }

    public String writeFunction() {
        return writeFunction;
    }

    /** 读回该阶段数据的函数：返回值 = 写入参数去掉 traceNumber，末尾多一个 timestamp */
    public String readFunction() {
        return readFunction;
    }

    /** getStageActors 返回值 (producer, distributor, retailer) 中对应的下标 */
    public int actorIndex() {
        return code - 1;
    }

    public static TraceStage ofCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (TraceStage stage : values()) {
            if (stage.code == code) {
                return stage;
            }
        }
        return null;
    }
}
