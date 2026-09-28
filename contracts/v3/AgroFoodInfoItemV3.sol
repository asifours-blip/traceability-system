pragma solidity ^0.4.25;
pragma experimental ABIEncoderV2;

//农产品信息管理合约（每个溯源号一个实例，由 Trace 合约创建）
// 安全约束：
// 1. 所有 setter 只允许创建它的 Trace 合约调用，外部账户或其它合约直接调用一律 revert；
// 2. 阶段只能按 生产 -> 分销 -> 零售 的顺序各写一次（Trace 层先做检查并给出具体原因，这里是兜底）。
contract AgroFoodInfoItemV3 {

    // 阶段编号，与 Trace.TraceStageRecorded 事件的 stage 取值一致
    uint8 constant STAGE_NONE = 0;
    uint8 constant STAGE_PRODUCED = 1;
    uint8 constant STAGE_DISTRIBUTED = 2;
    uint8 constant STAGE_RETAILED = 3;

    // 生产商操作字段
    struct Producer {
        string companyName;  // 公司名称
        string productName; // 农产品名称
        string productionLocation; // 生产地址
        string variety; // 农产品品种
        string productionBatch; // 生产批次
        string productionCert; // 生产凭证hash
        string productTime; // 生产时间
        uint timestamp; // 上链时间
    }

    // 分销商操作字段
    struct Distributor {
        string companyName;  // 公司名称
        string storageCondition; // 存储条件
        string transportMethod; // 运输方式
        string distributeBatch; // 分销批次号
        string storageLocation; // 存储位置(仓库地址)
        uint distributePrice; // 分销价格
        uint distributeQuantity; // 分销数量
        string inspectionReport; // 质检报告hash
        uint timestamp; // 上链时间
    }

    // 零售商操作字段
    struct Retailer {
        string companyName;  // 公司名称
        uint salePrice; // 销售价格
        uint saleQuantity; // 数量
        uint shelfLife; // 保质期(天数)
        string invoiceNo; // 单号
        string saleTime;  // 销售时间
        uint timestamp; // 上链时间
    }

    address public trace; // 创建本条目的 Trace 合约地址，唯一可写入者
    uint8 public stage; // 已完成的最后一个阶段

    // 各阶段实际写入者（由 Trace 传入的 msg.sender），未写入为 0 地址
    address private _producerActor;
    address private _distributorActor;
    address private _retailerActor;

    address private _designatedDistributor;
    address private _designatedRetailer;

    Producer _producer;
    Distributor _distributor;
    Retailer _retailer;

    constructor() public {
        trace = msg.sender;
    }

    modifier onlyTrace() {
        require(msg.sender == trace, "AgroFoodInfoItem: caller is not the Trace contract");
        _;
    }

    // 说明：逐字段赋值而不是结构体字面量，避免 0.4.x 参数过多时 stack too deep
    function setProducer(
        address actor,
        string companyName,
        string productName,
        string productionLocation,
        string variety,
        string productionBatch,
        string productionCert,
        string productTime,
        address designatedDistributor
    ) public onlyTrace {
        require(stage == STAGE_NONE, "AgroFoodInfoItem: producer stage not allowed");
        stage = STAGE_PRODUCED;
        _producerActor = actor;
        _designatedDistributor = designatedDistributor;
        _producer.companyName = companyName;
        _producer.productName = productName;
        _producer.productionLocation = productionLocation;
        _producer.variety = variety;
        _producer.productionBatch = productionBatch;
        _producer.productionCert = productionCert;
        _producer.productTime = productTime;
        _producer.timestamp = now;
    }

    function getProducer() public view returns (
        string companyName,
        string productName,
        string productionLocation,
        string variety,
        string productionBatch,
        string productionCert,
        string productTime,
        uint timestamp
    ) {
        Producer memory producer = _producer;
        return (
            producer.companyName,
            producer.productName,
            producer.productionLocation,
            producer.variety,
            producer.productionBatch,
            producer.productionCert,
            producer.productTime,
            producer.timestamp
        );
    }

    function setDistributor(
        address actor,
        string companyName,
        string storageCondition,
        string transportMethod,
        string distributeBatch,
        string storageLocation,
        uint distributePrice,
        uint distributeQuantity,
        string inspectionReport,
        address designatedRetailer
    ) public onlyTrace {
        require(stage == STAGE_PRODUCED, "AgroFoodInfoItem: distributor stage not allowed");
        stage = STAGE_DISTRIBUTED;
        _distributorActor = actor;
        _designatedRetailer = designatedRetailer;
        _distributor.companyName = companyName;
        _distributor.storageCondition = storageCondition;
        _distributor.transportMethod = transportMethod;
        _distributor.distributeBatch = distributeBatch;
        _distributor.storageLocation = storageLocation;
        _distributor.distributePrice = distributePrice;
        _distributor.distributeQuantity = distributeQuantity;
        _distributor.inspectionReport = inspectionReport;
        _distributor.timestamp = now;
    }

    function getDistributor() public view returns (
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
        Distributor memory distributor = _distributor;
        return (
            distributor.companyName,
            distributor.storageCondition,
            distributor.transportMethod,
            distributor.distributeBatch,
            distributor.storageLocation,
            distributor.distributePrice,
            distributor.distributeQuantity,
            distributor.inspectionReport,
            distributor.timestamp
        );
    }

    function setRetailer(
        address actor,
        string companyName,
        uint salePrice,
        uint saleQuantity,
        uint shelfLife,
        string invoiceNo,
        string saleTime
    ) public onlyTrace {
        require(stage == STAGE_DISTRIBUTED, "AgroFoodInfoItem: retailer stage not allowed");
        stage = STAGE_RETAILED;
        _retailerActor = actor;
        _retailer.companyName = companyName;
        _retailer.salePrice = salePrice;
        _retailer.saleQuantity = saleQuantity;
        _retailer.shelfLife = shelfLife;
        _retailer.invoiceNo = invoiceNo;
        _retailer.saleTime = saleTime;
        _retailer.timestamp = now;
    }

    function getRetailer() public view returns (
        string companyName,
        uint salePrice,
        uint saleQuantity,
        uint shelfLife,
        string invoiceNo,
        string saleTime,
        uint timestamp
    ) {
        Retailer memory retailer = _retailer;
        return (
            retailer.companyName,
            retailer.salePrice,
            retailer.saleQuantity,
            retailer.shelfLife,
            retailer.invoiceNo,
            retailer.saleTime,
            retailer.timestamp
        );
    }

    // 仅 Trace 可以在分销写入之前更新生产者指定的分销商。
    function setDesignatedDistributor(address account) public onlyTrace {
        require(stage == STAGE_PRODUCED, "Trace: distribution already recorded");
        _designatedDistributor = account;
    }

    function getDesignations() public view returns (address designatedDistributor, address designatedRetailer) {
        return (_designatedDistributor, _designatedRetailer);
    }

    // 各阶段实际写入者，未写入为 0 地址
    function getActors() public view returns (
        address producer,
        address distributor,
        address retailer
    ) {
        return (_producerActor, _distributorActor, _retailerActor);
    }
}
