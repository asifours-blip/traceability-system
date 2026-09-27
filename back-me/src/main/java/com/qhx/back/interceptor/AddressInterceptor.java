package com.qhx.back.interceptor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.qhx.back.annotation.RequireRole;
import com.qhx.back.context.AddressContext;
import com.qhx.back.context.UserContext;
import com.qhx.back.enums.UserRole;
import com.qhx.back.model.Result;
import com.qhx.back.model.UserAccount;
import com.qhx.back.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 登录校验 + 角色鉴权。
 * 身份只来自 Authorization: Bearer token 在服务端查到的账号；
 * AddressContext 写入的是账号绑定的链上地址，客户端传来的 address 头一律忽略。
 */
@Component
public class AddressInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final UrlPathHelper urlPathHelper = new UrlPathHelper();

    // 免登录路径，Ant 风格精确匹配（不再用 contains，避免 /x;/login 之类的绕过）
    @Value("${allow.paths}")
    String allowPaths;

    @Autowired
    private AuthService authService;

    private boolean isAllowPath(HttpServletRequest request) {
        // 与 Spring MVC 路由一致：解码并去掉 ;jsessionid 之类的分号参数
        String path = urlPathHelper.getLookupPathForRequest(request);
        for (String allowPath : allowPaths.split(",")) {
            String pattern = allowPath.trim();
            if (!pattern.isEmpty() && pathMatcher.match(pattern, path)) {
                return true;
            }
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
        if (isAllowPath(request)) {
            return true;
        }
        String token = resolveToken(request);
        if (StrUtil.isEmpty(token)) {
            handlerErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录：缺少 Authorization Bearer token");
            return false;
        }
        UserAccount user = authService.authenticate(token);
        if (user == null) {
            handlerErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "登录已失效，请重新登录");
            return false;
        }
        UserContext.setUser(user);
        AddressContext.setAddress(user.getChainAddress());
        if (!hasRequiredRole(handler, user)) {
            handlerErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "当前角色无权访问该接口");
            return false;
        }
        return true;
    }

    public static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    private boolean hasRequiredRole(Object handler, UserAccount user) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (requireRole == null) {
            return true;
        }
        for (UserRole role : requireRole.value()) {
            if (role.name().equals(user.getRole())) {
                return true;
            }
        }
        return false;
    }

    public void handlerErrorResponse(HttpServletResponse response, int status, String mes) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(status);
        response.getWriter().write(JSONUtil.toJsonStr(new Result(null, mes, status)));
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        AddressContext.clear();
        UserContext.clear();
    }
}
