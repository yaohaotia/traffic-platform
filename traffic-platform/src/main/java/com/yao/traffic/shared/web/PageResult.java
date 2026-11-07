package com.yao.traffic.shared.web;

import java.util.List;

/** Pagination is bounded in the service, independent of any client-side filters. */
public record PageResult<T>(List<T> items, int page, int size) {
    public static int offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100 || page > 100_000)
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE", "页码须为1至100000，分页大小须为1至100");
        return Math.multiplyExact(page - 1, size);
    }
}
