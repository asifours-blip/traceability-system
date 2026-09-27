package com.qhx.back.trace;

import cn.hutool.core.util.StrUtil;
import com.qhx.back.chain.TraceStage;
import com.qhx.back.exception.ValidationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 业务字段校验。收集全部错误后一次抛出 ValidationException（HTTP 400，data.errors 逐字段说明）。
 * 纯函数，不连链；依赖链上数据的规则（零售数量不超过分销数量、日期不早于上一阶段）由 BatchService 读链后调用。
 */
public final class TraceValidator {

    /**
     * 溯源号：大写字母开头，只含大写字母、数字、连字符，4-64 位。
     * 仓库原来没有生成规则（生产商手填），已有样例 SY60202600001 / LC-3 / E2E-xxx 都符合；
     * 它会出现在二维码 URL 路径里，所以不允许空格、斜杠、小写（避免 sy001 与 SY001 成为两个批次）。
     */
    public static final Pattern TRACE_NUMBER = Pattern.compile("^[A-Z][A-Z0-9-]{3,63}$");
    private static final Pattern CID = Pattern.compile("^[A-Za-z0-9]+$");
    private static final BigDecimal MAX_INT = new BigDecimal("1000000000000");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    /** 业务日期按中国时区判断是否晚于今天 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final List<Map<String, String>> errors = new ArrayList<>();

    public static TraceValidator create() {
        return new TraceValidator();
    }

    public static boolean isTraceNumber(String value) {
        return value != null && TRACE_NUMBER.matcher(value).matches();
    }

    public TraceValidator traceNumber(String field, String value) {
        if (StrUtil.isBlank(value)) {
            return add(field, "不能为空");
        }
        if (!isTraceNumber(value)) {
            return add(field, "格式不正确：大写字母开头，只能包含大写字母、数字、连字符，长度 4-64，例如 SY20260928001");
        }
        return this;
    }

    public TraceValidator required(String field, String value) {
        if (StrUtil.isBlank(value)) {
            return add(field, "不能为空");
        }
        return this;
    }

    /** 按字段清单校验一个阶段的全部字段；values 以字段名取值 */
    public TraceValidator stageFields(TraceStage stage, Map<String, Object> values) {
        for (TraceFields.Field f : TraceFields.of(stage)) {
            field(f, values.get(f.name));
        }
        return this;
    }

    /** 按类型校验单个字段 */
    public TraceValidator field(TraceFields.Field f, Object value) {
        switch (f.type) {
            case POSITIVE_INT:
                return positiveInt(f.name, value);
            case DATE:
                return date(f.name, value == null ? null : String.valueOf(value));
            case CID:
                text(f.name, value, f.type.maxLength);
                if (!hasError(f.name) && !CID.matcher(String.valueOf(value)).matches()) {
                    add(f.name, "不是合法的 IPFS CID（只能包含字母和数字）");
                }
                return this;
            default:
                return text(f.name, value, f.type.maxLength);
        }
    }

    public TraceValidator text(String field, Object value, int maxLength) {
        String s = value == null ? null : String.valueOf(value);
        if (StrUtil.isBlank(s)) {
            return add(field, "不能为空");
        }
        if (s.length() > maxLength) {
            return add(field, "长度不能超过 " + maxLength);
        }
        return this;
    }

    public TraceValidator positiveInt(String field, Object value) {
        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
            return add(field, "不能为空");
        }
        BigDecimal n = toDecimal(value);
        if (n == null) {
            return add(field, "必须是数字");
        }
        if (n.signum() <= 0) {
            return add(field, "必须是正整数（大于 0）");
        }
        if (n.stripTrailingZeros().scale() > 0) {
            return add(field, "必须是正整数，不能有小数");
        }
        if (n.compareTo(MAX_INT) > 0) {
            return add(field, "不能超过 " + MAX_INT.toPlainString());
        }
        return this;
    }

    public TraceValidator date(String field, String value) {
        if (StrUtil.isBlank(value)) {
            return add(field, "不能为空");
        }
        LocalDate d = parseDate(value);
        if (d == null) {
            return add(field, "日期格式不正确，应为 yyyy-MM-dd 且是真实存在的日期");
        }
        if (d.isAfter(LocalDate.now(ZONE))) {
            return add(field, "不能晚于今天");
        }
        return this;
    }

    /** 本阶段日期不能早于上一阶段的日期；任一无法解析时不在这里报错（格式错误由 date() 负责） */
    public TraceValidator notBefore(String field, String value, String previousLabel, String previousValue) {
        LocalDate d = parseDate(value);
        LocalDate prev = parseDate(previousValue);
        if (d != null && prev != null && d.isBefore(prev)) {
            add(field, "不能早于" + previousLabel + "（" + previousValue + "）");
        }
        return this;
    }

    /** 本阶段数量不能超过上一阶段的数量 */
    public TraceValidator notMoreThan(String field, Object value, String previousLabel, long previous) {
        BigDecimal n = toDecimal(value);
        if (n != null && n.compareTo(BigDecimal.valueOf(previous)) > 0) {
            add(field, "不能超过" + previousLabel + "（" + previous + "）");
        }
        return this;
    }

    public TraceValidator add(String field, String message) {
        errors.add(ValidationException.error(field, message));
        return this;
    }

    public boolean hasError(String field) {
        return errors.stream().anyMatch(e -> field.equals(e.get("field")));
    }

    public void throwIfInvalid() {
        if (!errors.isEmpty()) {
            throw new ValidationException(new ArrayList<>(errors));
        }
    }

    public static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 已通过 positiveInt 校验的值转成 Long，作为合约 uint 参数 */
    public static Long toLong(Object value) {
        BigDecimal n = toDecimal(value);
        return n == null ? null : n.longValueExact();
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
