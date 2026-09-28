package com.qhx.back.handler;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.qhx.back.exception.AuthException;
import com.qhx.back.exception.BusinessException;
import com.qhx.back.exception.ChainTxException;
import com.qhx.back.exception.ValidationException;
import com.qhx.back.exception.WeBaseFrontException;
import com.qhx.back.file.FileRejectedException;
import com.qhx.back.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Collections;
import java.util.stream.Collectors;
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


    // 业务规则不满足（含字段校验 400）：HTTP 状态码与 body.code 一致
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> handlerBusinessException(BusinessException exception)
    {
        return ResponseEntity.status(exception.getStatus())
                .body(new Result(exception.getData(), exception.getMessage(), exception.getStatus()));
    }


    // 上传被拒：HTTP 状态码与 body.code 一致，data.errorCode 为机器可读的错误码
    @ExceptionHandler(FileRejectedException.class)
    public ResponseEntity<Result> handlerFileRejected(FileRejectedException exception)
    {
        log.warn("upload rejected: {} {}", exception.getErrorCode(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus()).body(new Result(
                Collections.singletonMap("errorCode", exception.getErrorCode()), exception.getMessage(), exception.getStatus()));
    }


    // 超过 spring.servlet.multipart 的上限：容器在解析请求时就已拒绝，文件没有进入业务代码
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result> handlerMaxUpload(MaxUploadSizeExceededException exception)
    {
        return handlerFileRejected(new FileRejectedException(413, "FILE_TOO_LARGE", "文件超过大小上限"));
    }


    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result> handlerMissingPart(MissingServletRequestPartException exception)
    {
        return handlerFileRejected(new FileRejectedException(400, "FILE_EMPTY", "缺少文件（表单字段 file）"));
    }


    // 请求体无法解析（如数量传了 "abc"）：400，并尽量指出字段
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result> handlerNotReadable(HttpMessageNotReadableException exception)
    {
        String field = "body";
        if (exception.getCause() instanceof JsonMappingException) {
            JsonMappingException jme = (JsonMappingException) exception.getCause();
            String path = jme.getPath().stream()
                    .map(r -> r.getFieldName() != null ? r.getFieldName() : String.valueOf(r.getIndex()))
                    .collect(Collectors.joining("."));
            if (!path.isEmpty()) {
                field = path;
            }
        }
        return handlerBusinessException(ValidationException.of(field, "格式不正确，无法解析"));
    }


    @ExceptionHandler(Exception.class)
    public Result handlerException(Exception excepxtion)
    {
        log.error("Exception：",excepxtion);
        return Result.error(excepxtion.getMessage());
    }
}





