package com.qhx.back.controller;

import com.qhx.back.annotation.RequireRole;
import com.qhx.back.enums.UserRole;
import com.qhx.back.model.Result;
import com.qhx.back.model.to.DistributorTo;
import com.qhx.back.model.to.ProducerTo;
import com.qhx.back.model.to.RetailerTo;
import com.qhx.back.service.BatchService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@Api(tags = "溯源接口")
public class TraceController {
    @Autowired
    private BatchService batchService;

    // 消费者扫码详情（免登录）：只返回公开字段，见 docs/business-flow.md「公开字段」
    @GetMapping("/trace/detail/{traceNumber}")
    @ApiOperation(value = "溯源信息（公开字段）")
    public Result getTrace(@PathVariable String traceNumber) {
        return Result.success(batchService.publicDetail(traceNumber));
    }

    // 消费者读取某阶段已绑定的文件（免登录）：CID 从链上该溯源号该阶段的字段读出，调用方不能指定任意 CID
    @GetMapping("/trace/{traceNumber}/file/{stage}")
    @ApiOperation(value = "读取已绑定到溯源号的公开文件（production / distribution）")
    public ResponseEntity<byte[]> getPublicFile(@PathVariable String traceNumber, @PathVariable String stage) {
        BatchService.PublicFile file = batchService.publicFile(traceNumber, stage);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType))
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes);
    }

    // 生产商录入生产信息并指定下游分销商；回执确认成功才返回 200，data 为交易记录（含哈希与块高）
    @PostMapping("/producer/add")
    @RequireRole(UserRole.PRODUCER)
    @ApiOperation(value = "生产商录入生产信息")
    public Result addProducer(@RequestBody ProducerTo producerTO) {
        return Result.success(batchService.submitProduction(producerTO));
    }

    // 被指定的分销商录入分销信息并指定下游零售商
    @PostMapping("/distributor/add")
    @RequireRole(UserRole.DISTRIBUTOR)
    @ApiOperation(value = "分销商录入分销信息")
    public Result addDistributor(@RequestBody DistributorTo distributorTO) {
        return Result.success(batchService.submitDistribution(distributorTO));
    }

    // 被指定的零售商录入零售信息
    @PostMapping("/retailer/add")
    @RequireRole(UserRole.RETAILER)
    @ApiOperation(value = "零售商录入零售信息")
    public Result addRetailer(@RequestBody RetailerTo retailerTO) {
        return Result.success(batchService.submitRetail(retailerTO));
    }
}
