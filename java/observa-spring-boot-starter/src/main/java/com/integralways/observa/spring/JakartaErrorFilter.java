package com.integralways.observa.spring;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.integralways.observa.sdk.Observa;
import org.springframework.core.Ordered;

/**
 * Reports a request that failed with a server error (Spring Boot 3 and 4: jakarta.servlet): an exception that
 * escaped (handled false), or a 5xx answer made from an exception by an advice or a resolver (handled true). Errors
 * logged while the request runs carry its method and path. It runs inside Spring Security's filters, so the
 * signed-in user is known.
 */
final class JakartaErrorFilter implements Filter, Ordered {

    private final Observa observa;

    JakartaErrorFilter(Observa observa) {
        this.observa = observa;
    }

    @Override
    public int getOrder() {
        return 0; // after Spring Security (-100): the user is known while the request runs
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (!(req instanceof HttpServletRequest) || !(res instanceof HttpServletResponse) || !observa.isEnabled()) {
            chain.doFilter(req, res);
            return;
        }
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        Map<String, Object> described = RequestErrors.request(request.getMethod(), request.getRequestURI());
        Observa.setRequest(described);
        try {
            chain.doFilter(req, res);
            if (response.getStatus() >= 500) {
                Object handled = request.getAttribute(RequestErrors.HANDLED_EXCEPTION);
                if (handled instanceof Throwable) {
                    RequestErrors.report(observa, (Throwable) handled, true, described, response.getStatus(),
                            RequestErrors.requestId(request::getHeader));
                }
            }
        } catch (IOException | ServletException | RuntimeException | Error e) {
            RequestErrors.report(observa, e, false, described, 500, RequestErrors.requestId(request::getHeader));
            throw e;
        } finally {
            Observa.clearRequest();
        }
    }
}
