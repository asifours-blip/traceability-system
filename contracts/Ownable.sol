pragma solidity ^0.4.25;

//管理员合约：部署者即 owner。
// owner 只负责授予/撤销业务角色、维护系统信息，本身不自动持有任何业务角色。
// 不提供 transferOwnership：owner 私钥丢失后只能重新部署（见 README「已知限制」）。
contract Ownable {
    address private _owner;

    constructor() internal {
        _owner = msg.sender;
    }

    modifier onlyOwner() {
        require(msg.sender == _owner, "Ownable: caller is not the owner");
        _;
    }

    function owner() public view returns (address) {
        return _owner;
    }
}
