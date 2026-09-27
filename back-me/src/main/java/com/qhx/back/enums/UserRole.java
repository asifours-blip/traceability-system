package com.qhx.back.enums;

/**
 * 平台账号角色。业务角色与合约里的 Producer/Distributor/Retailer 一一对应；
 * ADMIN 对应合约 owner，本身不再拥有业务角色。
 */
public enum UserRole
{
    ADMIN(null, "管理员"),
    PRODUCER("Producer", "生产商"),
    DISTRIBUTOR("Distributor", "分销商"),
    RETAILER("Retailer", "零售商");

    // 合约函数名里的角色后缀，如 addProducer / removeProducer / isProducer
    private final String chainRole;
    private final String desc;

    UserRole(String chainRole, String desc)
    {
        this.chainRole = chainRole;
        this.desc = desc;
    }

    public String getDesc()
    {
        return desc;
    }

    public boolean isBusinessRole()
    {
        return chainRole != null;
    }

    public String addFunction()
    {
        return "add" + requireChainRole();
    }

    public String removeFunction()
    {
        return "remove" + requireChainRole();
    }

    public String checkFunction()
    {
        return "is" + requireChainRole();
    }

    private String requireChainRole()
    {
        if (chainRole == null) {
            throw new IllegalArgumentException("管理员没有对应的链上业务角色");
        }
        return chainRole;
    }

    public static UserRole parse(String value)
    {
        if (value != null) {
            for (UserRole role : values()) {
                if (role.name().equalsIgnoreCase(value.trim())) {
                    return role;
                }
            }
        }
        throw new IllegalArgumentException("无效的用户角色");
    }
}
