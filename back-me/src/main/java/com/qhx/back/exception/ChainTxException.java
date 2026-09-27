package com.qhx.back.exception;

/**
 * 交易没有被确认成功（失败、未知、被拒绝重复提交），携带返回给前端的 HTTP 状态码与交易记录。
 */
public class ChainTxException extends RuntimeException
{
    private final int status;
    private final transient Object data;

    public ChainTxException(int status, String message, Object data)
    {
        super(message);
        this.status = status;
        this.data = data;
    }

    public int getStatus()
    {
        return status;
    }

    public Object getData()
    {
        return data;
    }
}
