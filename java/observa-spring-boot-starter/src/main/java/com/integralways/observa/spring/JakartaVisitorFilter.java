package com.integralways.observa.spring;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import com.integralways.observa.sdk.Observa;
import com.integralways.observa.sdk.Visitor;
import org.springframework.core.Ordered;

/**
 * Reads the browser's visitor from each request (Spring Boot 3 and 4: jakarta.servlet): the {@code Observa-Visitor} header, or
 * {@code observa.visitor} in {@code baggage}. While the request runs, its events and errors carry the visitor and
 * the visit, and calls to other services forward it. It runs outside the error filter, so a failed request's error
 * carries it too.
 */
final class JakartaVisitorFilter implements Filter, Ordered {

    private final VisitorRules rules;

    JakartaVisitorFilter(VisitorRules rules) {
        this.rules = rules;
    }

    @Override
    public int getOrder() {
        return -110; // before Spring Security (-100) and the error filter (0)
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (!(req instanceof HttpServletRequest)) {
            chain.doFilter(req, res);
            return;
        }
        HttpServletRequest request = (HttpServletRequest) req;
        Visitor before = Observa.currentVisitor();
        Observa.setVisitor(rules.read(request.getRequestURI(), request::getHeader));
        try {
            chain.doFilter(req, res);
        } finally {
            Observa.setVisitor(before);
        }
    }
}
