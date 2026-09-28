pragma solidity ^0.4.25;

import "./Ownable.sol";
import "./Roles.sol";

//零售商角色（超市）：只有 owner 能授予/撤销，持有者之间不能互相授权
contract Retailer is Ownable {
    using Roles for Roles.Role;

    event RetailerAdded(address indexed account);
    event RetailerRemoved(address indexed account);

    Roles.Role private _retailers;

    constructor (address retailer) public {
        // 0 地址表示部署时暂不指定，之后由 owner 调用 addRetailer 授予
        if (retailer != address(0)) {
            _addRetailer(retailer);
        }
    }

    modifier onlyRetailer() {
        require(isRetailer(msg.sender), "RetailerRole: caller does not have the Retailer role");
        _;
    }


    function isRetailer(address account) public view returns (bool) {
        return _retailers.has(account);
    }

    function addRetailer(address account) public onlyOwner {
        _addRetailer(account);
    }

    function removeRetailer(address account) public onlyOwner {
        _removeRetailer(account);
    }

    function renounceRetailer() public {
        _removeRetailer(msg.sender);
    }

    function _addRetailer(address account) internal {
        _retailers.add(account);
        emit RetailerAdded(account);
    }

    function _removeRetailer(address account) internal {
        _retailers.remove(account);
        emit RetailerRemoved(account);
    }
}
