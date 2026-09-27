package com.qhx.back.client;

import cn.hutool.json.JSONArray;
import com.qhx.back.chain.TxOutcome;

import java.util.List;

/**
 * WeBASE-Front 调用端口。生产实现是 HttpUtil；测试里用本地 HTTP 替身或 Mock，不连链。
 */
public interface WeBaseClient {

    JSONArray call(String funcName);

    JSONArray call(String funcName, List<Object> params);

    /**
     * 发送交易，返回按 WeBASE-Front 契约分类后的结果；传输层异常也折算成结果，不抛出、不自动重发。
     * 签名地址只取 AddressContext（当前登录账号在服务端绑定的地址），
     * 不接受调用方传入签名地址，避免请求体/请求头里的地址被拿去签名。
     * 业务代码不要直接调用，统一经 ChainTxService（先落库提交意图再发）。
     */
    TxOutcome sendTransaction(String funcName, List<Object> params);

    /** 按交易哈希查回执；查不到或查询失败时返回 UNKNOWN。 */
    TxOutcome queryReceipt(String txHash);
}
