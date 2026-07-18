package com.qhx.back.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qhx.back.model.IotSensorData;
import com.qhx.back.mapper.IotSensorDataMapper;
import com.qhx.back.service.IotSensorDataService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class IotSensorDataServiceImpl extends ServiceImpl<IotSensorDataMapper, IotSensorData> implements IotSensorDataService {

    @Override
    public List<IotSensorData> getLatestByBatchId(String batchId, int limit) {
        LambdaQueryWrapper<IotSensorData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotSensorData::getBatchId, batchId)
                .orderByDesc(IotSensorData::getCollectTime)
                .last("limit " + limit);
        return this.list(wrapper);
    }
}