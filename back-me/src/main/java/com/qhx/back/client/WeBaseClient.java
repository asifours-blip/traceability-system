package com.qhx.back.client;

import cn.hutool.json.JSONArray;

import java.util.List;

/**
 * WeBASE-Front 调用端口。生产实现是 HttpUtil；测试里 Mock，不连链。
 */
public interface WeBaseClient {

    JSONArray call(String funcName);

    JSONArray call(String funcName, List<Object> params);

    /**
     * 发送交易。签名地址只取 AddressContext（当前登录账号在服务端绑定的地址），
     * 不接受调用方传入签名地址，避免请求体/请求头里的地址被拿去签名。
     */
    String sendTransaction(String funcName, List<Object> params);
}
