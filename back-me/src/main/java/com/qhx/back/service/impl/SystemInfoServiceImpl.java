package com.qhx.back.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.qhx.back.model.to.SystemInfoTo;
import com.qhx.back.service.SystemInfoService;
import com.qhx.back.util.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
@Service
@Slf4j
public class SystemInfoServiceImpl implements SystemInfoService
{
    @Autowired
    private HttpUtil httpUtil;
    @Override
    public JSONObject getSystemInfo()
    {
        JSONArray resJson = httpUtil.call("getSystemInfo", List.of());
        JSONObject jsonObject = new JSONObject();
        jsonObject.putOpt("name", resJson.getStr(0));
        jsonObject.putOpt("version", resJson.getStr(1));
        jsonObject.putOpt("description", resJson.getStr(2));
        return jsonObject;
    }
    @Override
    public String clearSystemInfo()
    {
        String errMes = httpUtil.sendTransaction("clearSystemInfo", List.of());
        if (StrUtil.isNotEmpty(errMes))
        {
            return errMes;
        }
        return "清空系统信息成功";
    }
    @Override
    public String setSystemInfo(SystemInfoTo systemInfoTo)
    {
        String name = systemInfoTo.getName();
        String version = systemInfoTo.getVersion();
        String description = systemInfoTo.getDescription();
        String errMes = httpUtil.sendTransaction("setSystemInfo", List.of(name, version, description));
        if (StrUtil.isNotEmpty(errMes))
        {
            return errMes;
        }
        return "设置系统信息成功";
    }
}

