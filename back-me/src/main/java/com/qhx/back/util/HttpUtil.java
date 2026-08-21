package com.qhx.back.util;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.qhx.back.exception.WeBaseFrontException;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
@Component
@Slf4j
public class HttpUtil implements com.qhx.back.client.WeBaseClient {


    @Value("${webase-front.url}")
    public String URL;
    @Value("${contract.address}")
    public String CONTRACT_ADDRESS;
    @Value("${contract.owner}")
    public String OWNER;
    @Value("${contract.name}")
    public String CONTRACT_NAME;
    @Value("${contract.abi}")
    public String CONTRACT_ABI;

    public String contractRequest(String userAddress, String funcName, List<Object> params) {
        JSONObject requestBody = new JSONObject();
        requestBody.putOpt("contractName", CONTRACT_NAME);
        requestBody.putOpt("contractAddress", CONTRACT_ADDRESS);
        requestBody.putOpt("contractAbi", JSONUtil.parseArray(CONTRACT_ABI));
        requestBody.putOpt("user", "");
        requestBody.putOpt("funcName", funcName);
        requestBody.putOpt("funcParam", params);
        requestBody.putOpt("user", userAddress);
        return httpPost(requestBody.toString());
    }

    public JSONArray call(String funcName){
        return call(OWNER, funcName, new ArrayList<>());
    }

    public JSONArray call(String funcName, List<Object> params) {
        return call(OWNER, funcName, params);

    }

    public JSONArray call(String userAddress, String funcName, List<Object> params) {
        String response = contractRequest(userAddress, funcName, params);
        try {
            JSONArray resJson = JSONUtil.parseArray(response);
            return resJson;
        } catch (Exception e) {
            throw new WeBaseFrontException(e);
        }

    }

    public String sendTransaction(String funcName, List<Object> params) {
        return sendTransaction(OWNER, funcName, params);
    }

    public String sendTransaction(String userAddress, String funcName, List<Object> params) {
        String response = contractRequest(userAddress, funcName, params);
        try {
            JSONObject resJson = JSONUtil.parseObj(response);
            if (resJson.getBool("statusOK")) {
                return null;
            } else {
                throw new WeBaseFrontException(resJson.getStr("message"));
            }
        } catch (WeBaseFrontException e) {
            throw e;
        } catch (Exception e) {
            throw new WeBaseFrontException(e);
        }
    }
    private String httpPost(String jsonStr) {
        // 创建httpClient
        CloseableHttpClient httpClient = HttpClients.createDefault();
        // 创建post请求方式实例
        HttpPost httpPost = new HttpPost(URL + "/trans/handle");
        // 设置请求头 发送的是json数据格式
        httpPost.setHeader("Content-type", "application/json;charset=utf-8");
        // 设置参数---设置消息实体 也就是携带的数据
        StringEntity entity = new StringEntity(jsonStr, Charset.forName("UTF-8"));
        // 设置编码格式
        entity.setContentEncoding("UTF-8");
        // 发送Json格式的数据请求
        entity.setContentType("application/json");
        // 把请求消息实体塞进去
        httpPost.setEntity(entity);
        // 执行http的post请求
        CloseableHttpResponse httpResponse;
        String result = null;
        try {
            httpResponse = httpClient.execute(httpPost);
            result = EntityUtils.toString(httpResponse.getEntity(), "UTF-8");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

}
