package com.qhx.back.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 链上交易提交记录：发请求之前写入，之后跟随结果更新。见 docs/tx-lifecycle.md。
 */
@Data
@TableName("chain_tx")
public class ChainTx {
    @TableId(type = IdType.AUTO)
    private Long id;
    // 阶段交易：trace:{溯源号}:{PRODUCTION|DISTRIBUTION|RETAIL}；其他交易：{函数名}:{参数摘要前 16 位}
    private String bizKey;
    // 未决占位（唯一索引）：阶段交易处于 PENDING/SUBMITTED/UNKNOWN 时等于 bizKey，终态或查证确认未写入后置空
    private String inflightKey;
    private String traceNumber;
    // 1 生产 2 分销 3 零售；非阶段交易为空
    private Integer stage;
    private String funcName;
    // sha256(参数逐个转字符串后的 JSON 数组)，查证时与链上读回的阶段数据比对
    private String paramsDigest;
    private String signer;
    private String state;
    private String txHash;
    private Long blockNumber;
    // 回执 status 原值：0x0 / 0x16 / 50001 等
    private String receiptStatus;
    private String errorReason;
    // 最近一次查证结论
    private String verifyResult;
    private Date createdAt;
    private Date updatedAt;
}
