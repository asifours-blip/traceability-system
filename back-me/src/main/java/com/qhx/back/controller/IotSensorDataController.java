package com.qhx.back.controller;

import com.qhx.back.common.Result;
import com.qhx.back.model.IotSensorData;
import com.qhx.back.service.IotSensorDataService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/iot")
public class IotSensorDataController {

    private final IotSensorDataService iotSensorDataService;
    public IotSensorDataController(IotSensorDataService iotSensorDataService) {
        this.iotSensorDataService = iotSensorDataService;
    }

    @GetMapping("/data")
    public Result<List<IotSensorData>> getSensorData(@RequestParam String batchId,
                                                     @RequestParam(defaultValue = "10") int limit) {
        List<IotSensorData> data = iotSensorDataService.getLatestByBatchId(batchId, limit);
        return Result.success(data);
    }
}