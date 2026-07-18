package com.qhx.back.util;

/**
 * @author: 本郡主是喵
 * @date: 2024-11-02 08:07
 **/
public class UserAddressUtil
{
    public static boolean isLegalAddress(String address)
    {
        if(address == null){
            return false;
        }
        if(!address.startsWith("0x")){
            return false;
        }

        return address.length() == 42;
    }
}
