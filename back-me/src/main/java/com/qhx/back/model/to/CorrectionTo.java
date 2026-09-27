package com.qhx.back.model.to;

import lombok.Data;

import java.util.Map;

/**
 * 链下更正：阶段（PRODUCTION / DISTRIBUTION / RETAIL）、原因、{字段名: 更正后的值}。
 */
@Data
public class CorrectionTo
{
    private String stage;
    private String reason;
    private Map<String, Object> content;
}
