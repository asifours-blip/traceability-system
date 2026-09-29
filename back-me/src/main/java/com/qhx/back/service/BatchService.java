package com.qhx.back.service;

import com.qhx.back.chain.TraceStage;
import com.qhx.back.model.ChainTx;
import com.qhx.back.model.to.AssignTo;
import com.qhx.back.model.to.CorrectionTo;
import com.qhx.back.model.to.DistributorTo;
import com.qhx.back.model.to.ProducerTo;
import com.qhx.back.model.to.RetailerTo;
import com.qhx.back.model.vo.PageQuery;
import com.qhx.back.model.vo.PageResult;

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

    /**
     * 当前用户名下的批次（管理员看全部），分页；归属取 trace_batch，链上进度取读模型，不逐条读链。
     * todo=true 只看「轮到我录入」的：生产商看链上还没有生产记录的，分销商看已生产未分销的，零售商看已分销未零售的。
     */
    PageResult<Map<String, Object>> list(PageQuery page, boolean todo, String keyword);

    /** 批次详情：链上数据 + 各阶段交易状态 + 交接历史 + 更正；只有批次参与者与管理员可见 */
    Map<String, Object> detail(String traceNumber);

    /** Rule-based, single-batch investigation from one authorised detail read. */
    Map<String, Object> investigation(String traceNumber);

    /** 追加链下更正：只有该阶段的链上写入者本人能提交 */
    Map<String, Object> addCorrection(String traceNumber, CorrectionTo to);

    /** 可被指定为交接对象的账号（生产商查分销商，分销商查零售商） */
    List<Map<String, Object>> partners(String role);

    /** 消费者扫码视图：只含公开字段；数据取读模型（首次查询时读链补进读模型） */
    Map<String, Object> publicDetail(String traceNumber);

    /** 消费者分页查询：只查读模型，只含公开字段 */
    PageResult<Map<String, Object>> searchPublic(String keyword, PageQuery page);

    /** 按链上绑定读取某阶段的公开文件（生产认证 / 质检报告）：CID 从链上读，且必须是已绑定（BOUND）的文件 */
    FileService.PublicFile publicFile(String traceNumber, String stage);
}
