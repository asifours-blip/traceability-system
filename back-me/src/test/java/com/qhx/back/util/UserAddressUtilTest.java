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
    void 当前实现不校验十六进制_非hex仍算合法() {
        // 记录现状，不是推荐；改校验时本用例应变红
        assertTrue(UserAddressUtil.isLegalAddress("0x" + "z".repeat(40)));
    }
}
