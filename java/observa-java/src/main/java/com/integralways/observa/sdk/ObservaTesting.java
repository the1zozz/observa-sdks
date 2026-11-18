package com.integralways.observa.sdk;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * For your application's own tests: an {@link Observa} that keeps events in memory instead of sending them, so a test
 * can check what your code tracked. Events go through the same checks as in production, so a test also catches an
 * invalid type or a missing user id.
 * <pre>{@code
 * ObservaTesting.Capture capture = ObservaTesting.capture();
 * BookingService service = new BookingService(capture.observa());
 *
 * service.confirm(booking);
 *
 * ObservaTesting.CapturedEvent e = capture.single("booking.confirmed");
 * assertEquals("42", e.userId());
 * assertEquals("B-17", e.property("bookingId"));
 * }</pre>
 * With Spring, give your test configuration an {@code @Bean Observa observa() { return capture.observa(); }}.
 */
public final class ObservaTesting {

    private ObservaTesting() {
    }

    /** A new, empty capture. */
    public static Capture capture() {
        return new Capture();
    }

    /** What your code tracked, in order. */
    public static final class Capture implements AutoCloseable {

        private final List<CapturedEvent> events = new CopyOnWriteArrayList<>();
        private final Observa observa;

        private Capture() {
            Transport memory = (url, key, json) -> {
                Object batch = Json.read(new String(json, StandardCharsets.UTF_8));
                int n = 0;
                if (batch instanceof Map && ((Map<?, ?>) batch).get("events") instanceof List) {
                    for (Object e : (List<?>) ((Map<?, ?>) batch).get("events")) {
                        if (e instanceof Map) {
                            events.add(new CapturedEvent((Map<?, ?>) e));
                            n++;
                        }
                    }
                }
                return new Transport.Response(202, "{\"accepted\":" + n + ",\"rejected\":0,\"errors\":[]}", 0);
            };
            this.observa = Observa.builder()
                    .key("sk_test_capture")
                    .endpoint("http://observa.capture")
                    .transport(memory)
                    .flushInterval(Duration.ofMillis(10))
                    .shutdownHook(false)
                    .build();
        }

        /** The Observa to give your code under test. */
        public Observa observa() {
            return observa;
        }

        /** Every event tracked so far, oldest first. */
        public List<CapturedEvent> events() {
            observa.flush(Duration.ofSeconds(5));
            return Collections.unmodifiableList(new ArrayList<>(events));
        }

        /** The events of one type, oldest first. */
        public List<CapturedEvent> events(String type) {
            List<CapturedEvent> found = new ArrayList<>();
            for (CapturedEvent e : events()) {
                if (e.type().equals(type)) {
                    found.add(e);
                }
            }
            return found;
        }

        /** The one event of this type; fails the test with what was tracked if there is none, or more than one. */
        public CapturedEvent single(String type) {
            List<CapturedEvent> found = events(type);
            if (found.size() != 1) {
                throw new AssertionError("expected one \"" + type + "\" event but found " + found.size()
                        + "; tracked: " + events());
            }
            return found.get(0);
        }

        /**
         * Events your code tried to track that the checks refused (an invalid type, no person, over 32 KB), as in
         * production; the log says why.
         */
        public long rejected() {
            return observa.stats().rejected();
        }

        /** Forgets what was captured, e.g. between two steps of a test. */
        public void clear() {
            observa.flush(Duration.ofSeconds(5));
            events.clear();
        }

        @Override
        public void close() {
            observa.close();
        }
    }

    /** One event as Observa would receive it. Numbers come back as {@code Long} or {@code Double}, times as text. */
    public static final class CapturedEvent {
        private final Map<?, ?> event;

        CapturedEvent(Map<?, ?> event) {
            this.event = event;
        }

        public String id() {
            return text(event.get("id"));
        }

        public String type() {
            return text(event.get("type"));
        }

        public String userId() {
            return text(map(event.get("subject")).get("userId"));
        }

        public String anonymousId() {
            return text(map(event.get("subject")).get("anonymousId"));
        }

        public Instant occurredAt() {
            String at = text(event.get("occurredAt"));
            return at == null ? null : Instant.parse(at);
        }

        /** The event's data: what you passed to {@code track}, as it was written. */
        public Map<String, Object> properties() {
            return copy(event.get("body"));
        }

        /** One property; nested ones with dots: {@code property("guest.name")}. */
        public Object property(String name) {
            Object value = event.get("body");
            for (String part : name.split("\\.")) {
                value = map(value).get(part);
            }
            return value;
        }

        public Map<String, Object> attributes() {
            return copy(event.get("attributes"));
        }

        @Override
        public String toString() {
            return type() + "(user " + userId() + ", " + properties() + ")";
        }

        private static Map<?, ?> map(Object value) {
            return value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap();
        }

        private static Map<String, Object> copy(Object value) {
            Map<String, Object> out = new LinkedHashMap<>();
            map(value).forEach((k, v) -> out.put(String.valueOf(k), v));
            return Collections.unmodifiableMap(out);
        }

        private static String text(Object value) {
            return value == null ? null : value.toString();
        }
    }
}
