pragma solidity ^0.4.25;

import "./Ownable.sol";
import "./Roles.sol";

//生产者角色：只有 owner 能授予/撤销，持有者之间不能互相授权
contract Producer is Ownable {
    using Roles for Roles.Role;

    event ProducerAdded(address indexed account);
    event ProducerRemoved(address indexed account);

    Roles.Role private _producers;

    constructor(address producer) public {
        // 0 地址表示部署时暂不指定，之后由 owner 调用 addProducer 授予
        if (producer != address(0)) {
            _addProducer(producer);
        }
    }

    modifier onlyProducer() {
        require(
            isProducer(msg.sender),
            "ProducerRole: caller does not have the Producer role"
        );
        _;
    }

    function isProducer(address account) public view returns (bool) {
        return _producers.has(account);
    }

    function addProducer(address account) public onlyOwner {
        _addProducer(account);
    }

    function removeProducer(address account) public onlyOwner {
        _removeProducer(account);
    }

    function renounceProducer() public {
        _removeProducer(msg.sender);
    }

    function _addProducer(address account) internal {
        _producers.add(account);
        emit ProducerAdded(account);
    }

    function _removeProducer(address account) internal {
        _producers.remove(account);
        emit ProducerRemoved(account);
    }
}
