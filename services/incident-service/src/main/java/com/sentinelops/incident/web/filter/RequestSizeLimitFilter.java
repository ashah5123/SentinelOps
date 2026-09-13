package com.sentinelops.incident.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects request bodies larger than the configured limit with {@code 413 Payload Too Large}.
 * Checks {@code Content-Length} up front when present, and additionally enforces the limit while
 * the body is read (covering chunked requests with no declared length), so an oversized body cannot
 * be used to exhaust server memory.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

  private final long maxRequestBodyBytes;

  public RequestSizeLimitFilter(
      @org.springframework.beans.factory.annotation.Value(
              "${sentinelops.incident-service.max-request-body-bytes}")
          long maxRequestBodyBytes) {
    this.maxRequestBodyBytes = maxRequestBodyBytes;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > maxRequestBodyBytes) {
      response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Request body too large");
      return;
    }
    filterChain.doFilter(new SizeLimitingRequestWrapper(request, maxRequestBodyBytes), response);
  }

  private static final class SizeLimitingRequestWrapper extends HttpServletRequestWrapper {
    private final long limit;

    SizeLimitingRequestWrapper(HttpServletRequest request, long limit) {
      super(request);
      this.limit = limit;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      ServletInputStream delegate = super.getInputStream();
      return new ServletInputStream() {
        private long bytesRead = 0;

        @Override
        public int read() throws IOException {
          int b = delegate.read();
          if (b != -1) {
            checkLimit(1);
          }
          return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
          int read = delegate.read(b, off, len);
          if (read > 0) {
            checkLimit(read);
          }
          return read;
        }

        private void checkLimit(int read) throws IOException {
          bytesRead += read;
          if (bytesRead > limit) {
            throw new IOException(
                "Request body exceeded maximum allowed size of " + limit + " bytes");
          }
        }

        @Override
        public boolean isFinished() {
          return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
          return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
          delegate.setReadListener(readListener);
        }
      };
    }
  }
}
