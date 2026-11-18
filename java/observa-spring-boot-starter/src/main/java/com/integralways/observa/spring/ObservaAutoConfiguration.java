package com.integralways.observa.spring;

import com.integralways.observa.sdk.Observa;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * One {@link Observa} for the application, built from the {@code observa.*} properties. Inject it anywhere:
 * <pre>{@code
 * @Service
 * class BookingService {
 *     private final Observa observa;
 *     BookingService(Observa observa) { this.observa = observa; }
 *
 *     void confirm(Booking b) {
 *         observa.track(b.userId(), "booking.confirmed", Props.of("bookingId", b.id()));
 *     }
 * }
 * }</pre>
 * It is always there to inject: with no key, or {@code observa.enabled=false}, it sends nothing and the application
 * starts as usual. Spring closes it with the application, which sends what is still queued. An application that defines
 * its own {@code Observa} bean keeps it.
 */
@AutoConfiguration
@ConditionalOnClass(Observa.class)
@EnableConfigurationProperties(ObservaProperties.class)
public class ObservaAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public Observa observa(ObservaProperties properties, ObjectProvider<ObservaBuilderCustomizer> customizers,
                           ObjectProvider<ObservaUserResolver> users, Environment environment) {
        Observa.Builder builder = Observa.builder()
                .enabled(properties.isEnabled())
                .debug(properties.isDebug())
                .flushInterval(properties.getFlushInterval())
                .maxBatchSize(properties.getMaxBatchSize())
                .maxQueueSize(properties.getMaxQueueSize())
                .requestTimeout(properties.getRequestTimeout())
                .shutdownTimeout(properties.getShutdownTimeout())
                .maxErrorsPerMinute(properties.getErrors().isEnabled() ? properties.getErrors().getMaxPerMinute() : 0)
                .userResolver(() -> {
                    ObservaUserResolver resolver = users.getIfAvailable();
                    return resolver == null ? null : resolver.currentUserId();
                })
                // Spring closes the bean with the context, in order, before the JVM stops.
                .shutdownHook(false);
        String service = hasText(properties.getService()) ? properties.getService()
                : environment.getProperty("spring.application.name");
        if (hasText(service)) {
            builder.service(service);
        }
        if (hasText(properties.getKey())) {
            builder.key(properties.getKey());
        }
        if (hasText(properties.getEndpoint())) {
            builder.endpoint(properties.getEndpoint());
        }
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
