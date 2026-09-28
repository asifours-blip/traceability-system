package com.qhx.back.exception;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段校验失败：HTTP 400，data.errors 列出每个出错字段，mes 为第一条「字段: 原因」。
 */
public class ValidationException extends BusinessException
{
    private final transient List<Map<String, String>> errors;

    public ValidationException(List<Map<String, String>> errors)
    {
        super(400, summary(errors), Collections.singletonMap("errors", errors));
        this.errors = errors;
    }

    public static ValidationException of(String field, String message)
    {
        List<Map<String, String>> errors = new ArrayList<>();
        errors.add(error(field, message));
        return new ValidationException(errors);
    }

    public static Map<String, String> error(String field, String message)
    {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("field", field);
        e.put("message", message);
        return e;
    }

    public List<Map<String, String>> getErrors()
    {
        return errors;
    }

    private static String summary(List<Map<String, String>> errors)
    {
        if (errors.isEmpty()) {
            return "参数校验失败";
        }
        Map<String, String> first = errors.get(0);
        String more = errors.size() > 1 ? "（共 " + errors.size() + " 处错误）" : "";
        return "参数校验失败：" + first.get("field") + " " + first.get("message") + more;
    }
}
