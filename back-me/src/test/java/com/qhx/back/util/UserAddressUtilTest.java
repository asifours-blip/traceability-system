package com.qhx.back.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAddressUtilTest {

    @Test
    void 合法地址_0x加40位() {
        assertTrue(UserAddressUtil.isLegalAddress("0x" + "a".repeat(40)));
    }

    @Test
    void 空与缺0x与长度不对_均非法() {
        assertFalse(UserAddressUtil.isLegalAddress(null));
        assertFalse(UserAddressUtil.isLegalAddress(""));
        assertFalse(UserAddressUtil.isLegalAddress("abc"));
        assertFalse(UserAddressUtil.isLegalAddress("0x123"));
        assertFalse(UserAddressUtil.isLegalAddress("0x" + "a".repeat(39)));
        assertFalse(UserAddressUtil.isLegalAddress("0x" + "a".repeat(41)));
    }

    @Test
    void 必须是十六进制_非hex与全角数字均非法() {
        assertFalse(UserAddressUtil.isLegalAddress("0x" + "z".repeat(40)));
        assertFalse(UserAddressUtil.isLegalAddress("0x" + "０".repeat(40)));
        assertFalse(UserAddressUtil.isLegalAddress("0x" + " ".repeat(40)));
    }

    @Test
    void 大小写混合十六进制合法_不做EIP55校验() {
        assertTrue(UserAddressUtil.isLegalAddress("0x" + "aBcDeF0123".repeat(4)));
    }
}
