package com.qhx.back.task;

import com.qhx.back.model.IotSensorData;
import com.qhx.back.service.IotSensorDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IotDataSimulatorTaskTest {

    @Mock
    private IotSensorDataService iotSensorDataService;

    @Test
    void 模拟任务写入三批次温湿度光照() {
        IotDataSimulatorTask task = new IotDataSimulatorTask(iotSensorDataService);
        task.simulateIotData();

        ArgumentCaptor<IotSensorData> captor = ArgumentCaptor.forClass(IotSensorData.class);
        verify(iotSensorDataService, times(9)).save(captor.capture());
        List<IotSensorData> rows = captor.getAllValues();

        Set<String> batches = rows.stream().map(IotSensorData::getBatchId).collect(Collectors.toSet());
        assertEquals(Set.of("SY60202600001", "SY60202600002", "SY60202600003"), batches);

        Set<String> types = rows.stream().map(IotSensorData::getSensorType).collect(Collectors.toSet());
        assertEquals(Set.of("temperature", "humidity", "light"), types);

        for (IotSensorData row : rows) {
            assertNotNull(row.getCollectTime());
            assertNotNull(row.getSensorValue());
            switch (row.getSensorType()) {
                case "temperature":
                    assertEquals("℃", row.getUnit());
                    assertInRange(row.getSensorValue(), "15", "35");
                    break;
                case "humidity":
                    assertEquals("%", row.getUnit());
                    assertInRange(row.getSensorValue(), "30", "90");
                    break;
                case "light":
                    assertEquals("lux", row.getUnit());
                    assertInRange(row.getSensorValue(), "0", "1000");
                    break;
                default:
                    throw new AssertionError(row.getSensorType());
            }
        }
    }

    private static void assertInRange(BigDecimal value, String min, String max) {
        assertTrue(value.compareTo(new BigDecimal(min)) >= 0, value::toPlainString);
        assertTrue(value.compareTo(new BigDecimal(max)) <= 0, value::toPlainString);
    }
}
