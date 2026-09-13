package com.sentinelops.incident.web.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** A stable, explicit pagination envelope, independent of Spring Data's internal page types. */
public record PageResponse<T>(
    List<T> content, int page, int size, long totalElements, int totalPages, boolean last) {

  public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
    return new PageResponse<>(
        page.getContent().stream().map(mapper).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages(),
        page.isLast());
  }
}
