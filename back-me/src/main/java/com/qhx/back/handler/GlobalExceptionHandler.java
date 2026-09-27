package com.qhx.back.handler;

import cn.hutool.json.JSONUtil;
import com.qhx.back.exception.AuthException;
import com.qhx.back.exception.ChainTxException;
import com.qhx.back.exception.WeBaseFrontException;
import com.qhx.back.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import java.awt.*;
@ControllerAdvice
@ResponseBody
@Slf4j
public class GlobalExceptionHandler
{

    @ExceptionHandler(WeBaseFrontException.class)
    public Result handlerWebaseFrontException(WeBaseFrontException excepxtion)
    {
        log.error("webase-front exception:" , excepxtion);
        // excepxtion.getMessage()可以拿到值，但是excepxtion.mes拿不到值
        return Result.error(excepxtion.mes);
    }


    // 认证/鉴权失败：HTTP 状态码与 body.code 一致（401 / 403）
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Result> handlerAuthException(AuthException exception)
    {
        return ResponseEntity.status(exception.getStatus())
                .body(new Result(null, exception.getMessage(), exception.getStatus()));
    }


    // 交易未确认成功：HTTP 状态码与 body.code 一致，data 是 chain_tx 记录（前端据此调用查证接口）
    @ExceptionHandler(ChainTxException.class)
    public ResponseEntity<Result> handlerChainTxException(ChainTxException exception)
    {
        log.warn("chain tx not confirmed: {}", exception.getMessage());
        return ResponseEntity.status(exception.getStatus())
                .body(new Result(exception.getData(), exception.getMessage(), exception.getStatus()));
    }


    @ExceptionHandler(Exception.class)
    public Result handlerException(Exception excepxtion)
    {
        log.error("Exception：",excepxtion);
        return Result.error(excepxtion.getMessage());
    }
}





