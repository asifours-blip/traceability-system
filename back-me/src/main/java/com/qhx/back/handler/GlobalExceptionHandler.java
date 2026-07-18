package com.qhx.back.handler;

import cn.hutool.json.JSONUtil;
import com.qhx.back.exception.WeBaseFrontException;
import com.qhx.back.model.Result;
import lombok.extern.slf4j.Slf4j;
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


    @ExceptionHandler(Exception.class)
    public Result handlerException(Exception excepxtion)
    {
        log.error("Exception：",excepxtion);
        return Result.error(excepxtion.getMessage());
    }
}





