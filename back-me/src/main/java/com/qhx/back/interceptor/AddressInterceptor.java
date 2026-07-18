package com.qhx.back.interceptor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.qhx.back.context.AddressContext;
import com.qhx.back.model.Result;
import com.qhx.back.util.UserAddressUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
public class AddressInterceptor implements HandlerInterceptor {

    @Value("${allow.paths}")
    String allowPaths;

    private boolean isAllowPath(String path) {
        List<String> paths = Arrays.asList(allowPaths.split(","));
        for (String allowPath : paths) {
            if (path.contains(allowPath))
                return true;
        }
        return false;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 处理跨域请求
        handleCorsRequest(request, response);
        // 处理预检请求
        if (request.getMethod().equals("OPTIONS")) {
            response.setStatus(HttpServletResponse.SC_OK);
            return true;
        }
        if (isAllowPath(request.getRequestURI())) {
            return true;
        }
        String address = request.getHeader("address");
        if (StrUtil.isEmpty(address)) {
            handlerErrorResponse(response, "请求头 adddress 为空");
            return false;
            // return true;
        }
        if (!UserAddressUtil.isLegalAddress(address)) {
            handlerErrorResponse(response, "请求头 adddress 不合法");
            return false;
        }
        AddressContext.setAddress(address);
        return true;
    }
    private void handleCorsRequest(HttpServletRequest request, HttpServletResponse response) {
        // 允许所有来源的跨域请求
        response.setHeader("Access-Control-Allow-Origin", "*");

        // 允许的HTTP方法
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");

        // 允许的请求头
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, address");

        // 是否支持预检请求
        response.setHeader("Access-Control-Allow-Credentials", "true");

        // 预检请求的有效期（单位：秒）
        response.setHeader("Access-Control-Max-Age", "3600");
    }
    public void handlerErrorResponse(HttpServletResponse response, String mes) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(JSONUtil.toJsonStr(Result.error(mes)));
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        AddressContext.clear();
    }
}
