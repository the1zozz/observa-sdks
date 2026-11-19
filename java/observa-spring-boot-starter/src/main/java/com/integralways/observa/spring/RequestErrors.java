package com.integralways.observa.spring;

import java.util.LinkedHashMap;
import java.util.Map;

import com.integralways.observa.sdk.ErrorReport;
import com.integralways.observa.sdk.Observa;

/** What the web filters (javax and jakarta) share: the request's description, and how a failed request is reported. */
final class RequestErrors {

    /** Set by Spring's DispatcherServlet when a handler's exception was turned into a response, e.g. by an advice. */
    static final String HANDLED_EXCEPTION = "org.springframework.web.servlet.DispatcherServlet.EXCEPTION";

    private RequestErrors() {
    }

    static Map<String, Object> request(String method, String path) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method);
        request.put("path", path);
        return request;
    }

    static void report(Observa observa, Throwable error, boolean handled, Map<String, Object> request, int status,
                       String requestId) {
        Map<String, Object> http = new LinkedHashMap<>(request);
        if (status > 0) {
            http.put("status", status);
        }
        ErrorReport report = ErrorReport.of(unwrap(error)).handled(handled).property("http", http);
        if (requestId != null && !requestId.trim().isEmpty()) {
            report.requestId(requestId.trim());
        }
        observa.error(report);
    }

    /** A servlet exception that only wraps the real one says nothing: report the real one. */
    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        for (int i = 0; i < 5 && t != null && t.getCause() != null && t.getCause() != t
                && t.getClass().getName().endsWith("ServletException"); i++) {
            t = t.getCause();
        }
        return t;
    }

    static String requestId(java.util.function.Function<String, String> header) {
        for (String name : new String[] {"X-Request-Id", "X-Correlation-Id"}) {
            String value = header.apply(name);
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
