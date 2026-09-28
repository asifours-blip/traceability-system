package com.qhx.back.service;

import com.qhx.back.model.TraceReadModel;
import com.qhx.back.model.vo.PageQuery;
import com.qhx.back.model.vo.PageResult;

import java.util.Map;

/**
 * 溯源读模型（trace_read_model）：列表与消费者查询从这里取，不逐条读链。
 * 所有内容只来自读链结果；可以随时从链上完整重建（getAgroFoodList + getStageActors + 各阶段读函数），重建是幂等的。
 * 设计与核验依据见 docs/read-model.md。
 */
public interface ReadModelService
{
    /** 读模型里的一行；没有返回 null（不读链） */
    TraceReadModel get(String traceNumber);

    /** 读链刷新一个溯源号并写入读模型；链上不存在返回 null。阶段交易确认后、以及消费者首次查询时调用 */
    TraceReadModel refresh(String traceNumber);

    /**
     * 从链上完整重建：逐个溯源号写入读模型、删除链上已不存在的行、为没有归属记录的旧批次按链上写入者地址回填归属、
     * 按链上 CID 补齐文件绑定。重复执行结果不变。
     *
     * @param operatorId 执行重建的管理员账号（记入回填的交接历史）
     * @return 重建报告
     */
    Map<String, Object> rebuild(Long operatorId);

    /** 消费者分页查询：只含公开字段 */
    PageResult<Map<String, Object>> searchPublic(String keyword, PageQuery page);

    /** 未认领（本系统没有归属记录）与部分认领（某阶段写入者与归属不一致）的批次 */
    PageResult<Map<String, Object>> unclaimed(PageQuery page);
}
