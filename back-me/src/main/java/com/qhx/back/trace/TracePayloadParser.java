package com.qhx.back.trace;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

/**
 * 解析 WeBASE 返回的合约输出。纯函数，无 HTTP、无链。
 */
public final class TracePayloadParser {

    private TracePayloadParser() {
    }

    public static JSONObject parseProducer(String traceNumber, JSONArray agroFoodInfo) {
        // ABI：getAgroFoodInfo 固定 8 个返回值。长度不对一律视为未找到，不靠 size==1 猜 WeBASE revert。
        if (agroFoodInfo == null || agroFoodInfo.size() != 8) {
            return null;
        }
        JSONObject jsonObj = new JSONObject();
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

    public static JSONObject parseDistributor(String traceNumber, JSONArray distributorAgroFood) {
        if (distributorAgroFood == null || StrUtil.isBlank(distributorAgroFood.getStr(0))) {
            return null;
        }
        JSONObject jsonObj = new JSONObject();
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

    public static JSONObject parseRetailer(String traceNumber, JSONArray retailerAgroFood) {
        if (retailerAgroFood == null || StrUtil.isBlank(retailerAgroFood.getStr(0))) {
            return null;
        }
        JSONObject jsonObj = new JSONObject();
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

    public static JSONObject assembleDetail(String traceNumber, JSONObject producer,
                                            JSONObject distributor, JSONObject retailer) {
        if (producer == null) {
            return null;
        }
        JSONObject result = new JSONObject();
        result.set("traceNumber", traceNumber);
        result.set("producer", producer);
        result.set("distributor", distributor == null ? new JSONObject() : distributor);
        result.set("retailer", retailer == null ? new JSONObject() : retailer);
        return result;
    }
}
