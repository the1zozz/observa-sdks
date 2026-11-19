package com.integralways.observa.spring;

import java.io.IOException;

import com.integralways.observa.sdk.Observa;
import com.integralways.observa.sdk.Visitor;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Forwards the visitor of the current work to the service being called, as the {@code Observa-Visitor} header. The
 * starter adds it to the application's RestTemplate and RestClient beans; add it yourself to one you build in code:
 * {@code new RestTemplate().getInterceptors().add(interceptor)} (inject it: it is a bean).
 */
public final class ObservaVisitorInterceptor implements ClientHttpRequestInterceptor {

    private final VisitorRules rules;

    ObservaVisitorInterceptor(VisitorRules rules) {
        this.rules = rules;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String header = Observa.visitorHeader();
        if (header != null && request.getHeaders().getFirst(Visitor.HEADER) == null
                && rules.forwardsTo(request.getURI().getHost())) {
            request.getHeaders().set(Visitor.HEADER, header);
        }
        return execution.execute(request, body);
    }
}
