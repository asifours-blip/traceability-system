package com.qhx.back.controller;

import com.qhx.back.model.Result;
import com.qhx.back.service.ChainTxService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Api(tags = "交易记录与查证")
public class ChainTxController {
    @Autowired
    private ChainTxService chainTxService;

    // 查看一笔交易记录（签名者本人或管理员）
    @GetMapping("/chain-tx/{id}")
    @ApiOperation(value = "交易记录")
    public Result get(@PathVariable Long id) {
        return Result.success(chainTxService.get(id));
    }

    // 查证结果未知的交易：有哈希查回执，没有回执的阶段交易读链判断
    @PostMapping("/chain-tx/{id}/verify")
    @ApiOperation(value = "查证交易")
    public Result verify(@PathVariable Long id) {
        return Result.success(chainTxService.verify(id));
    }
}
