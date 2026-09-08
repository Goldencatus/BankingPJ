package com.bankingpj.backend.common.response;

import java.util.List;
import org.springframework.data.domain.Page;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements,
                              int totalPages, boolean first, boolean last) {
    // Spring 페이지에서 공개할 항목과 페이지 메타데이터만 복사한다.
    public static <T> PageResponse<T> from(Page<T> result) {
        return new PageResponse<>(List.copyOf(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
    }
}
