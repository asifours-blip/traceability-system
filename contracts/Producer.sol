pragma solidity ^0.4.25;

import "./Roles.sol";

//生产者角色
contract Producer {
    using Roles for Roles.Role;

    event ProducerAdded(address indexed account);
    event ProducerRemoved(address indexed account);

    Roles.Role private _producers;

    constructor(address producer) public {
        _addProducer(producer);
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

    function addProducer(address account) public onlyProducer {
        _addProducer(account);
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
