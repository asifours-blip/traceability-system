package com.qhx.back.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 链下更正记录：链上数据只能写一次，写错了只能在链下追加说明。只追加，不改不删。
 */
@Data
@TableName("trace_correction")
public class TraceCorrection {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceNumber;
    // 1 生产 2 分销 3 零售
    private Integer stage;
    private Long authorId;
    private String authorUsername;
    private String authorCompany;
    private String authorAddress;
    private String reason;
    // JSON：{字段名: 更正后的值}
    private String content;
    private Date createdAt;
}
