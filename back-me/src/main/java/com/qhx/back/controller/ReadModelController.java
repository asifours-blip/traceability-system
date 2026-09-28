package com.qhx.back.controller;

import com.qhx.back.annotation.RequireRole;
import com.qhx.back.context.UserContext;
import com.qhx.back.enums.UserRole;
import com.qhx.back.model.Result;
import com.qhx.back.model.vo.PageQuery;
import com.qhx.back.service.FileService;
import com.qhx.back.service.ReadModelService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;

/**
 * 管理员：重建读模型、查看未认领批次、手动触发孤儿文件清理。
 */
@RestController
@Api(tags = "读模型（管理员）")
@RequireRole(UserRole.ADMIN)
public class ReadModelController
{
    @Autowired
    private ReadModelService readModelService;
    @Autowired
    private FileService fileService;

    // 从链上完整重建读模型（幂等）；同时回填旧批次归属、补齐文件绑定。同步执行，返回报告
    @PostMapping("/admin/read-model/rebuild")
    @ApiOperation(value = "重建读模型")
    public Result rebuild()
    {
        return Result.success(readModelService.rebuild(UserContext.getUser().getId()));
    }

    // 未认领（没有归属记录）与部分认领（某阶段写入者对不上）的批次
    @GetMapping("/admin/read-model/unclaimed")
    @ApiOperation(value = "未认领批次")
    public Result unclaimed(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size)
    {
        return Result.success(readModelService.unclaimed(PageQuery.of(page, size)));
    }

    // 立即执行一次孤儿文件清理（定时任务之外的手动入口）
    @PostMapping("/admin/files/cleanup-orphans")
    @ApiOperation(value = "清理孤儿文件")
    public Result cleanupOrphans()
    {
        return Result.success(fileService.cleanupOrphans(new Date()));
    }
}
