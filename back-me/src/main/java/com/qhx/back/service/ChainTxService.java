package com.qhx.back.service;

import com.qhx.back.chain.TraceStage;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.vo.ChainTxVerifyVO;

import java.util.List;

/**
 * 所有上链交易的唯一入口：发请求之前先把提交意图写进 chain_tx，再按结果更新状态。
 * 只有回执确认成功才正常返回；失败、未知、重复提交都抛 ChainTxException（带 HTTP 状态码与交易记录）。
 */
public interface ChainTxService
{
    // 非阶段交易（授予/撤销角色、系统信息）：记录但不占用业务键
    ChainTx submit(String funcName, List<Object> params);

    // 溯源阶段交易：params 第一个元素是溯源号；同一溯源号同一阶段有未决记录时拒绝提交
    ChainTx submitStage(TraceStage stage, List<Object> params);

    // 查看交易记录：仅签名者本人或管理员
    ChainTx get(Long id);

    // 查证 SUBMITTED / UNKNOWN（以及残留的 PENDING）记录：有哈希先查回执，拿不到回执的阶段交易读链判断
    ChainTxVerifyVO verify(Long id);
}
