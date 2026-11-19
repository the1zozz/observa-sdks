package com.integralways.observa.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.integralways.observa.sdk.Observa;
import com.integralways.observa.sdk.Transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors with the starter, in a real web application on a random port: what Observa receives when a request fails,
 * when an advice turns an exception into a 500, and when the application logs an error. The same test runs on Spring
 * Boot 2.7, 3.5 and 4.1.
 */
class ObservaErrorsTest {

    /** The events the SDK would have sent, as JSON text. */
    static final List<String> sent = new CopyOnWriteArrayList<>();

    static class Payment extends RuntimeException {
        Payment(String message) {
            super(message);
        }
    }

    @RestController
    static class Endpoints {
        private static final Logger log = LoggerFactory.getLogger("com.acme.Bookings");

        @GetMapping("/fine")
        String fine() {
            return "ok";
        }

        @GetMapping("/escapes")
        String escapes() {
            throw new IllegalStateException("No room left");
        }

        @GetMapping("/advised")
        String advised() {
            throw new Payment("Card declined");
        }

        @GetMapping("/not-found")
        String notFound() {
            throw new IllegalArgumentException("No such booking");
        }

        @GetMapping("/logged")
        String logged() {
            log.error("Reminder for booking {} not sent", "B-17", new IllegalStateException("Mail server down"));
            return "carried on";
        }
    }

    @RestControllerAdvice
    static class Advice {
        @ExceptionHandler(Payment.class)
        ResponseEntity<Map<String, String>> payment(Payment e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(java.util.Collections.singletonMap("error", "payment"));
        }

        @ExceptionHandler(IllegalArgumentException.class)
        ResponseEntity<Map<String, String>> notFound(IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(java.util.Collections.singletonMap("error", "not found"));
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class App {
        @Bean
        Endpoints endpoints() {
            return new Endpoints();
        }

        @Bean
        Advice advice() {
            return new Advice();
        }

        @Bean
        ObservaUserResolver user() {
            return () -> "u-7";
        }

        @Bean
        ObservaBuilderCustomizer capture() {
            return builder -> builder.transport((url, key, json) -> {
                sent.add(new String(json, StandardCharsets.UTF_8));
                return new Transport.Response(202, "{\"accepted\":1,\"rejected\":0}", 0);
            });
        }
    }

    ConfigurableApplicationContext context;
    String base;

    @BeforeEach
    void start() {
        sent.clear();
        context = new SpringApplicationBuilder(App.class)
                .properties("server.port=0", "spring.main.banner-mode=off", "spring.application.name=booking-api",
                        "observa.key=sk_test_0123456789abcdef", "observa.endpoint=https://observa.example.com")
                .run();
        base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    int get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestProperty("X-Request-Id", "req-" + path.substring(1));
        int status = c.getResponseCode();
        c.disconnect();
        return status;
    }

    /**
     * The $error events sent, as JSON text, once {@code expected} have arrived (or after 3 s). A response can reach the
     * client a moment before the filter reports its error, so the test waits for it; for none, it waits briefly.
     */
    List<String> errors(int expected) throws InterruptedException {
        long end = System.currentTimeMillis() + (expected == 0 ? 500 : 3000);
        List<String> errors = sentErrors();
        while (errors.size() < expected || (expected == 0 && System.currentTimeMillis() < end)) {
            if (System.currentTimeMillis() > end) {
                break;
            }
            Thread.sleep(20);
            errors = sentErrors();
        }
        return errors;
    }

    List<String> sentErrors() {
        context.getBean(Observa.class).flush();
        List<String> errors = new ArrayList<>();
        for (String batch : sent) {
            for (String event : batch.split("\\{\"id\":")) {
                if (event.contains("\"type\":\"$error\"")) {
                    errors.add(event);
                }
            }
        }
        return errors;
    }

    @Test
    void aRequestThatFailsIsReportedWithThePersonAndTheRequest() throws Exception {
        assertThat(get("/escapes")).isEqualTo(500);
        List<String> errors = errors(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("\"kind\":\"java.lang.IllegalStateException\"", "\"message\":\"No room left\"",
                "\"userId\":\"u-7\"", "\"handled\":false", "\"method\":\"GET\"", "\"path\":\"/escapes\"", "\"status\":500",
                "\"requestId\":\"req-escapes\"", "\"service\":\"booking-api\"");
    }

    @Test
    void anAdviceThatAnswers500StillReportsTheError() throws Exception {
        assertThat(get("/advised")).isEqualTo(500);
        List<String> errors = errors(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("Payment", "\"message\":\"Card declined\"", "\"handled\":true", "\"status\":500");
    }

    @Test
    void clientErrorsAndGoodRequestsAreNotErrors() throws Exception {
        assertThat(get("/not-found")).isEqualTo(404);
        assertThat(get("/fine")).isEqualTo(200);
        assertThat(errors(0)).isEmpty();
    }

    @Test
    void anErrorLoggedDuringARequestCarriesItAndThePerson() throws Exception {
        assertThat(get("/logged")).isEqualTo(200);
        List<String> errors = errors(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("\"message\":\"Mail server down\"", "Reminder for booking B-17 not sent",
                "\"logger\":\"com.acme.Bookings\"", "\"userId\":\"u-7\"", "\"path\":\"/logged\"");
    }

    @Test
    void errorsCanBeTurnedOff() throws Exception {
        context.close();
        sent.clear();
        context = new SpringApplicationBuilder(App.class)
                .properties("server.port=0", "spring.main.banner-mode=off", "observa.errors.enabled=false",
                        "observa.key=sk_test_0123456789abcdef", "observa.endpoint=https://observa.example.com")
                .run();
        base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
        assertThat(get("/escapes")).isEqualTo(500);
        assertThat(get("/logged")).isEqualTo(200);
        assertThat(errors(0)).isEmpty();
    }
}
