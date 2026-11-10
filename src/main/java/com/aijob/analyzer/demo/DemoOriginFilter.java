package com.aijob.analyzer.demo;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Browser-origin requests require an explicitly configured Chrome extension origin. */
@Component @Profile("!test")
public class DemoOriginFilter extends OncePerRequestFilter {
    private final String extensionOrigin;
    public DemoOriginFilter(@Value("${app.demo.extension-origin:}") String extensionOrigin) {
        this.extensionOrigin = extensionOrigin;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (request.getRequestURI().startsWith("/api/") && origin != null) {
            if (!extensionOrigin.startsWith("chrome-extension://") || !extensionOrigin.equals(origin)) {
                response.setStatus(403);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"ORIGIN_DENIED\",\"message\":\"Browser origin is not configured\"}");
                return;
            }
            response.setHeader("Access-Control-Allow-Origin", extensionOrigin);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type");
            if ("OPTIONS".equals(request.getMethod())) { response.setStatus(204); return; }
        }
        chain.doFilter(request, response);
    }
}
