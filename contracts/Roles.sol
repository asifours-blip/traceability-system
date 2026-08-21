pragma solidity ^0.4.25;

//角色库（管理所有角色地址）
// 1. 实现增加角色地址
// 2. 移除角色地址
// 3. 判断角色地址是否被授权
library Roles {
    struct Role {
        mapping(address => bool) bearer;
    }

    // 传入的是一个结构体,且有一个映射，那证明

    function add(Role storage role, address account) internal {
        require(!has(role, account), "account already has a role!");
        role.bearer[account] = true;
    }

    function remove(Role storage role, address account) internal {
        require(has(role, account), "account already has not a role!");
        role.bearer[account] = false;
    }

    function has(
        Role storage role,
        address account
    ) internal view returns (bool) {
        return role.bearer[account];
    }


}
