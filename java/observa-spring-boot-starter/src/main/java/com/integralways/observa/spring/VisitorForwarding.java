package com.integralways.observa.spring;

import java.lang.reflect.Method;
import java.util.List;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

/**
 * Adds {@link ObservaVisitorInterceptor} to the application's HTTP client beans: every {@code RestTemplate}, and every
 * {@code RestClient.Builder} (Spring 6.1 and later, reached by name so the starter still runs on Spring 5). WebClient
 * builders are handled by {@link WebClientForwarding}, only when WebFlux is there.
 */
final class VisitorForwarding implements BeanPostProcessor {

    static final String REST_CLIENT_BUILDER = "org.springframework.web.client.RestClient$Builder";

    private final ObservaVisitorInterceptor interceptor;

    VisitorForwarding(ObservaVisitorInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof RestTemplate) {
            List<ClientHttpRequestInterceptor> interceptors = ((RestTemplate) bean).getInterceptors();
            if (!interceptors.contains(interceptor)) {
                interceptors.add(interceptor);
            }
        } else if (isRestClientBuilder(bean.getClass())) {
            try {
                // Through the public interface: the builder's own class is not public.
                Method add = Class.forName(REST_CLIENT_BUILDER, false, bean.getClass().getClassLoader())
                        .getMethod("requestInterceptor", ClientHttpRequestInterceptor.class);
                add.invoke(bean, interceptor);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // A builder we do not know: it simply does not forward.
            }
        }
        return bean;
    }

    static boolean isRestClientBuilder(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().equals(REST_CLIENT_BUILDER)) {
                    return true;
                }
            }
        }
        return false;
    }
}
