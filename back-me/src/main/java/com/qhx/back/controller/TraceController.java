package com.qhx.back.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.qhx.back.context.AddressContext;
import com.qhx.back.model.Result;
import com.qhx.back.model.to.DistributorTo;
import com.qhx.back.model.to.ProducerTo;
import com.qhx.back.model.to.RetailerTo;
import com.qhx.back.util.HttpUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
@RestController
@Api(tags = "溯源接口")
public class TraceController {
    @Autowired
    private HttpUtil httpUtil;

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
        JSONObject result = new JSONObject();
        result.set("traceNumber", traceNumber);
        JSONObject producer = getProducer(traceNumber);
        if (producer == null) {
            throw new RuntimeException("未找到该溯源信息");
        }
        result.set("producer", producer);
        JSONObject distributor = getDistributor(traceNumber);
        if (distributor == null) {
            result.set("distributor", new JSONObject());
        } else {

            result.set("distributor", distributor);
        }
        JSONObject retailer = getRetailer(traceNumber);
        if (retailer == null) {
            result.set("retailer", new JSONObject());
        } else {
            result.set("retailer", retailer);
        }
        return result;
    }

    private JSONObject getRetailer(String traceNumber) {
        JSONArray retailerAgroFood = httpUtil.call("getAgroFoodInfoByRetailer", Arrays.asList(traceNumber));
        JSONObject jsonObj = new JSONObject();
        if (StrUtil.isBlank(retailerAgroFood.getStr(0))) {
            return null;
        }
        jsonObj.set("traceNumber", traceNumber);
        jsonObj.set("companyName", retailerAgroFood.getStr(0));
        jsonObj.set("salePrice", retailerAgroFood.getLong(1));
        jsonObj.set("saleQuantity", retailerAgroFood.getLong(2));
        jsonObj.set("shelfLife", retailerAgroFood.getLong(3));
        jsonObj.set("invoiceNo", retailerAgroFood.getStr(4));
        jsonObj.set("saleTime", retailerAgroFood.getStr(5));
        jsonObj.set("timestamp", retailerAgroFood.getLong(6));
        return jsonObj;
    }

    private JSONObject getDistributor(String traceNumber) {
        JSONArray distributorAgroFood = httpUtil.call("getAgroFoodInfoByDistributor", Arrays.asList(traceNumber));
        JSONObject jsonObj = new JSONObject();
        if (StrUtil.isBlank(distributorAgroFood.getStr(0))) {
            return null;
        }
        jsonObj.set("traceNumber", traceNumber);
        jsonObj.set("companyName", distributorAgroFood.getStr(0));
        jsonObj.set("storageCondition", distributorAgroFood.getStr(1));
        jsonObj.set("transportMethod", distributorAgroFood.getStr(2));
        jsonObj.set("distributeBatch", distributorAgroFood.getStr(3));
        jsonObj.set("storageLocation", distributorAgroFood.getStr(4));
        jsonObj.set("distributePrice", distributorAgroFood.getLong(5));
        jsonObj.set("distributeQuantity", distributorAgroFood.getLong(6));
        jsonObj.set("inspectionReport", distributorAgroFood.getStr(7));
        jsonObj.set("timestamp", distributorAgroFood.getLong(8));
        return jsonObj;
    }

    private JSONObject getProducer(String traceNumber) {
        JSONArray agroFoodInfo = httpUtil.call("getAgroFoodInfo", Arrays.asList(traceNumber));
        JSONObject jsonObj = new JSONObject();
        if (agroFoodInfo.size() == 1) {
            return null;
        }
        jsonObj.set("traceNumber", traceNumber);
        jsonObj.set("companyName", agroFoodInfo.getStr(0));
        jsonObj.set("productName", agroFoodInfo.getStr(1));
        jsonObj.set("productionLocation", agroFoodInfo.getStr(2));
        jsonObj.set("variety", agroFoodInfo.getStr(3));
        jsonObj.set("productionBatch", agroFoodInfo.getStr(4));
        jsonObj.set("productionCert", agroFoodInfo.getStr(5));
        jsonObj.set("productTime", agroFoodInfo.getStr(6));
        jsonObj.set("timestamp", agroFoodInfo.getLong(7));
        return jsonObj;
    }

    // 生产者录入生产信息
    @PostMapping("/producer/add")
    @ApiOperation(value = "生产者录入生产信息")
    public Result addProducer(@RequestBody ProducerTo producerTO) {
        String address = AddressContext.getAddress();
        httpUtil.sendTransaction(
                address, "newAgroFood", Arrays.asList(
                        producerTO.getTraceNumber(),
                        producerTO.getCompanyName(),
                        producerTO.getProductName(),
                        producerTO.getProductionLocation(),
                        producerTO.getVariety(),
                        producerTO.getProductionBatch(),
                        producerTO.getProductionCert(),
                        producerTO.getProductTime()
                )
        );
        return Result.success();
    }

    // 生产者获取生产信息列表
    @GetMapping("/producer/list")
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
    @ApiOperation(value = "分销商录分销信息")
    public Result addDistributor(@RequestBody DistributorTo distributorTO) {
        String address = AddressContext.getAddress();
        httpUtil.sendTransaction(address, "addTraceInfoByDistributor", Arrays.asList(
                distributorTO.getTraceNumber(),
                distributorTO.getCompanyName(),
                distributorTO.getStorageCondition(),
                distributorTO.getTransportMethod(),
                distributorTO.getDistributeBatch(),
                distributorTO.getStorageLocation(),
                distributorTO.getDistributePrice(),
                distributorTO.getDistributeQuantity(),
                distributorTO.getInspectionReport()
        ));
        return Result.success();
    }

    // 分销商获取分销信息列表
    @GetMapping("/distributor/list")
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
    @ApiOperation(value = "零售商添加销售信息")
    public Result addRetailer(@RequestBody RetailerTo retailerTO) {
        String address = AddressContext.getAddress();
        httpUtil.sendTransaction(address, "addTraceInfoByRetailer", Arrays.asList(
                retailerTO.getTraceNumber(),
                retailerTO.getCompanyName(),
                retailerTO.getSalePrice(),
                retailerTO.getSaleQuantity(),
                retailerTO.getShelfLife(),
                retailerTO.getInvoiceNo(),
                retailerTO.getSaleTime()
        ));
        return Result.success();
    }

    // 零售商获取销售信息列表
    @GetMapping("/retailer/list")
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
        JSONArray call = httpUtil.call("getAgroFoodList");
        return call.getJSONArray(0);
    }


}
