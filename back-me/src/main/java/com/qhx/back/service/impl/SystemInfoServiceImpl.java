package com.qhx.back.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.qhx.back.model.to.SystemInfoTo;
import com.qhx.back.service.ChainTxService;
import com.qhx.back.service.SystemInfoService;
import com.qhx.back.util.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
@Service
@Slf4j
public class SystemInfoServiceImpl implements SystemInfoService
{
    @Autowired
    private HttpUtil httpUtil;
    @Autowired
    private ChainTxService chainTxService;
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
    // 交易经 ChainTxService 发出：只有回执确认成功才返回，失败/未知抛 ChainTxException
    @Override
    public String clearSystemInfo()
    {
        chainTxService.submit("clearSystemInfo", List.of());
        return "清空系统信息成功";
    }
    @Override
    public String setSystemInfo(SystemInfoTo systemInfoTo)
    {
        String name = systemInfoTo.getName();
        String version = systemInfoTo.getVersion();
        String description = systemInfoTo.getDescription();
        chainTxService.submit("setSystemInfo", Arrays.asList(name, version, description));
        return "设置系统信息成功";
    }
}

