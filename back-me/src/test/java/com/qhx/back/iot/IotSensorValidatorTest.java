package com.qhx.back.iot;

import com.qhx.back.model.IotSensorData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IotSensorValidatorTest {

    @Test
    void 正常温度通过() {
        assertDoesNotThrow(() -> IotSensorValidator.requireValid(row("B1", "temperature", "20", "℃")));
    }

    @Test
    void 缺batchId() {
        IotSensorData data = row("B1", "temperature", "20", "℃");
        data.setBatchId("  ");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> IotSensorValidator.requireValid(data));
        assertTrue(ex.getMessage().contains("batchId"));
    }

    @Test
    void 空单位() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> IotSensorValidator.requireValid(row("B1", "humidity", "40", "")));
        assertTrue(ex.getMessage().contains("单位"));
    }

    @Test
    void 温度超量程() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> IotSensorValidator.requireValid(row("B1", "temperature", "120", "℃")));
        assertTrue(ex.getMessage().contains("超量程"));
    }

    @Test
    void 湿度超量程() {
        assertThrows(IllegalArgumentException.class,
                () -> IotSensorValidator.requireValid(row("B1", "humidity", "101", "%")));
    }

    private static IotSensorData row(String batchId, String type, String value, String unit) {
        IotSensorData data = new IotSensorData();
        data.setBatchId(batchId);
        data.setSensorType(type);
        data.setSensorValue(new BigDecimal(value));
        data.setUnit(unit);
        return data;
    }
}
