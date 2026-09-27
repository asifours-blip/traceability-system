package com.qhx.back.controller;

import com.qhx.back.annotation.RequireRole;
import com.qhx.back.chain.TraceStage;
import com.qhx.back.enums.UserRole;
import com.qhx.back.model.Result;
import com.qhx.back.model.to.AssignTo;
import com.qhx.back.model.to.CorrectionTo;
import com.qhx.back.service.BatchService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次归属、交接与更正。权限全部在 BatchService 里按账号与批次关系校验，前端隐藏按钮只是体验。
 */
@RestController
@Api(tags = "批次")
@RequireRole({UserRole.PRODUCER, UserRole.DISTRIBUTOR, UserRole.RETAILER, UserRole.ADMIN})
public class BatchController {
    @Autowired
    private BatchService batchService;

    // 当前账号名下的批次：生产商看自己建档的，分销商/零售商看指定给自己的，管理员看全部
    @GetMapping("/batches")
    @ApiOperation(value = "我的批次")
    public Result list() {
        return Result.success(batchService.list());
    }

    // 批次详情：链上数据、各阶段交易状态、交接历史、更正
    @GetMapping("/batches/{traceNumber}")
    @ApiOperation(value = "批次详情")
    public Result detail(@PathVariable String traceNumber) {
        return Result.success(batchService.detail(traceNumber));
    }

    // 生产商变更分销商（分销阶段写入前）
    @PutMapping("/batches/{traceNumber}/distributor")
    @RequireRole(UserRole.PRODUCER)
    @ApiOperation(value = "指定分销商")
    public Result assignDistributor(@PathVariable String traceNumber, @RequestBody AssignTo to) {
        return Result.success(batchService.reassign(traceNumber, TraceStage.DISTRIBUTION, to));
    }

    // 分销商指定/变更零售商（零售阶段写入前）
    @PutMapping("/batches/{traceNumber}/retailer")
    @RequireRole(UserRole.DISTRIBUTOR)
    @ApiOperation(value = "指定零售商")
    public Result assignRetailer(@PathVariable String traceNumber, @RequestBody AssignTo to) {
        return Result.success(batchService.reassign(traceNumber, TraceStage.RETAIL, to));
    }

    // 追加链下更正；只追加，不提供修改与删除
    @PostMapping("/batches/{traceNumber}/corrections")
    @RequireRole({UserRole.PRODUCER, UserRole.DISTRIBUTOR, UserRole.RETAILER})
    @ApiOperation(value = "追加更正")
    public Result addCorrection(@PathVariable String traceNumber, @RequestBody CorrectionTo to) {
        return Result.success(batchService.addCorrection(traceNumber, to));
    }

    // 可指定的交接对象：生产商查分销商，分销商查零售商
    @GetMapping("/partners")
    @ApiOperation(value = "可指定的交接对象")
    public Result partners(@RequestParam String role) {
        return Result.success(batchService.partners(role));
    }
}
