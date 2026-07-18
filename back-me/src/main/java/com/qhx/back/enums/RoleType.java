package com.qhx.back.enums;
public enum RoleType
{
    PRODUCER("0","生产商"),
    DISTRIBUTOR("1","分销商"),
    RETAILER("2","零售商");

    private final String code;
    private final String desc;

    RoleType(String code, String desc)
    {
        this.code = code;
        this.desc = desc;
    }

    public String getCode()
    {
        return code;
    }

    public String getDesc()
    {
        return desc;
    }
}
