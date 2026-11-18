package com.integralways.observa.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The real HTTP sender against a local server: headers, body, answers, Retry-After. */
class HttpTransportTest {

    HttpServer server;
    final List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final AtomicInteger calls = new AtomicInteger();

    void start(int... statuses) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ingest/v1/events", exchange -> {
            int call = calls.getAndIncrement();
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            headers.put(":method", exchange.getRequestMethod());
            seen.add(headers);
            try (InputStream in = exchange.getRequestBody()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                bodies.add(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
            int status = statuses[Math.min(call, statuses.length - 1)];
            byte[] answer = (status == 202 ? "{\"accepted\":1,\"rejected\":0,\"errors\":[]}" : "{\"detail\":\"busy\"}")
                    .getBytes(StandardCharsets.UTF_8);
            if (status == 503) {
                exchange.getResponseHeaders().add("Retry-After", "1");
            }
            exchange.sendResponseHeaders(status, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void sendsTheBatchWithTheKeyAndReadsTheAnswer() throws Exception {
        start(503, 202);
        HttpTransport transport = new HttpTransport(5000);
        Transport.Response busy = transport.send(endpoint() + "/ingest/v1/events", "sk_test_x", "{\"events\":[]}".getBytes(StandardCharsets.UTF_8));
        assertEquals(503, busy.status());
        assertEquals(1, busy.retryAfterSeconds());
        assertEquals("{\"detail\":\"busy\"}", busy.body());

        Transport.Response ok = transport.send(endpoint() + "/ingest/v1/events", "sk_test_x", "{\"events\":[]}".getBytes(StandardCharsets.UTF_8));
        assertEquals(202, ok.status());
        Map<String, String> h = seen.get(1);
        assertEquals("POST", h.get(":method"));
        assertEquals("Bearer sk_test_x", h.get("authorization"));
        assertTrue(h.get("content-type").startsWith("application/json"));
        assertTrue(h.get("user-agent").startsWith("observa-java/"));
        assertEquals(null, h.get("origin"), "a server never sends Origin");
    }

    @Test
    void theSdkSendsEndToEndOverHttp() throws Exception {
        start(202);
        Observa observa = Observa.builder().key("sk_test_x").endpoint(endpoint() + "/").shutdownHook(false).build();
        try {
            observa.track("42", "booking.confirmed", Props.of("bookingId", "B-17"));
            assertTrue(observa.flush(Duration.ofSeconds(5)));
            assertEquals(1, observa.stats().sent());
            assertTrue(bodies.get(0).contains("\"type\":\"booking.confirmed\""));
        } finally {
            observa.close();
        }
    }

    @Test
    void retryAfterInSecondsOnly() {
        assertEquals(30, HttpTransport.retryAfter(" 30 "));
        assertEquals(0, HttpTransport.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(0, HttpTransport.retryAfter(null));
        assertEquals(0, HttpTransport.retryAfter("-5"));
    }
}
