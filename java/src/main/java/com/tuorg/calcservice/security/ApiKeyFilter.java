package com.tuorg.calcservice.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ApiKeyFilter implements Filter {

  private final Environment env;
  private final String headerName = "X-API-KEY";

  public ApiKeyFilter(Environment env) {
    this.env = env;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
          throws IOException, ServletException {

    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse resp = (HttpServletResponse) response;

    if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
      resp.setHeader("Access-Control-Allow-Origin", "*");
      resp.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
      resp.setHeader("Access-Control-Allow-Headers", "Content-Type, X-API-KEY");
      resp.setHeader("Access-Control-Max-Age", "3600");
      chain.doFilter(request, response);
      return;
    }

    String path = req.getRequestURI();
    if (path.startsWith("/api/ping") || path.startsWith("/actuator")) {
      chain.doFilter(request, response);
      return;
    }

    String apiKey = req.getHeader(headerName);
    String expected = env.getProperty("app.api.key", "");

    if (expected == null || expected.isEmpty()) {
      resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "API key not configured");
      return;
    }

    if (expected.equals(apiKey)) {
      chain.doFilter(request, response);
    } else {
      resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key");
    }
  }

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {}

  @Override
  public void destroy() {}
}
