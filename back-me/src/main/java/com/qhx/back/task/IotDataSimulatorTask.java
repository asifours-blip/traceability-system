package com.qhx.back.task;

import com.qhx.back.model.IotSensorData;
import com.qhx.back.service.IotSensorDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.Random;
@Slf4j
@Component
@EnableScheduling
public class IotDataSimulatorTask {

    private final IotSensorDataService iotSensorDataService;

    public IotDataSimulatorTask(IotSensorDataService iotSensorDataService) {
        this.iotSensorDataService = iotSensorDataService;
    }
    private String[] batchIds = {"SY60202600001", "SY60202600002", "SY60202600003"};
    private Random random = new Random();

    @Scheduled(fixedDelay = 300000) // 每5分钟执行一次
    public void simulateIotData() {
        log.info("开始生成模拟物联网数据...");
        for (String batchId : batchIds) {
            // 温度（15~35℃）
            BigDecimal temperature = BigDecimal.valueOf(15 + random.nextDouble() * 20)
                    .setScale(1, RoundingMode.HALF_UP);
            saveData(batchId, "temperature", temperature, "℃");

            // 湿度（30%~90%）
            BigDecimal humidity = BigDecimal.valueOf(30 + random.nextDouble() * 60)
                    .setScale(1, RoundingMode.HALF_UP);
            saveData(batchId, "humidity", humidity, "%");

            // 光照（0~1000 lux）
            BigDecimal light = BigDecimal.valueOf(random.nextDouble() * 1000)
                    .setScale(0, RoundingMode.HALF_UP);
            saveData(batchId, "light", light, "lux");
        }
    }

    private void saveData(String batchId, String type, BigDecimal value, String unit) {
        IotSensorData data = new IotSensorData();
        data.setBatchId(batchId);
        data.setSensorType(type);
        data.setSensorValue(value);
        data.setUnit(unit);
        data.setCollectTime(new Date());
        iotSensorDataService.save(data);
        log.info("保存数据：批次={}, 类型={}, 值={}{}", batchId, type, value, unit);
    }
}