package com.qhx.back.model.vo;

import lombok.Getter;

import java.util.List;

/**
 * 分页结果：page 从 1 开始；超出末页时 records 为空、total 照常返回。
 */
@Getter
public class PageResult<T> {
    private final List<T> records;
    private final long total;
    private final int page;
    private final int size;

    public PageResult(List<T> records, long total, int page, int size) {
        this.records = records;
        this.total = total;
        this.page = page;
        this.size = size;
    }
}
