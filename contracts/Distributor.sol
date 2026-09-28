pragma solidity ^0.4.25;

import "./Ownable.sol";
import "./Roles.sol";

//中间商（分销商）角色：只有 owner 能授予/撤销，持有者之间不能互相授权
contract Distributor is Ownable {
    using Roles for Roles.Role;

    event DistributorAdded(address indexed account);
    event DistributorRemoved(address indexed account);

    Roles.Role private _distributors;

    constructor(address distributor) public {
        // 0 地址表示部署时暂不指定，之后由 owner 调用 addDistributor 授予
        if (distributor != address(0)) {
            _addDistributor(distributor);
        }
    }

    modifier onlyDistributor() {
        require(
            isDistributor(msg.sender),
            "DistributorRole: caller does not have the Distributor role"
        );
        _;
    }

    function isDistributor(address account) public view returns (bool) {
        return _distributors.has(account);
    }

    function addDistributor(address account) public onlyOwner {
        _addDistributor(account);
    }

    function removeDistributor(address account) public onlyOwner {
        _removeDistributor(account);
    }

    function renounceDistributor() public {
        _removeDistributor(msg.sender);
    }

    function _addDistributor(address account) internal {
        _distributors.add(account);
        emit DistributorAdded(account);
    }

    function _removeDistributor(address account) internal {
        _distributors.remove(account);
        emit DistributorRemoved(account);
    }
}
