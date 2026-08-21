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
        // CORS 只走 WebConfig，避免拦截器再写一套 ACAO
        if (request.getMethod().equals("OPTIONS")) {
            response.setStatus(HttpServletResponse.SC_OK);
            return true;
        }
        if (isAllowPath(request.getRequestURI())) {
            return true;
        }
        String address = request.getHeader("address");
        if (StrUtil.isEmpty(address)) {
            handlerErrorResponse(response, "请求头 address 为空");
            return false;
        }
        if (!UserAddressUtil.isLegalAddress(address)) {
            handlerErrorResponse(response, "请求头 address 不合法");
            return false;
        }
        AddressContext.setAddress(address);
        return true;
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
