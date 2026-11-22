package com.integralways.observa.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.bind.Binder;

import com.integralways.observa.sdk.Observa;

/**
 * One person across browser and server (the SDK contract, §9), with nothing to write: each request's
 * {@code Observa-Visitor} header is read, its events and errors carry the visitor and the visit, and the application's
 * RestTemplate, RestClient and WebClient beans forward it to the services they call. Off with
 * {@code observa.visitor.enabled=false}.
 */
@AutoConfiguration(after = ObservaAutoConfiguration.class)
@ConditionalOnClass(Observa.class)
@ConditionalOnProperty(prefix = "observa.visitor", name = "enabled", matchIfMissing = true)
public class ObservaVisitorAutoConfiguration {

    /**
     * The settings, read straight from the environment: the forwarding post-processors are made before the
     * application's beans, so they cannot wait for the properties bean.
     */
    static VisitorRules rules(Environment environment) {
        ObservaProperties.Visitor settings = Binder.get(environment)
                .bind("observa.visitor", ObservaProperties.Visitor.class).orElseGet(ObservaProperties.Visitor::new);
        return new VisitorRules(settings);
    }

    /** Spring Boot 3 and 4. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = {"jakarta.servlet.Filter", ObservaErrorsAutoConfiguration.SPRING_6})
    static class JakartaWeb {
        @Bean
        JakartaVisitorFilter observaJakartaVisitorFilter(Environment environment) {
            return new JakartaVisitorFilter(rules(environment));
        }
    }

    /** Spring Boot 2.7. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "javax.servlet.Filter")
    @ConditionalOnMissingClass(ObservaErrorsAutoConfiguration.SPRING_6)
    static class JavaxWeb {
        @Bean
        JavaxVisitorFilter observaJavaxVisitorFilter(Environment environment) {
            return new JavaxVisitorFilter(rules(environment));
        }
    }

    /** RestTemplate and RestClient (spring-web). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.http.client.ClientHttpRequestInterceptor")
    static class Http {
        @Bean
        static ObservaVisitorInterceptor observaVisitorInterceptor(Environment environment) {
            return new ObservaVisitorInterceptor(rules(environment));
        }

        @Bean
        static VisitorForwarding observaVisitorForwarding(Environment environment) {
            return new VisitorForwarding(new ObservaVisitorInterceptor(rules(environment)));
        }
    }

    /** WebClient (spring-webflux). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
    static class Reactive {
        @Bean
        static WebClientForwarding observaWebClientForwarding(Environment environment) {
            return new WebClientForwarding(rules(environment));
        }
    }
}
