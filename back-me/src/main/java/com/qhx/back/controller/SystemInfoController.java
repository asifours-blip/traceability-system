package com.qhx.back.controller;

import com.qhx.back.model.Result;
import com.qhx.back.model.to.SystemInfoTo;
import com.qhx.back.service.SystemInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class SystemInfoController
{

    @Autowired
    private SystemInfoService systemInfoService;

    @GetMapping("/getSystemInfo")
    public Result getSystemInfo()
    {
        return Result.success(systemInfoService.getSystemInfo());
    }

    @PostMapping("/clearSystemInfo")
    public Result clearSystemInfo()
    {
        return Result.success(systemInfoService.clearSystemInfo());
    }

    @PostMapping("/setSystemInfo")
    public Result setSystemInfo(@RequestBody SystemInfoTo systemInfoTo)
    {
        return Result.success(systemInfoService.setSystemInfo(systemInfoTo));
    }
}
