package com.qhx.back.context;

import com.qhx.back.model.UserAccount;

/**
 * 当前请求的登录账号，由拦截器根据 Bearer token 从服务端解析后写入。
 */
public class UserContext {
    private static final ThreadLocal<UserAccount> userHolder = new ThreadLocal<>();

    public static void setUser(UserAccount user) {
        userHolder.set(user);
    }

    public static UserAccount getUser() {
        return userHolder.get();
    }

    public static void clear() {
        userHolder.remove();
    }
}
