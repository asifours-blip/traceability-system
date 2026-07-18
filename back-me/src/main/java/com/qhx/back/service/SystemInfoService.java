package com.qhx.back.service;

import cn.hutool.json.JSONObject;
import com.qhx.back.model.to.SystemInfoTo;
public interface SystemInfoService
{
    JSONObject getSystemInfo();

    String clearSystemInfo();

    String setSystemInfo(SystemInfoTo systemInfoTo);
}
