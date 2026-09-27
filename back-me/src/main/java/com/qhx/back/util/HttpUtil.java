package com.qhx.back.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.qhx.back.chain.TxOutcome;
import com.qhx.back.chain.WeBaseResponses;
import com.qhx.back.context.AddressContext;
import com.qhx.back.exception.WeBaseFrontException;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
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
    @Value("${webase-front.group-id:1}")
    public int GROUP_ID = 1;
    // 连接超时：连不上说明请求没有送达，交易一定没有发出
    @Value("${webase-front.connect-timeout-ms:3000}")
    public int connectTimeoutMs = 3000;
    // 读取超时：必须大于 WeBASE-Front 的 constant.transMaxWait（默认 30 秒），让 WeBASE 自己的回执超时先返回
    @Value("${webase-front.read-timeout-ms:40000}")
    public int readTimeoutMs = 40000;

    private volatile CloseableHttpClient httpClient;

    // 共享一个连接池客户端；关闭自动重试：POST 交易一旦发出就不能由客户端悄悄重发
    private CloseableHttpClient client() {
        CloseableHttpClient c = httpClient;
        if (c == null) {
            synchronized (this) {
                if (httpClient == null) {
                    RequestConfig config = RequestConfig.custom()
                            .setConnectTimeout(connectTimeoutMs)
                            .setConnectionRequestTimeout(connectTimeoutMs)
                            .setSocketTimeout(readTimeoutMs)
                            .build();
                    httpClient = HttpClients.custom()
                            .setDefaultRequestConfig(config)
                            .disableAutomaticRetries()
                            .setMaxConnTotal(50)
                            .setMaxConnPerRoute(50)
                            .build();
                }
                c = httpClient;
            }
        }
        return c;
    }

    @PreDestroy
    public void close() throws IOException {
        CloseableHttpClient c = httpClient;
        httpClient = null;
        if (c != null) {
            c.close();
        }
    }

    public JSONArray call(String funcName){
        return call(OWNER, funcName, new ArrayList<>());
    }

    public JSONArray call(String funcName, List<Object> params) {
        return call(OWNER, funcName, params);

    }

    // 只读调用不签名，user 固定为 owner
    private JSONArray call(String userAddress, String funcName, List<Object> params) {
        RawResponse response;
        try {
            response = execute(post("/trans/handle", requestBody(userAddress, funcName, params)));
        } catch (IOException e) {
            throw new WeBaseFrontException("调用 WeBASE-Front 失败：" + e);
        }
        if (response.status != 200) {
            throw new WeBaseFrontException("WeBASE-Front 返回 HTTP " + response.status + "：" + StrUtil.maxLength(response.body, 200));
        }
        try {
            return JSONUtil.parseArray(response.body);
        } catch (Exception e) {
            throw new WeBaseFrontException(e);
        }
    }

    @Override
    public TxOutcome sendTransaction(String funcName, List<Object> params) {
        // 签名地址只来自服务端会话绑定的地址（拦截器写入 AddressContext）
        String signer = AddressContext.getAddress();
        if (!UserAddressUtil.isLegalAddress(signer)) {
            throw new IllegalStateException("当前会话没有绑定合法的链上地址，拒绝发送交易");
        }
        HttpPost request = post("/trans/handle", requestBody(signer, funcName, params));
        RawResponse response;
        try {
            response = execute(request);
        } catch (ConnectTimeoutException | ConnectException | UnknownHostException e) {
            // 连接阶段失败：请求没有送达 WeBASE-Front
            log.warn("WeBASE-Front 不可达，交易 {} 未发出", funcName, e);
            return TxOutcome.notSent(e.toString());
        } catch (SocketTimeoutException e) {
            log.warn("等待 WeBASE-Front 响应超时，交易 {} 结果未知", funcName, e);
            return TxOutcome.unknown(null, null, "读取 WeBASE-Front 响应超时（>" + readTimeoutMs + "ms），交易可能已发出");
        } catch (IOException e) {
            log.warn("WeBASE-Front 响应中断，交易 {} 结果未知", funcName, e);
            return TxOutcome.unknown(null, null, "WeBASE-Front 响应在完成前中断（" + e + "），交易可能已发出");
        }
        TxOutcome outcome = WeBaseResponses.classifyTransHandle(response.status, response.body);
        if (outcome.getKind() != TxOutcome.Kind.CONFIRMED) {
            log.warn("交易 {} 未确认成功：{}", funcName, outcome);
        }
        return outcome;
    }

    @Override
    public TxOutcome queryReceipt(String txHash) {
        if (txHash == null || !txHash.matches("^0x[0-9a-fA-F]{64}$")) {
            return TxOutcome.unknown(txHash, null, "交易哈希格式不正确，无法查回执");
        }
        RawResponse response;
        try {
            response = execute(new HttpGet(URL + "/" + GROUP_ID + "/web3/transactionReceipt/" + txHash));
        } catch (IOException e) {
            return TxOutcome.unknown(txHash, null, "查回执失败：" + e);
        }
        return WeBaseResponses.classifyReceiptQuery(response.status, response.body, txHash);
    }

    private String requestBody(String userAddress, String funcName, List<Object> params) {
        JSONObject requestBody = new JSONObject();
        requestBody.putOpt("groupId", GROUP_ID);
        requestBody.putOpt("contractName", CONTRACT_NAME);
        requestBody.putOpt("contractAddress", CONTRACT_ADDRESS);
        requestBody.putOpt("contractAbi", JSONUtil.parseArray(CONTRACT_ABI));
        requestBody.putOpt("funcName", funcName);
        requestBody.putOpt("funcParam", params);
        requestBody.putOpt("user", userAddress);
        return requestBody.toString();
    }

    private HttpPost post(String path, String json) {
        HttpPost httpPost = new HttpPost(URL + path);
        httpPost.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));
        return httpPost;
    }

    // 响应在 try-with-resources 中关闭；读取响应体中途出错时连接随之关闭，不回到连接池
    private RawResponse execute(HttpUriRequest request) throws IOException {
        try (CloseableHttpResponse response = client().execute(request)) {
            HttpEntity entity = response.getEntity();
            String body = entity == null ? null : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            return new RawResponse(response.getStatusLine().getStatusCode(), body);
        }
    }

    private static final class RawResponse {
        final int status;
        final String body;

        RawResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

}
