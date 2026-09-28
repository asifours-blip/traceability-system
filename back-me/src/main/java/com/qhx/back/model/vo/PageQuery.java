package com.qhx.back.model.vo;

import com.qhx.back.exception.ValidationException;

/**
 * 分页参数：page ≥ 1，1 ≤ size ≤ MAX_SIZE；不合规返回 400（不静默纠正，避免前端以为拿到了想要的那一页）。
 */
public final class PageQuery {
    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 100;

    public final int page;
    public final int size;

    private PageQuery(int page, int size) {
        this.page = page;
        this.size = size;
    }

    public static PageQuery of(Integer page, Integer size) {
        int p = page == null ? 1 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 1) {
            throw ValidationException.of("page", "必须 ≥ 1");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw ValidationException.of("size", "必须在 1 到 " + MAX_SIZE + " 之间");
        }
        // 防止 offset 溢出 int
        if ((long) (p - 1) * s > Integer.MAX_VALUE) {
            throw ValidationException.of("page", "过大");
        }
        return new PageQuery(p, s);
    }

    public int offset() {
        return (page - 1) * size;
    }
}
