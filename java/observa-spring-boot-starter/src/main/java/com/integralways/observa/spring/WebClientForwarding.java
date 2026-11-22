package com.integralways.observa.spring;

import java.net.URI;

import com.integralways.observa.sdk.Observa;
import com.integralways.observa.sdk.Visitor;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Forwards the visitor on the application's {@code WebClient.Builder} beans. The visitor is read when the request is
 * made, on the thread that subscribes: a servlet application calling {@code .block()}, or anything subscribing on the
 * request's thread. (A WebFlux server has no thread per request: there the visitor is not carried yet.)
 */
final class WebClientForwarding implements BeanPostProcessor {

    private final ExchangeFilterFunction filter;

    WebClientForwarding(VisitorRules rules) {
        this.filter = (request, next) -> {
            String header = Observa.visitorHeader();
            URI url = request.url();
            if (header == null || request.headers().getFirst(Visitor.HEADER) != null
                    || !rules.forwardsTo(url == null ? null : url.getHost())) {
                return next.exchange(request);
            }
            return next.exchange(ClientRequest.from(request).header(Visitor.HEADER, header).build());
        };
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof WebClient.Builder) {
            ((WebClient.Builder) bean).filter(filter);
        }
        return bean;
    }
}
