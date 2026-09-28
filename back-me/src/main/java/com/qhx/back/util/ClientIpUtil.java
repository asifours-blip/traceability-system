package com.qhx.back.util;

import cn.hutool.core.util.StrUtil;

import javax.servlet.http.HttpServletRequest;

/**
 * 取客户端 IP：优先 X-Forwarded-For 的第一段（反向代理场景），否则用 remoteAddr。
 * 仅用于登录限流的统计维度，不做安全校验，调用方不应把它当作可信身份。
 */
public class ClientIpUtil
{
    public static String resolve(HttpServletRequest request)
    {
        if (request == null) {
            return "";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote;
    }
}
