pragma solidity ^0.4.25;
pragma experimental ABIEncoderV2;

//农产品信息管理合约
contract AgroFoodInfoItem {


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

    Producer _producer;
    Distributor _distributor;
    Retailer _retailer;

    function setProducer(
        string companyName,
        string productName,
        string productionLocation,
        string variety,
        string productionBatch,
        string productionCert,
        string productTime
    ) public {
        _producer = Producer({
            companyName: companyName,
            productName: productName,
            productionLocation: productionLocation,
            variety: variety,
            productionBatch: productionBatch,
            productionCert: productionCert,
            productTime: productTime,
            timestamp: now
        });
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
        string companyName,
        string storageCondition,
        string transportMethod,
        string distributeBatch,
        string storageLocation,
        uint distributePrice,
        uint distributeQuantity,
        string inspectionReport
    ) public {
        _distributor = Distributor({
            companyName: companyName,
            storageCondition: storageCondition,
            transportMethod: transportMethod,
            distributeBatch: distributeBatch,
            storageLocation: storageLocation,
            distributePrice: distributePrice,
            distributeQuantity: distributeQuantity,
            inspectionReport: inspectionReport,
            timestamp: now
        });
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
        string companyName,
        uint salePrice,
        uint saleQuantity,
        uint shelfLife,
        string invoiceNo,
        string saleTime
    ) public {
        _retailer = Retailer({
            companyName: companyName,
            salePrice: salePrice,
            saleQuantity: saleQuantity,
            shelfLife: shelfLife,
            invoiceNo: invoiceNo,
            saleTime: saleTime,
            timestamp: now
        });
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
}
