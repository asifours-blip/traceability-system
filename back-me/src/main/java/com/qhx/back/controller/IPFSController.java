package com.qhx.back.controller;

import com.qhx.back.annotation.RequireRole;
import com.qhx.back.enums.UserRole;
import com.qhx.back.model.Result;
import com.qhx.back.service.FileService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传。只保留 multipart 流式上传：原 /uploadBase64 要把整个文件放进 JSON 字符串再整体解码，内存占用约为文件的 2~3 倍，已删除；
 * 按任意 CID 读文件的 /file/{hash}、/fileBase64/{hash} 也已删除，文件只能经 /trace/{溯源号}/file/{阶段} 按链上绑定读取。
 */
@RestController
@Api(tags = "文件")
public class IPFSController
{
    @Autowired
    private FileService fileService;

    // 只有需要上传生产认证 / 质检报告的角色能上传
    @PostMapping("/upload")
    @RequireRole({UserRole.PRODUCER, UserRole.DISTRIBUTOR})
    @ApiOperation(value = "上传文件（PNG/JPEG/WEBP/PDF，按内容判定类型）")
    public Result upload(@RequestParam("file") MultipartFile file)
    {
        return Result.success(fileService.upload(file));
    }
}
