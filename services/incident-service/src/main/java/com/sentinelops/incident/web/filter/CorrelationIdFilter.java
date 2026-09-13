package com.sentinelops.incident.web.filter;

import com.sentinelops.incident.application.CorrelationIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts an inbound {@code X-Correlation-ID} header or generates one, makes it available to the
 * rest of the request (via {@link #CORRELATION_ID_ATTRIBUTE} and the logging MDC under {@code
 * correlationId}, which the JSON log encoder includes in every log line), and returns it on every
 * response so callers can correlate their request with logs, database records, and downstream
 * events.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
  public static final String CORRELATION_ID_ATTRIBUTE = "correlationId";
  private static final String MDC_KEY = "correlationId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = CorrelationIds.orGenerate(request.getHeader(CORRELATION_ID_HEADER));
    request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    MDC.put(MDC_KEY, correlationId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  public static String currentOrGenerate(HttpServletRequest request) {
    Object attribute = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
    return attribute != null ? attribute.toString() : CorrelationIds.generate();
  }
}
