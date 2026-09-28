package com.qhx.back.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qhx.back.model.TraceReadModel;
import com.qhx.back.model.vo.BatchListRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 读模型与分页查询。
 * 分页统一用 LIMIT/OFFSET + COUNT，不依赖 MyBatis-Plus 分页插件；H2（MySQL 模式）与 MySQL 8 通用。
 * 动态条件之间都留空格：MySQL 驱动在客户端替换参数，"?AND" 会变成 "5AND" 而报语法错误（H2 不报，真实 MySQL 上发现）。
 * keyword 由调用方转义好 % _ \ 并包上 %。
 */
@Mapper
public interface TraceReadModelMapper extends BaseMapper<TraceReadModel> {

    String BATCH_WHERE = "<where>"
            + "<if test='producerId != null'> AND b.producer_id = #{producerId} </if>"
            + "<if test='distributorId != null'> AND b.distributor_id = #{distributorId} </if>"
            + "<if test='retailerId != null'> AND b.retailer_id = #{retailerId} </if>"
            + "<if test='todoStage != null'> AND COALESCE(m.stage_reached, 0) = #{todoStage} </if>"
            + "<if test='keyword != null'> AND (b.trace_number LIKE #{keyword} OR b.product_name LIKE #{keyword})</if>"
            + " </where>";

    /** 各角色的批次列表：归属来自 trace_batch，链上进度来自读模型；按建档先后倒序 */
    @Select("<script>SELECT b.id, b.trace_number, b.product_name, b.producer_id, b.distributor_id, b.retailer_id, b.created_at,"
            + " m.stage_reached FROM trace_batch b LEFT JOIN trace_read_model m ON m.trace_number = b.trace_number"
            + BATCH_WHERE + " ORDER BY b.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<BatchListRow> pageBatches(@Param("producerId") Long producerId, @Param("distributorId") Long distributorId,
                                   @Param("retailerId") Long retailerId, @Param("todoStage") Integer todoStage,
                                   @Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM trace_batch b LEFT JOIN trace_read_model m ON m.trace_number = b.trace_number"
            + BATCH_WHERE + "</script>")
    long countBatches(@Param("producerId") Long producerId, @Param("distributorId") Long distributorId,
                      @Param("retailerId") Long retailerId, @Param("todoStage") Integer todoStage,
                      @Param("keyword") String keyword);

    String PUBLIC_WHERE = "<where><if test='keyword != null'>"
            + "(trace_number LIKE #{keyword} OR product_name LIKE #{keyword} OR producer_company LIKE #{keyword})"
            + "</if></where>";

    /** 消费者查询：只查读模型，按生产上链时间倒序 */
    @Select("<script>SELECT * FROM trace_read_model" + PUBLIC_WHERE
            + " ORDER BY production_ts DESC, trace_number ASC LIMIT #{size} OFFSET #{offset}</script>")
    List<TraceReadModel> pagePublic(@Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) FROM trace_read_model" + PUBLIC_WHERE + "</script>")
    long countPublic(@Param("keyword") String keyword);

    /** 未认领 / 部分认领的批次（管理员） */
    @Select("SELECT * FROM trace_read_model WHERE claim_status <> 'CLAIMED'"
            + " ORDER BY list_index ASC, trace_number ASC LIMIT #{size} OFFSET #{offset}")
    List<TraceReadModel> pageUnclaimed(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM trace_read_model WHERE claim_status <> 'CLAIMED'")
    long countUnclaimed();
}
