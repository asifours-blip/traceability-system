package com.qhx.back.iot;

import cn.hutool.core.util.StrUtil;
import com.qhx.back.model.IotSensorData;

import java.math.BigDecimal;

/**
 * IoT 写入校验。模拟任务原先没有边界检查，缺 batchId / 空单位 / 超量程会直接入库。
 */
public final class IotSensorValidator {

    public static final BigDecimal TEMP_MIN = new BigDecimal("-40");
    public static final BigDecimal TEMP_MAX = new BigDecimal("85");
    public static final BigDecimal HUMIDITY_MIN = BigDecimal.ZERO;
    public static final BigDecimal HUMIDITY_MAX = new BigDecimal("100");
    public static final BigDecimal LIGHT_MIN = BigDecimal.ZERO;
    public static final BigDecimal LIGHT_MAX = new BigDecimal("200000");

    private IotSensorValidator() {
    }

    public static void requireValid(IotSensorData data) {
        if (data == null) {
            throw new IllegalArgumentException("IoT 数据为空");
        }
        if (StrUtil.isBlank(data.getBatchId())) {
            throw new IllegalArgumentException("缺少 batchId");
        }
        if (StrUtil.isBlank(data.getSensorType())) {
            throw new IllegalArgumentException("缺少 sensorType");
        }
        if (data.getSensorValue() == null) {
            throw new IllegalArgumentException("缺少 sensorValue");
        }
        if (StrUtil.isBlank(data.getUnit())) {
            throw new IllegalArgumentException("单位不能为空");
        }
        String type = data.getSensorType();
        BigDecimal value = data.getSensorValue();
        if ("temperature".equals(type) && outOfRange(value, TEMP_MIN, TEMP_MAX)) {
            throw new IllegalArgumentException("温度超量程，允许 [" + TEMP_MIN + "," + TEMP_MAX + "]℃");
        }
        if ("humidity".equals(type) && outOfRange(value, HUMIDITY_MIN, HUMIDITY_MAX)) {
            throw new IllegalArgumentException("湿度超量程，允许 [0,100]%");
        }
        if ("light".equals(type) && outOfRange(value, LIGHT_MIN, LIGHT_MAX)) {
            throw new IllegalArgumentException("光照超量程，允许 [0,200000]lux");
        }
    }

    private static boolean outOfRange(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) < 0 || value.compareTo(max) > 0;
    }
}
