package com.qhx.back.model.to;

import lombok.Data;

/**
 * 变更交接对象：新指定账号的用户名与原因。
 */
@Data
public class AssignTo
{
    private String username;
    private String reason;
}
