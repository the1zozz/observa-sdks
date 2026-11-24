package com.integralways.observa.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.integralways.observa.sdk.Observa;
import com.integralways.observa.sdk.Props;
import com.integralways.observa.sdk.Transport;
import com.integralways.observa.sdk.Visitor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * One person across browser and server (the SDK contract, §9), in a real web application on a random port: the
 * request's Observa-Visitor header reaches its events and errors, and the calls it makes to other services. The same
 * test runs on Spring Boot 2.7, 3.5 and 4.1.
 */
class ObservaVisitorTest {

    static final String HEADER = "3f2c9a4e-8b1d-4c7e-9f0a-2d6b5e8c1a47;9e1b7c20-4f3a-4d8e-b6c1-0a2f9d3e7b54";
    static final String VISITOR = "3f2c9a4e-8b1d-4c7e-9f0a-2d6b5e8c1a47";
    static final String SESSION = "9e1b7c20-4f3a-4d8e-b6c1-0a2f9d3e7b54";

    static final List<String> sent = new CopyOnWriteArrayList<>();
    static final AtomicReference<String> signedIn = new AtomicReference<>();
    static volatile String base;

    @RestController
    static class Endpoints {
        private final Observa observa;
        private final RestTemplate rest;

        Endpoints(Observa observa, RestTemplate rest) {
            this.observa = observa;
            this.rest = rest;
        }

        @GetMapping("/bookings/start")
        String start() {
            observa.track(null, "booking.started");
            observa.identify("42", Props.of("plan", "gold"));
            return "ok";
        }

        /** Sign-in on a server-only visit: links the visitor the server made to the user. */
        @GetMapping("/sign-in")
        String signIn() {
            Visitor visitor = Observa.currentVisitor();
            observa.identify("42", visitor == null ? null : visitor.anonymousId(), Props.of("plan", "gold"));
            return visitor == null ? "none" : visitor.header();
        }

        @GetMapping("/bookings/fail")
        String fail() {
            throw new IllegalStateException("No room left");
        }

        @GetMapping("/webhooks/payment")
        String webhook() {
            observa.error(new IllegalStateException("Unknown payment"));
            return "ok";
        }

        /** Calls another service (here: this application's /echo) with the application's RestTemplate. */
        @GetMapping("/bookings/call")
        String call() {
            return rest.getForObject(base + "/echo", String.class);
        }

        @GetMapping("/echo")
        String echo(@RequestHeader(value = Visitor.HEADER, required = false) String visitor) {
            return visitor == null ? "none" : visitor;
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class App {
        @Bean
        RestTemplate restTemplate() {
            return new RestTemplate();
        }

        @Bean
        Endpoints endpoints(Observa observa, RestTemplate rest) {
            return new Endpoints(observa, rest);
        }

        @Bean
        ObservaUserResolver user() {
            return signedIn::get;
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

    void start(String... properties) {
        sent.clear();
        signedIn.set(null);
        List<String> all = new ArrayList<>();
        all.add("server.port=0");
        all.add("spring.main.banner-mode=off");
        all.add("spring.application.name=bookings");
        all.add("observa.key=sk_test_0123456789abcdef");
        all.add("observa.endpoint=https://observa.example.com");
        all.add("observa.visitor.ignore-paths=/webhooks/**");
        for (String p : properties) {
            all.add(p);
        }
        context = new SpringApplicationBuilder(App.class).properties(all.toArray(new String[0])).run();
        base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    String get(String path, String visitor, String baggage) throws Exception {
        return get(path, visitor, baggage, new String[0]);
    }

    /** With more request headers, as name, value pairs (e.g. "Cookie", "device_id=d-1"). */
    String get(String path, String visitor, String baggage, String... headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            c.setRequestProperty(headers[i], headers[i + 1]);
        }
        if (visitor != null) {
            c.setRequestProperty(Visitor.HEADER, visitor);
        }
        if (baggage != null) {
            c.setRequestProperty("baggage", baggage);
        }
        int status = c.getResponseCode();
        String body = "";
        if (status < 400) {
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            body = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        c.disconnect();
        return status + " " + body;
    }

    /** The events sent so far of {@code type}, as JSON text, once {@code expected} have arrived (or after 3 s). */
    List<String> events(String type, int expected) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        List<String> found;
        do {
            context.getBean(Observa.class).flush();
            found = new ArrayList<>();
            for (String batch : sent) {
                for (String event : batch.split("\\{\"id\":")) {
                    if (event.contains("\"type\":\"" + type + "\"")) {
                        found.add(event);
                    }
                }
            }
            if (found.size() >= expected) {
                break;
            }
            Thread.sleep(20);
        } while (System.currentTimeMillis() < end);
        return found;
    }

    @Test
    void eventsInARequestCarryTheVisitorButProfileEventsNever() throws Exception {
        start();
        assertThat(get("/bookings/start", HEADER, null)).isEqualTo("200 ok");
        List<String> started = events("booking.started", 1);
        assertThat(started).hasSize(1);
        assertThat(started.get(0)).contains("\"anonymousId\":\"" + VISITOR + "\"", "\"sessionId\":\"" + SESSION + "\"")
                .doesNotContain("userId");
        List<String> identify = events("$identify", 1);
        assertThat(identify).hasSize(1);
        assertThat(identify.get(0)).contains("\"userId\":\"42\"").doesNotContain(VISITOR, SESSION);
    }

    @Test
    void theSignedInUserKeepsTheirIdInTheVisit() throws Exception {
        start();
        signedIn.set("7");
        get("/bookings/start", HEADER, null);
        String started = events("booking.started", 1).get(0);
        assertThat(started).contains("\"userId\":\"7\"", "\"sessionId\":\"" + SESSION + "\"");
    }

    @Test
    void aFailedRequestsErrorIsOnTheVisitor() throws Exception {
        start();
        assertThat(get("/bookings/fail", HEADER, null)).startsWith("500");
        List<String> errors = events("$error", 1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("\"anonymousId\":\"" + VISITOR + "\"", "\"sessionId\":\"" + SESSION + "\"",
                "\"path\":\"/bookings/fail\"").doesNotContain("service:bookings");
    }

    @Test
    void baggageWorksToo() throws Exception {
        start();
        get("/bookings/start", null, "tier=gold,observa.visitor=" + HEADER.replace(";", "%3B"));
        assertThat(events("booking.started", 1).get(0)).contains(VISITOR, SESSION);
    }

    @Test
    void aMalformedHeaderOrAnIgnoredPathChangesNothing() throws Exception {
        start();
        get("/bookings/fail", "42;x;y", null);
        get("/webhooks/payment", HEADER, null);
        List<String> errors = events("$error", 2);
        assertThat(errors).hasSize(2);
        for (String error : errors) {
            assertThat(error).contains("\"anonymousId\":\"service:bookings\"").doesNotContain("sessionId");
        }
    }

    @Test
    void callsToOtherServicesForwardIt() throws Exception {
        start();
        assertThat(get("/bookings/call", HEADER, null)).isEqualTo("200 " + HEADER);
        assertThat(get("/bookings/call", null, null)).isEqualTo("200 none");
    }

    @Test
    void forwardingCanBeLimitedToTheSystemsOwnHosts() throws Exception {
        start("observa.visitor.forward-hosts=billing,*.internal.example.com");
        assertThat(get("/bookings/call", HEADER, null)).as("localhost is not listed").isEqualTo("200 none");
    }

    @Test
    void itCanBeTurnedOff() throws Exception {
        start("observa.visitor.enabled=false");
        get("/bookings/start", HEADER, null);
        assertThat(events("booking.started", 1)).isEmpty();
        assertThat(get("/bookings/call", HEADER, null)).isEqualTo("200 none");
    }

    @Test
    void webClientBuildersForwardIt() throws Exception {
        start();
        WebClient.Builder builder = WebClient.builder();
        context.getBean(WebClientForwarding.class).postProcessAfterInitialization(builder, "webClientBuilder");
        List<ExchangeFilterFunction> filters = new ArrayList<>();
        builder.filters(filters::addAll);
        assertThat(filters).hasSize(1);
        AtomicReference<String> seen = new AtomicReference<>();
        ExchangeFunction billing = request -> {
            seen.set(request.headers().getFirst(Visitor.HEADER));
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        ClientRequest charge = ClientRequest.create(HttpMethod.GET, URI.create("http://billing/charges")).build();
        Observa.withVisitor(HEADER, () -> filters.get(0).filter(charge, billing).block());
        assertThat(seen.get()).isEqualTo(HEADER);
        filters.get(0).filter(charge, billing).block();
        assertThat(seen.get()).as("no visitor, no header").isNull();
    }

    /** A Feign client, as Spring Cloud OpenFeign makes one: every RequestInterceptor bean is applied to it. */
    interface Echo {
        @feign.RequestLine("GET /echo")
        String echo();

        @feign.RequestLine("GET /echo")
        @feign.Headers(Visitor.HEADER + ": app-own;app-visit")
        String echoWithOwnHeader();
    }

    @Test
    void feignClientsForwardIt() throws Exception {
        start();
        Echo echo = feign.Feign.builder().requestInterceptors(context.getBeansOfType(feign.RequestInterceptor.class).values())
                .target(Echo.class, base);
        assertThat(Observa.withVisitor(HEADER, echo::echo)).isEqualTo(HEADER);
        assertThat(echo.echo()).as("no visitor, no header").isEqualTo("none");
        assertThat(Observa.withVisitor(HEADER, echo::echoWithOwnHeader)).as("the application's own header is kept")
                .isEqualTo("app-own;app-visit");
    }

    @Test
    void feignForwardingKeepsToTheListedHosts() throws Exception {
        start("observa.visitor.forward-hosts=payments");
        FeignForwarding forwarding = context.getBean(FeignForwarding.class);
        Echo local = feign.Feign.builder().requestInterceptor(forwarding).target(Echo.class, base);
        assertThat(Observa.withVisitor(HEADER, local::echo)).as("localhost is not listed").isEqualTo("none");
        feign.RequestTemplate payments = new feign.RequestTemplate();
        payments.feignTarget(new feign.Target.HardCodedTarget<>(Echo.class, "http://payments"));
        Observa.withVisitor(HEADER, () -> {
            forwarding.apply(payments);
            return null;
        });
        assertThat(payments.headers().get(Visitor.HEADER)).as("a load-balanced service name").containsExactly(HEADER);
    }

    @Test
    void restClientBuildersForwardIt() throws Exception {
        Class<?> restClient;
        try {
            restClient = Class.forName("org.springframework.web.client.RestClient");
        } catch (ClassNotFoundException e) {
            Assumptions.abort("RestClient is Spring 6.1 and later");
            return;
        }
        start();
        ClassLoader loader = restClient.getClassLoader();
        Class<?> builderType = Class.forName("org.springframework.web.client.RestClient$Builder", false, loader);
        Class<?> uriSpec = Class.forName("org.springframework.web.client.RestClient$UriSpec", false, loader);
        Class<?> headersSpec = Class.forName("org.springframework.web.client.RestClient$RequestHeadersSpec", false, loader);
        Class<?> responseSpec = Class.forName("org.springframework.web.client.RestClient$ResponseSpec", false, loader);
        Object builder = restClient.getMethod("builder").invoke(null);
        context.getBean(VisitorForwarding.class).postProcessAfterInitialization(builder, "restClientBuilder");
        Object client = builderType.getMethod("build").invoke(builder);
        Object answer = Observa.withVisitor(HEADER, () -> {
            try {
                Object spec = restClient.getMethod("get").invoke(client);
                Object request = uriSpec.getMethod("uri", String.class, Object[].class).invoke(spec, base + "/echo", new Object[0]);
                Object response = headersSpec.getMethod("retrieve").invoke(request);
                return responseSpec.getMethod("body", Class.class).invoke(response, String.class);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(answer).isEqualTo(HEADER);
    }

    @Test
    void withoutTheWebSdkTheServerMakesTheVisitorFromItsCookie() throws Exception {
        start("observa.visitor.anonymous-from=cookie:device_id", "observa.visitor.session-from=cookie:JSESSIONID");
        Visitor made = Visitor.derived("d-7f3a", "A1B2C3.node0");
        get("/bookings/start", null, null, "Cookie", "device_id=d-7f3a; JSESSIONID=A1B2C3.node0");
        String started = events("booking.started", 1).get(0);
        assertThat(started).contains("\"anonymousId\":\"" + made.anonymousId() + "\"",
                "\"sessionId\":\"" + made.sessionId() + "\"").doesNotContain("d-7f3a", "A1B2C3");
        assertThat(get("/bookings/fail", null, null, "Cookie", "device_id=d-7f3a")).startsWith("500");
        assertThat(events("$error", 1).get(0)).contains(made.anonymousId()).doesNotContain("service:bookings");
        assertThat(get("/bookings/call", null, null, "Cookie", "device_id=d-7f3a; JSESSIONID=A1B2C3.node0"))
                .as("the services it calls get the visitor it made").isEqualTo("200 " + made.header());
    }

    @Test
    void anAppsOwnHeaderWorksAndTheWebSdksHeaderWins() throws Exception {
        start("observa.visitor.anonymous-from=header:X-Device-Id");
        Visitor made = Visitor.derived("ios-5c1e", null);
        get("/bookings/start", null, null, "X-Device-Id", "ios-5c1e");
        assertThat(events("booking.started", 1).get(0)).contains(made.anonymousId()).doesNotContain("sessionId");
        sent.clear();
        get("/bookings/start", HEADER, null, "X-Device-Id", "ios-5c1e");
        assertThat(events("booking.started", 1).get(0)).contains(VISITOR).doesNotContain(made.anonymousId());
        sent.clear();
        get("/bookings/start", null, null);
        assertThat(events("booking.started", 1)).as("no id at all: nothing to go to").isEmpty();
    }

    @Test
    void signInLinksTheVisitorTheServerMade() throws Exception {
        start("observa.visitor.anonymous-from=cookie:device_id");
        String header = get("/sign-in", null, null, "Cookie", "device_id=d-7f3a").substring(4);
        List<String> identify = events("$identify", 1);
        assertThat(identify).hasSize(1);
        assertThat(identify.get(0)).contains("\"userId\":\"42\"", "\"anonymousId\":\"" + header + "\"");
    }

    @Test
    void aWrongSourceStopsTheApplicationWithTheReason() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> start("observa.visitor.anonymous-from=device_id"))
                .hasStackTraceContaining("use cookie:<name> or header:<name>");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> start("observa.visitor.session-from=cookie:JSESSIONID"))
                .hasStackTraceContaining("needs observa.visitor.anonymous-from");
    }
}
