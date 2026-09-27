package com.qhx.back.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.qhx.back.annotation.RequireRole;
import com.qhx.back.chain.TraceStage;
import com.qhx.back.client.WeBaseClient;
import com.qhx.back.enums.UserRole;
import com.qhx.back.model.Result;
import com.qhx.back.model.to.DistributorTo;
import com.qhx.back.model.to.ProducerTo;
import com.qhx.back.model.to.RetailerTo;
import com.qhx.back.service.ChainTxService;
import com.qhx.back.trace.TracePayloadParser;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
@RestController
@Api(tags = "溯源接口")
public class TraceController {
    @Autowired
    private WeBaseClient weBaseClient;
    @Autowired
    private ChainTxService chainTxService;

    // 获取详细溯源信息
    @GetMapping("/trace/detail/{traceNumber}")
    @ApiOperation(value = "溯源信息")
    public Result getTrace(@PathVariable String traceNumber) {
        JSONObject traceDetail = getTraceDetail(traceNumber);
        return Result.success(traceDetail);
    }

    // 获取溯源列表
    @GetMapping("/trace/list")
    @ApiOperation(value = "溯源列表")
    public Result getTraceList() {
        JSONArray foodList = getFoodList();
        JSONArray resList = new JSONArray();
        for (int i = 0; i < foodList.size(); i++) {
            String traceNumber = foodList.getStr(i);
            JSONObject jsonObj = getTraceDetail(traceNumber);
            resList.add(jsonObj);
        }
        return Result.success(resList);
    }

    private JSONObject getTraceDetail(String traceNumber) {
        JSONObject producer = getProducer(traceNumber);
        if (producer == null) {
            throw new RuntimeException("未找到该溯源信息");
        }
        return TracePayloadParser.assembleDetail(
                traceNumber, producer, getDistributor(traceNumber), getRetailer(traceNumber));
    }

    private JSONObject getRetailer(String traceNumber) {
        JSONArray retailerAgroFood = weBaseClient.call("getAgroFoodInfoByRetailer", Arrays.asList(traceNumber));
        return TracePayloadParser.parseRetailer(traceNumber, retailerAgroFood);
    }

    private JSONObject getDistributor(String traceNumber) {
        JSONArray distributorAgroFood = weBaseClient.call("getAgroFoodInfoByDistributor", Arrays.asList(traceNumber));
        return TracePayloadParser.parseDistributor(traceNumber, distributorAgroFood);
    }

    private JSONObject getProducer(String traceNumber) {
        JSONArray agroFoodInfo = weBaseClient.call("getAgroFoodInfo", Arrays.asList(traceNumber));
        return TracePayloadParser.parseProducer(traceNumber, agroFoodInfo);
    }

    // 生产者录入生产信息
    @PostMapping("/producer/add")
    @RequireRole(UserRole.PRODUCER)
    @ApiOperation(value = "生产者录入生产信息")
    public Result addProducer(@RequestBody ProducerTo producerTO) {
        // 签名地址取当前会话绑定地址；回执确认成功才返回 200，data 为交易记录（含哈希与块高）
        return Result.success(chainTxService.submitStage(
                TraceStage.PRODUCTION, Arrays.asList(
                        producerTO.getTraceNumber(),
                        producerTO.getCompanyName(),
                        producerTO.getProductName(),
                        producerTO.getProductionLocation(),
                        producerTO.getVariety(),
                        producerTO.getProductionBatch(),
                        producerTO.getProductionCert(),
                        producerTO.getProductTime()
                )
        ));
    }

    // 生产者获取生产信息列表
    @GetMapping("/producer/list")
    @RequireRole({UserRole.PRODUCER, UserRole.ADMIN})
    @ApiOperation(value = "生产者获取生产信息列表")
    public Result getProducerList() {
        JSONArray foodList = getFoodList();
        JSONArray resList = new JSONArray();
        for (int i = 0; i < foodList.size(); i++) {
            String traceNumber = foodList.getStr(i);
            JSONObject jsonObj = getProducer(traceNumber);
            resList.add(jsonObj);
        }
        return Result.success(resList);
    }

    // 分销商录入分销信息
    @PostMapping("/distributor/add")
    @RequireRole(UserRole.DISTRIBUTOR)
    @ApiOperation(value = "分销商录分销信息")
    public Result addDistributor(@RequestBody DistributorTo distributorTO) {
        return Result.success(chainTxService.submitStage(TraceStage.DISTRIBUTION, Arrays.asList(
                distributorTO.getTraceNumber(),
                distributorTO.getCompanyName(),
                distributorTO.getStorageCondition(),
                distributorTO.getTransportMethod(),
                distributorTO.getDistributeBatch(),
                distributorTO.getStorageLocation(),
                distributorTO.getDistributePrice(),
                distributorTO.getDistributeQuantity(),
                distributorTO.getInspectionReport()
        )));
    }

    // 分销商获取分销信息列表
    @GetMapping("/distributor/list")
    @RequireRole({UserRole.DISTRIBUTOR, UserRole.ADMIN})
    @ApiOperation(value = "分销商获取分销信息列表")
    public Result getDistributorList() {
        JSONArray foodList = getFoodList();
        JSONArray resList = new JSONArray();
        for (int i = 0; i < foodList.size(); i++) {
            String traceNumber = foodList.getStr(i);
            JSONObject jsonObj = getDistributor(traceNumber);
            if (jsonObj == null) {
                continue;
            }
            resList.add(jsonObj);
        }
        return Result.success(resList);
    }

    // 零售商添加销售信息
    @PostMapping("/retailer/add")
    @RequireRole(UserRole.RETAILER)
    @ApiOperation(value = "零售商添加销售信息")
    public Result addRetailer(@RequestBody RetailerTo retailerTO) {
        return Result.success(chainTxService.submitStage(TraceStage.RETAIL, Arrays.asList(
                retailerTO.getTraceNumber(),
                retailerTO.getCompanyName(),
                retailerTO.getSalePrice(),
                retailerTO.getSaleQuantity(),
                retailerTO.getShelfLife(),
                retailerTO.getInvoiceNo(),
                retailerTO.getSaleTime()
        )));
    }

    // 零售商获取销售信息列表
    @GetMapping("/retailer/list")
    @RequireRole({UserRole.RETAILER, UserRole.ADMIN})
    @ApiOperation(value = "零售商获取销售信息列表")
    public Result getRetailerList() {
        JSONArray foodList = getFoodList();
        JSONArray resList = new JSONArray();
        for (int i = 0; i < foodList.size(); i++) {
            String traceNumber = foodList.getStr(i);
            JSONObject jsonObj = getRetailer(traceNumber);
            if (jsonObj == null) {
                continue;
            }
            resList.add(jsonObj);
        }
        return Result.success(resList);
    }


    private JSONArray getFoodList() {
        JSONArray call = weBaseClient.call("getAgroFoodList");
        return call.getJSONArray(0);
    }


}
