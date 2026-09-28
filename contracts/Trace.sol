pragma solidity ^0.4.25;
pragma experimental ABIEncoderV2;

import "./AgroFoodInfoItem.sol";
import "./Distributor.sol";
import "./Producer.sol";
import "./Retailer.sol";
import "./SystemInfo.sol";
//农产品溯源合约（负责具体农产品溯源信息的生成）
// 权限模型：
// - 部署者为 owner，只负责授予/撤销三种业务角色、维护系统信息，不自动持有业务角色；
// - 生产/分销/零售分别要求对应角色，且严格按 生产 -> 分销 -> 零售 的顺序各写一次；
// - AgroFoodInfoItem 只接受本合约写入，无法绕过这里的角色与阶段检查。
contract Trace is Producer, Distributor, Retailer, SystemInfo {
    // 阶段编号，与 AgroFoodInfoItem 中的定义一致
    uint8 constant STAGE_PRODUCED = 1;
    uint8 constant STAGE_DISTRIBUTED = 2;
    uint8 constant STAGE_RETAILED = 3;

    mapping(string => address) private agroFoods;
    string[] agroFoodList;

    // 每个阶段写入成功时触发，stage：1=生产 2=分销 3=零售，actor 为实际写入者
    event TraceStageRecorded(string traceNumber, uint8 stage, address actor);

    //构造函数：非 0 地址的参数分别授予对应角色；部署者（owner）不自动获得任何业务角色
    constructor(
        address producer,
        address distributor,
        address retailer
    ) public Producer(producer) Distributor(distributor) Retailer(retailer) {
    }

    //生成农产品溯源信息接口（生产阶段）
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
        _requireNewTraceNumber(traceNumber);
        AgroFoodInfoItem agroFood = new AgroFoodInfoItem();
        agroFood.setProducer(msg.sender, companyName, productName, productionLocation, variety, productionBatch, productionCert, productTime);
        agroFoods[traceNumber] = address(agroFood);
        agroFoodList.push(traceNumber);
        emit TraceStageRecorded(traceNumber, STAGE_PRODUCED, msg.sender);
        return address(agroFood);
    }

    //农产品分销过程中增加溯源信息的接口（分销阶段，必须在生产之后且只能一次）
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
        _itemForDistribution(traceNumber).setDistributor(msg.sender, companyName, storageCondition, transportMethod, distributeBatch, storageLocation, distributePrice, distributeQuantity, inspectionReport);
        emit TraceStageRecorded(traceNumber, STAGE_DISTRIBUTED, msg.sender);
    }

    //农产品出售过程中增加溯源信息的接口（零售阶段，必须在分销之后且只能一次）
    function addTraceInfoByRetailer(
        string traceNumber,
        string companyName,
        uint salePrice,
        uint saleQuantity,
        uint shelfLife,
        string invoiceNo,
        string saleTime
    ) public onlyRetailer {
        _itemForRetail(traceNumber).setRetailer(msg.sender, companyName, salePrice, saleQuantity, shelfLife, invoiceNo, saleTime);
        emit TraceStageRecorded(traceNumber, STAGE_RETAILED, msg.sender);
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
        return _existingItem(traceNumber).getProducer();
    }

    // 分销阶段未写入时返回空字符串与 0（timestamp 为 0 即表示未写入）
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
        return _existingItem(traceNumber).getDistributor();
    }

    // 零售阶段未写入时返回空字符串与 0（timestamp 为 0 即表示未写入）
    function getAgroFoodInfoByRetailer(string traceNumber) public view returns (
        string companyName,
        uint salePrice,
        uint saleQuantity,
        uint shelfLife,
        string invoiceNo,
        string saleTime,
        uint timestamp
    ) {
        return _existingItem(traceNumber).getRetailer();
    }

    // 各阶段实际写入者地址，未写入的阶段为 0 地址
    function getStageActors(string traceNumber) public view returns (
        address producer,
        address distributor,
        address retailer
    ) {
        return _existingItem(traceNumber).getActors();
    }

    function getAgroFoodList() public view returns (string[]) {
        return agroFoodList;
    }

    // 生产阶段：溯源号非空且尚未登记
    function _requireNewTraceNumber(string traceNumber) internal view {
        require(bytes(traceNumber).length > 0, "Trace: traceNumber is empty");
        require(agroFoods[traceNumber] == address(0), "Trace: traceNumber already exists");
    }

    // 溯源号必须已登记（登记即意味着生产阶段已写入）
    function _existingItem(string traceNumber) internal view returns (AgroFoodInfoItem) {
        require(bytes(traceNumber).length > 0, "Trace: traceNumber is empty");
        address item = agroFoods[traceNumber];
        require(item != address(0), "Trace: traceNumber does not exist");
        return AgroFoodInfoItem(item);
    }

    // 分销阶段：已生产、尚未分销
    function _itemForDistribution(string traceNumber) internal view returns (AgroFoodInfoItem) {
        AgroFoodInfoItem item = _existingItem(traceNumber);
        require(item.stage() < STAGE_DISTRIBUTED, "Trace: distribution already recorded");
        return item;
    }

    // 零售阶段：已分销、尚未零售
    function _itemForRetail(string traceNumber) internal view returns (AgroFoodInfoItem) {
        AgroFoodInfoItem item = _existingItem(traceNumber);
        uint8 current = item.stage();
        require(current >= STAGE_DISTRIBUTED, "Trace: distribution not recorded yet");
        require(current < STAGE_RETAILED, "Trace: retail already recorded");
        return item;
    }
}
