package com.qhx.back.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qhx.back.model.IotSensorData;
import java.util.List;

public interface IotSensorDataService extends IService<IotSensorData> {
    List<IotSensorData> getLatestByBatchId(String batchId, int limit);
}