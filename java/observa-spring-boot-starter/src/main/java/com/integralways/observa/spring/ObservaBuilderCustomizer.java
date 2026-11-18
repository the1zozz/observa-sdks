package com.integralways.observa.spring;

import com.integralways.observa.sdk.Observa;

/**
 * Changes what the {@code observa.*} properties cannot, before the {@link Observa} bean is built, e.g. its transport:
 * <pre>{@code
 * @Bean
 * ObservaBuilderCustomizer viaProxy() {
 *     return builder -> builder.transport(new MyProxyTransport());
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ObservaBuilderCustomizer {

    void customize(Observa.Builder builder);
}
