package com.qhx.back.client;

import cn.hutool.json.JSONArray;

import java.util.List;

/**
 * WeBASE-Front 调用端口。生产实现是 HttpUtil；测试里 Mock，不连链。
 */
public interface WeBaseClient {

    JSONArray call(String funcName);

    JSONArray call(String funcName, List<Object> params);

    JSONArray call(String userAddress, String funcName, List<Object> params);

    String sendTransaction(String funcName, List<Object> params);

    String sendTransaction(String userAddress, String funcName, List<Object> params);
}
