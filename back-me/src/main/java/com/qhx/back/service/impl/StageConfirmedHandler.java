package com.qhx.back.service.impl;

import com.qhx.back.chain.StageConfirmedListener;
import com.qhx.back.chain.TraceStage;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.TraceReadModel;
import com.qhx.back.service.FileService;
import com.qhx.back.service.ReadModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 阶段交易确认后：读链刷新读模型，再按读链得到的 CID 绑定文件。
 * 绑定用的 CID 只取读链结果，不取请求体；读链失败时这次不绑定，由管理员重建读模型时补齐。
 */
@Component
@Slf4j
public class StageConfirmedHandler implements StageConfirmedListener
{
    @Autowired
    private ReadModelService readModelService;
    @Autowired
    private FileService fileService;

    @Override
    public void onStageConfirmed(ChainTx tx)
    {
        TraceReadModel row = readModelService.refresh(tx.getTraceNumber());
        TraceStage stage = TraceStage.ofCode(tx.getStage());
        if (row == null || stage == null || stage == TraceStage.RETAIL) {
            return;
        }
        String cid = stage == TraceStage.PRODUCTION ? row.getProductionCid() : row.getDistributionCid();
        FileService.BindOutcome outcome = fileService.bindConfirmed(tx, cid);
        log.info("交易 #{} 已确认：{} {} 文件绑定结果 {}", tx.getId(), tx.getTraceNumber(), stage, outcome);
    }
}
