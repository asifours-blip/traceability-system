package com.qhx.back.exception;
public class WeBaseFrontException extends RuntimeException
{
    public String mes;
    public WeBaseFrontException(Throwable cause)
    {
        super(cause);
    }

    public WeBaseFrontException(String message)
    {
        super(message);
        this.mes = message;
    }
}
