package com.integralways.observa.spring;

import java.net.URI;

import com.integralways.observa.sdk.Observa;
import com.integralways.observa.sdk.Visitor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Target;

/**
 * Forwards the visitor on OpenFeign calls, as {@link ObservaVisitorInterceptor} does on RestTemplate. Spring Cloud
 * OpenFeign applies every {@code RequestInterceptor} bean to every Feign client, so the application writes nothing.
 * A Feign client built by hand takes it with {@code Feign.builder().requestInterceptor(forwarding)} (it is a bean).
 *
 * <p>With a load balancer the host is the service's name ({@code http://payments/...}); {@code forward-hosts} matches
 * that name.
 */
public final class FeignForwarding implements RequestInterceptor {

    private final VisitorRules rules;

    FeignForwarding(VisitorRules rules) {
        this.rules = rules;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (!rules.forwardsTo(host(template))) {
            return;
        }
        String header = Observa.visitorHeader();
        if (header != null && !template.headers().containsKey(Visitor.HEADER)) {
            template.header(Visitor.HEADER, header);
        }
        String traceparent = Observa.traceparentHeader();
        if (traceparent != null && !template.headers().containsKey(Observa.TRACEPARENT)) {
            template.header(Observa.TRACEPARENT, traceparent);
        }
    }

    /** The called host: from the client's target, or from the request's own URL when it names one. */
    static String host(RequestTemplate template) {
        String url = null;
        Target<?> target = template.feignTarget();
        if (target != null) {
            url = target.url();
        }
        if (url == null || url.isEmpty()) {
            url = template.url();
        }
        try {
            return url == null ? null : URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
