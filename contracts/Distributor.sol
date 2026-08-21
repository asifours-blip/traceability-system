pragma solidity ^0.4.25;

import "./Roles.sol";

//中间商角色
contract Distributor {
    using Roles for Roles.Role;

    event DistributorAdded(address indexed account);
    event DistributorRemoved(address indexed account);

    Roles.Role private _distributors;

    constructor(address distributor) public {
        _addDistributor(distributor);
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

    function addDistributor(address account) public onlyDistributor {
        _addDistributor(account);
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
