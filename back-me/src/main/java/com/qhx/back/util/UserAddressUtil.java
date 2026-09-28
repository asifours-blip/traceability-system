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
        if(address.length() != 42){
            return false;
        }
        // 0x 后必须是 40 位十六进制；不做 EIP-55 大小写校验
        for (int i = 2; i < address.length(); i++) {
            char c = address.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
