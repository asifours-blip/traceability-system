package com.qhx.back.util;

import com.qhx.back.context.AddressContext;
import com.qhx.back.exception.WeBaseFrontException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HttpUtilSendTransactionTest {

    private static final String SIGNER = "0x" + "a".repeat(40);

    @BeforeEach
    void setUp() {
        AddressContext.setAddress(SIGNER);
    }

    @AfterEach
    void tearDown() {
        AddressContext.clear();
    }

    @Test
    void statusOK返回null() {
        HttpUtil util = spy(new HttpUtil());
        doReturn("{\"statusOK\":true}").when(util).contractRequest(anyString(), anyString(), anyList());
        assertNull(util.sendTransaction("newAgroFood", List.of()));
        // 签名地址取自会话上下文
        verify(util).contractRequest(eq(SIGNER), eq("newAgroFood"), anyList());
    }

    @Test
    void 会话没有绑定地址_拒绝发送且不请求WeBASE() {
        AddressContext.clear();
        HttpUtil util = spy(new HttpUtil());
        assertThrows(IllegalStateException.class, () -> util.sendTransaction("newAgroFood", List.of()));
        verify(util, never()).contractRequest(anyString(), anyString(), anyList());
    }

    @Test
    void 链上失败保留mes且不再二次包装() {
        HttpUtil util = spy(new HttpUtil());
        doReturn("{\"statusOK\":false,\"message\":\"caller does not have the Producer role\"}")
                .when(util).contractRequest(anyString(), anyString(), anyList());
        WeBaseFrontException ex = assertThrows(WeBaseFrontException.class,
                () -> util.sendTransaction("newAgroFood", List.of()));
        assertEquals("caller does not have the Producer role", ex.getMessage());
        assertEquals("caller does not have the Producer role", ex.mes);
        assertFalse(ex.getCause() instanceof WeBaseFrontException);
    }

    @Test
    void 非法JSON才包装为新异常() {
        HttpUtil util = spy(new HttpUtil());
        doReturn("not-json").when(util).contractRequest(anyString(), anyString(), anyList());
        WeBaseFrontException ex = assertThrows(WeBaseFrontException.class,
                () -> util.sendTransaction("newAgroFood", List.of()));
        assertFalse(ex.getCause() instanceof WeBaseFrontException);
    }
}
