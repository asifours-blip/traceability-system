package com.qhx.back.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 交接对象指定历史，只追加。
 */
@Data
@TableName("trace_assignment_log")
public class TraceAssignmentLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceNumber;
    // 被指定对象负责的阶段：2 分销 3 零售
    private Integer stage;
    private Long fromUserId;
    private Long toUserId;
    private Long operatorId;
    private String reason;
    private Date createdAt;
}
