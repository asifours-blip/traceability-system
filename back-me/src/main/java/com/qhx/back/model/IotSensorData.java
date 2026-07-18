package com.qhx.back.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("iot_sensor_data")
public class IotSensorData {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String batchId;
    private String sensorType;
    private BigDecimal sensorValue;
    private String unit;
    private Date collectTime;
    private String txHash;
    private Date createdAt;
}