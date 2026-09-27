package com.qhx.back.annotation;

import com.qhx.back.enums.UserRole;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口所需的账号角色，由 AddressInterceptor 在登录校验之后检查，不符返回 403。
 * 合约里的 onlyProducer 等检查照旧保留，作为第二道防线。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    UserRole[] value();
}
