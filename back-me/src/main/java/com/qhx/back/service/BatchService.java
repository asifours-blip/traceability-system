package com.qhx.back.service;

import com.qhx.back.chain.TraceStage;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.to.AssignTo;
import com.qhx.back.model.to.CorrectionTo;
import com.qhx.back.model.to.DistributorTo;
import com.qhx.back.model.to.ProducerTo;
import com.qhx.back.model.to.RetailerTo;

import java.util.List;
import java.util.Map;

/**
 * 批次业务：归属、交接、三阶段写入、链下更正、消费者公开视图。
 * 这里的归属与交接是后端规则（链下 MySQL）；链上 v2 合约只强制角色、阶段顺序、每阶段只写一次。见 docs/business-flow.md。
 */
public interface BatchService
{
    ChainTx submitProduction(ProducerTo to);

    ChainTx submitDistribution(DistributorTo to);

    ChainTx submitRetail(RetailerTo to);

    /** 变更交接对象：stage=DISTRIBUTION 由生产商指定分销商，stage=RETAIL 由分销商指定零售商 */
    Map<String, Object> reassign(String traceNumber, TraceStage stage, AssignTo to);

    /** 当前用户名下的批次（管理员看全部），只读数据库，不逐条读链 */
    List<Map<String, Object>> list();

    /** 批次详情：链上数据 + 各阶段交易状态 + 交接历史 + 更正；只有批次参与者与管理员可见 */
    Map<String, Object> detail(String traceNumber);

    /** 追加链下更正：只有该阶段的链上写入者本人能提交 */
    Map<String, Object> addCorrection(String traceNumber, CorrectionTo to);

    /** 可被指定为交接对象的账号（生产商查分销商，分销商查零售商） */
    List<Map<String, Object>> partners(String role);

    /** 消费者扫码视图：只含公开字段 */
    Map<String, Object> publicDetail(String traceNumber);

    /** 按链上绑定读取某阶段的公开文件（生产认证 / 质检报告） */
    PublicFile publicFile(String traceNumber, String stage);

    final class PublicFile
    {
        public final byte[] bytes;
        public final String contentType;

        public PublicFile(byte[] bytes, String contentType)
        {
            this.bytes = bytes;
            this.contentType = contentType;
        }
    }
}
