pragma solidity ^0.4.25;
pragma experimental ABIEncoderV2;

contract SystemInfo {
    // 定义一个结构体来存储系统信息
    struct SystemDetails {
        string name;
        string version;
        string description;
    }
    constructor() public {
        systemInfo = SystemDetails("农产品溯源系统", "v1.0", "一个溯源农产品的系统案例");
    }

    // 状态变量，用于存储系统信息
    SystemDetails private systemInfo;

    // 设置系统信息的函数
    function setSystemInfo(string memory _name, string memory _version, string memory _description) public {
        systemInfo = SystemDetails(_name, _version, _description);
    }

    // 获取系统信息的函数
    function getSystemInfo() public view returns (string memory, string memory, string memory) {
        return (systemInfo.name, systemInfo.version, systemInfo.description);
    }

    // 清空系统信息的函数
    function clearSystemInfo() public {
        systemInfo = SystemDetails("农产品溯源系统", "v1.0", "一个溯源农产品的系统案例");
    }
}