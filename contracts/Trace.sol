pragma solidity ^0.4.25;
pragma experimental ABIEncoderV2;

import "./AgroFoodInfoItem.sol";
import "./Distributor.sol";
import "./Producer.sol";
import "./Retailer.sol";
import "./SystemInfo.sol";
//农产品溯源合约（负责具体农产品溯源信息的生成）
contract Trace is Producer, Distributor, Retailer, SystemInfo {
    mapping(string => address) private agroFoods;
    string[] agroFoodList;

    //构造函数
    constructor(
        address producer,
        address distributor,
        address retailer
    ) public Producer(producer) Distributor(distributor) Retailer(retailer) {
        address owner = msg.sender;
        _addProducer(owner);
        _addDistributor(owner);
        _addRetailer(owner);
    }

    //生成农产品溯源信息接口
    function newAgroFood(
        string traceNumber,
        string companyName,
        string productName,
        string productionLocation,
        string variety,
        string productionBatch,
        string productionCert,
        string productTime
    ) public onlyProducer returns (address) {
        require(
            agroFoods[traceNumber] == address(0),
            "traceNumber is already exists!"
        );
        AgroFoodInfoItem agroFood = new AgroFoodInfoItem();
        agroFood.setProducer(companyName, productName, productionLocation, variety, productionBatch, productionCert, productTime);
        agroFoods[traceNumber] = address(agroFood);
        agroFoodList.push(traceNumber);
        return address(agroFood);
    }
    
    //农产品分销过程中增加溯源信息的接口
    function addTraceInfoByDistributor(
        string traceNumber,
        string companyName,
        string storageCondition,
        string transportMethod,
        string distributeBatch,
        string storageLocation,
        uint distributePrice,
        uint distributeQuantity,
        string inspectionReport
    ) public onlyDistributor {
        require(
            agroFoods[traceNumber] != address(0),
            "Trace:traceNumber is not exists!"
        );
        return AgroFoodInfoItem(agroFoods[traceNumber]).setDistributor(companyName, storageCondition, transportMethod, distributeBatch, storageLocation, distributePrice, distributeQuantity, inspectionReport);
    }

    //农产品出售过程中增加溯源信息的接口
    function addTraceInfoByRetailer(
        string traceNumber,
        string companyName,
        uint salePrice,
        uint saleQuantity,
        uint shelfLife,
        string invoiceNo,
        string saleTime
    ) public onlyRetailer {
        require(
            agroFoods[traceNumber] != address(0),
            "Trace:traceNumber is not exists!"
        );
        return AgroFoodInfoItem(agroFoods[traceNumber]).setRetailer(companyName, salePrice, saleQuantity, shelfLife, invoiceNo, saleTime);
    }

    // 获取农产品信息接口
    function getAgroFoodInfo(string traceNumber) public view returns (
        string companyName,
        string productName,
        string productionLocation,
        string variety,
        string productionBatch,
        string productionCert,
        string productTime,
        uint timestamp
    ) {
        require(
            agroFoods[traceNumber] != address(0),
            "Trace:traceNumber is not exists!"
        );
        return AgroFoodInfoItem(agroFoods[traceNumber]).getProducer();
    }

    function getAgroFoodInfoByDistributor(string traceNumber) public view returns (
        string companyName,
        string storageCondition,
        string transportMethod,
        string distributeBatch,
        string storageLocation,
        uint distributePrice,
        uint distributeQuantity,
        string inspectionReport,
        uint timestamp
    ) {
        require(
            agroFoods[traceNumber] != address(0),
            "Trace:traceNumber is not exists!"
        );
        return AgroFoodInfoItem(agroFoods[traceNumber]).getDistributor();
    }

    function getAgroFoodInfoByRetailer(string traceNumber) public view returns (
        string companyName,
        uint salePrice,
        uint saleQuantity,
        uint shelfLife,
        string invoiceNo,
        string saleTime,
        uint timestamp
    ) {
        require(
            agroFoods[traceNumber] != address(0),
            "Trace:traceNumber is not exists!"
        );
        return AgroFoodInfoItem(agroFoods[traceNumber]).getRetailer();
    }

    function getAgroFoodList() public view returns (string[]) {
        return agroFoodList;
    }


}
