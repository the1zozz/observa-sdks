package com.integralways.observa.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.integralways.observa.sdk.Observa;
import com.integralways.observa.sdk.Props;
import com.integralways.observa.sdk.Transport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The starter on the Spring Boot version of the build: 2.7 here, 3.5 and 4.1 in observa-spring-boot-tests, which runs
 * these same tests.
 */
class ObservaAutoConfigurationTest {

    private static final String KEY = "sk_test_0123456789abcdef";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservaAutoConfiguration.class));

    /** Records what the SDK would have sent. */
    static final class Captured implements Transport {
        final List<String> urls = new CopyOnWriteArrayList<>();
        final List<String> keys = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();

        @Override
        public Response send(String url, String secretKey, byte[] json) {
            urls.add(url);
            keys.add(secretKey);
            bodies.add(new String(json, StandardCharsets.UTF_8));
            return new Response(202, "{\"accepted\":1,\"rejected\":0}", 0);
        }
    }

    @Configuration
    static class CapturingTransport {
        final Captured captured = new Captured();

        @Bean
        Captured captured() {
            return captured;
        }

        @Bean
        ObservaBuilderCustomizer capture() {
            return builder -> builder.transport(captured);
        }
    }

    @Test
    void theKeyAndEndpointPropertiesMakeAWorkingObserva() {
        runner.withUserConfiguration(CapturingTransport.class)
                .withPropertyValues("observa.key=" + KEY, "observa.endpoint=https://observa.example.com/")
                .run(context -> {
                    Observa observa = context.getBean(Observa.class);
                    assertThat(observa.isEnabled()).isTrue();

                    observa.track("42", "booking.confirmed", Props.of("bookingId", "B-17"));
                    assertThat(observa.flush()).isTrue();

                    Captured captured = context.getBean(Captured.class);
                    assertThat(captured.urls).containsExactly("https://observa.example.com/ingest/v1/events");
                    assertThat(captured.keys).containsExactly(KEY);
                    assertThat(captured.bodies.get(0)).contains("\"type\":\"booking.confirmed\"", "\"bookingId\":\"B-17\"",
                            "\"userId\":\"42\"");
                });
    }

    @Test
    void withoutAKeyTheApplicationStillStartsAndObservaSendsNothing() {
        runner.withPropertyValues("observa.endpoint=https://observa.example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(Observa.class);
                    Observa observa = context.getBean(Observa.class);
                    observa.track("42", "booking.confirmed");
                    assertThat(observa.flush()).isTrue();
                });
    }

    @Test
    void enabledFalseSendsNothingButCanBeInjected() {
        runner.withUserConfiguration(CapturingTransport.class)
                .withPropertyValues("observa.enabled=false", "observa.key=" + KEY, "observa.endpoint=https://observa.example.com")
                .run(context -> {
                    Observa observa = context.getBean(Observa.class);
                    assertThat(observa.isEnabled()).isFalse();
                    observa.track("42", "booking.confirmed");
                    observa.flush();
                    assertThat(context.getBean(Captured.class).bodies).isEmpty();
                });
    }

    @Test
    void aPublishableKeyIsRefusedWithoutStoppingTheApplication() {
        runner.withPropertyValues("observa.key=pk_test_0123456789", "observa.endpoint=https://observa.example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(Observa.class).isEnabled()).isFalse();
                });
    }

    @Configuration
    static class OwnObserva {
        @Bean
        Observa myObserva() {
            return Observa.builder().enabled(false).build();
        }
    }

    @Test
    void anApplicationsOwnObservaIsKept() {
        runner.withUserConfiguration(OwnObserva.class)
                .withPropertyValues("observa.key=" + KEY, "observa.endpoint=https://observa.example.com")
                .run(context -> {
                    assertThat(context).hasSingleBean(Observa.class);
                    assertThat(context).hasBean("myObserva");
                });
    }

    @Test
    void closingTheApplicationSendsWhatIsQueued() {
        // A long flush interval: only the close can send it.
        runner.withUserConfiguration(CapturingTransport.class)
                .withPropertyValues("observa.key=" + KEY, "observa.endpoint=https://observa.example.com",
                        "observa.flush-interval=1h")
                .run(context -> {
                    context.getBean(Observa.class).track("42", "booking.cancelled");
                    Captured captured = context.getBean(Captured.class);
                    assertThat(captured.bodies).isEmpty();
                    context.close();
                    assertThat(captured.bodies).hasSize(1);
                    assertThat(captured.bodies.get(0)).contains("booking.cancelled");
                });
    }

    @Test
    void durationsAndNumbersBindTheSpringWay() {
        runner.withPropertyValues("observa.flush-interval=250ms", "observa.max-batch-size=20",
                        "observa.request-timeout=3s", "observa.shutdown-timeout=2s", "observa.debug=true")
                .run(context -> {
                    ObservaProperties properties = context.getBean(ObservaProperties.class);
                    assertThat(properties.getFlushInterval().toMillis()).isEqualTo(250);
                    assertThat(properties.getMaxBatchSize()).isEqualTo(20);
                    assertThat(properties.getRequestTimeout().getSeconds()).isEqualTo(3);
                    assertThat(properties.getShutdownTimeout().getSeconds()).isEqualTo(2);
                    assertThat(properties.isDebug()).isTrue();
                });
    }

    @Configuration
    @EnableAutoConfiguration
    static class App {
    }

    @Test
    void aSpringBootApplicationFindsTheStarterByItself() {
        // No AutoConfigurations.of(...): this is how an application picks the starter up, from its registration file.
        ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties("observa.key=" + KEY, "observa.endpoint=https://observa.example.com",
                        "spring.main.banner-mode=off")
                .run();
        try {
            assertThat(context.getBean(Observa.class).isEnabled()).isTrue();
            System.out.println("Observa starter tested on Spring Boot " + SpringBootVersion.getVersion());
        } finally {
            context.close();
        }
    }
}
