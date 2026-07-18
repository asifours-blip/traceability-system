package com.qhx.back.contreoller;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.qhx.back.model.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/block")
public class BlockController
{

    private String  urlPre;

    @Value("${webase-front.url}")
    public void setUrlPre(String url){
        this.urlPre = url;
    }



    @GetMapping("/{blockNumber}")
    public Result getBlock(@PathVariable String blockNumber){
        String res = HttpUtil.get(urlPre + "/1/web3/blockByNumber/" + blockNumber);
        JSONObject jsonObject = JSONUtil.parseObj(res);
        return Result.success(jsonObject);
    }

    @RequestMapping(value = "/getNodeList",method = RequestMethod.GET)
    public Result getNodeStatusList(){
        String body = HttpUtil.createGet(urlPre + "/1/web3/getNodeStatusList").execute().body();
        JSONArray jsonAry = JSONUtil.parseArray(body);
        return Result.success(jsonAry);
    }

    @RequestMapping(value = "/getTxTotal",method = RequestMethod.GET)
    public Result getTransactionTotal(){
        // 交易数量、区块数量
        String body = HttpUtil.createGet(urlPre + "/1/web3/transaction-total").execute().body();
        JSONObject resObj = JSONUtil.parseObj(body);
        // 节点数量
        JSONArray nodeAry = JSONUtil.parseArray(HttpUtil.createGet(urlPre + "/1/web3/groupPeers").execute().body());
        resObj.set("nodeTotal",nodeAry.size());
        // 待交易数量
        String pendTxTotal = HttpUtil.createGet(urlPre + "/1/web3/pending-transactions-count").execute().body();
        resObj.set("pendTxSum",pendTxTotal);
        return Result.success(resObj);
    }


}
