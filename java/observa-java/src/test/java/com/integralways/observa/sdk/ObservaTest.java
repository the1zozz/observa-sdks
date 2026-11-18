package com.integralways.observa.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The SDK against a fake Observa: the contract's checklist (CONTRACT.md §11). */
class ObservaTest {

    /** Records what was sent, answers what the test says. */
    static final class FakeTransport implements Transport {
        final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        final List<Long> times = new CopyOnWriteArrayList<>();
        final List<String> keys = new CopyOnWriteArrayList<>();
        final List<String> urls = new CopyOnWriteArrayList<>();
        volatile Function<Integer, Object> answer = n -> new Response(202, "{\"accepted\":1,\"rejected\":0,\"errors\":[]}", 0);

        @Override
        @SuppressWarnings("unchecked")
        public Response send(String url, String key, byte[] json) throws IOException {
            int n = requests.size();
            requests.add((Map<String, Object>) Json.read(new String(json, StandardCharsets.UTF_8)));
            times.add(System.currentTimeMillis());
            keys.add(key);
            urls.add(url);
            Object a = answer.apply(n);
            if (a instanceof IOException) {
                throw (IOException) a;
            }
            return (Response) a;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events(int request) {
            return (List<Map<String, Object>>) requests.get(request).get("events");
        }

        List<Object> allIds() {
            List<Object> ids = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                events(i).forEach(e -> ids.add(e.get("id")));
            }
            return ids;
        }
    }

    final FakeTransport fake = new FakeTransport();
    final List<Observa> made = new ArrayList<>();
    final List<LogRecord> logs = new CopyOnWriteArrayList<>();
    final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logs.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    {
        Logger.getLogger(Observa.LOGGER).addHandler(capture);
    }

    @AfterEach
    void stop() {
        made.forEach(Observa::close);
        Logger.getLogger(Observa.LOGGER).removeHandler(capture);
    }

    Observa.Builder builder() {
        return Observa.builder().key("sk_test_abc").endpoint("http://observa.test/").transport(fake).shutdownHook(false)
                .flushInterval(Duration.ofMillis(50)).retryInitialDelay(Duration.ofMillis(40)).retryMaxDelay(Duration.ofMillis(200));
    }

    Observa make(Observa.Builder b) {
        Observa o = b.build();
        made.add(o);
        return o;
    }

    static Transport.Response answer(int status, String body, long retryAfter) {
        return new Transport.Response(status, body, retryAfter);
    }

    // 1. A tracked event arrives with a UUID, the type, occurredAt, the person and context.library.
    @Test
    @SuppressWarnings("unchecked")
    void aTrackedEventArrivesWithEverythingObservaNeeds() {
        Observa observa = make(builder());
        observa.track("42", "booking.confirmed", Props.of("bookingId", "B-17", "nights", 3, "total", 450.5,
                "when", Instant.parse("2026-12-01T10:00:00Z"), "tags", Collections.singletonList("sea view")));
        assertTrue(observa.flush());

        assertEquals("http://observa.test/ingest/v1/events", fake.urls.get(0));
        assertEquals("sk_test_abc", fake.keys.get(0));
        assertNotNull(fake.requests.get(0).get("sentAt"));
        Map<String, Object> e = fake.events(0).get(0);
        assertEquals(36, e.get("id").toString().length());
        assertEquals("booking.confirmed", e.get("type"));
        assertNotNull(Instant.parse(e.get("occurredAt").toString()));
        assertEquals("42", ((Map<String, Object>) e.get("subject")).get("userId"));
        Map<String, Object> body = (Map<String, Object>) e.get("body");
        assertEquals("B-17", body.get("bookingId"));
        assertEquals(3L, body.get("nights"));
        assertEquals(450.5, body.get("total"));
        assertEquals("2026-12-01T10:00:00Z", body.get("when"));
        assertEquals(Collections.singletonList("sea view"), body.get("tags"));
        Map<String, Object> library = (Map<String, Object>) ((Map<String, Object>) e.get("context")).get("library");
        assertEquals("observa-java", library.get("name"));
        assertNotNull(library.get("version"));
        assertEquals(1, observa.stats().sent());
    }

    @Test
    @SuppressWarnings("unchecked")
    void identifySetUnsetAndConsentUseObservasOwnTypes() {
        Observa observa = make(builder());
        observa.identify("42", Props.of("email", "lina@example.com", "plan", "gold"));
        observa.setProfile("42", Props.of("plan", "platinum"));
        observa.unsetProfile("42", "trial");
        observa.consent("42", Consent.email(true).and("sms", false).text("Send me offers").form("signup"));
        observa.track(Event.of("ticket.opened").anonymous("a-1").session("s-1").platform("web").at(Instant.parse("2026-01-02T03:04:05.678Z"))
                .property("ticket", "T-9").attribute("channel", "support"));
        assertTrue(observa.flush());

        List<Map<String, Object>> events = fake.events(0);
        assertEquals("$identify", events.get(0).get("type"));
        assertEquals("lina@example.com", ((Map<String, Object>) events.get(0).get("body")).get("email"));
        assertEquals("$profile.set", events.get(1).get("type"));
        assertEquals("$profile.unset", events.get(2).get("type"));
        assertEquals(Collections.singletonList("trial"), ((Map<String, Object>) events.get(2).get("body")).get("properties"));
        Map<String, Object> consent = (Map<String, Object>) events.get(3).get("body");
        assertEquals("$consent.update", events.get(3).get("type"));
        assertEquals(true, ((Map<String, Object>) consent.get("channels")).get("email"));
        assertEquals(false, ((Map<String, Object>) consent.get("channels")).get("sms"));
        assertEquals("Send me offers", consent.get("text"));
        Map<String, Object> ticket = events.get(4);
        assertEquals("a-1", ((Map<String, Object>) ticket.get("subject")).get("anonymousId"));
        assertEquals("s-1", ticket.get("sessionId"));
        assertEquals("WEB", ticket.get("platform"));
        assertEquals("2026-01-02T03:04:05.678Z", ticket.get("occurredAt"));
        assertEquals("support", ((Map<String, Object>) ticket.get("attributes")).get("channel"));
    }

    // 2. Many events are batched up to maxBatchSize.
    @Test
    void manyEventsGoInBatchesOfTheBatchSize() {
        Observa observa = make(builder().maxBatchSize(10).flushInterval(Duration.ofSeconds(30)));
        for (int i = 0; i < 25; i++) {
            observa.track("u-" + i, "page.viewed");
        }
        assertTrue(observa.flush());
        assertEquals(3, fake.requests.size());
        assertEquals(10, fake.events(0).size());
        assertEquals(10, fake.events(1).size());
        assertEquals(5, fake.events(2).size());
        assertEquals(25, observa.stats().sent());
    }

    @Test
    void aFullBatchGoesAtOnceAndASmallOneAfterTheInterval() throws Exception {
        Observa observa = make(builder().maxBatchSize(5).flushInterval(Duration.ofMillis(300)));
        long start = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            observa.track("u", "page.viewed");
        }
        waitFor(() -> fake.requests.size() == 1, 2000);
        assertTrue(fake.times.get(0) - start < 250, "a full batch does not wait for the interval");
        observa.track("u", "page.viewed");
        waitFor(() -> fake.requests.size() == 2, 2000);
        assertTrue(fake.times.get(1) - fake.times.get(0) >= 200, "a partial batch waits for the interval");
    }

    // 3. 503, 429 with Retry-After, a timeout and a dropped connection are retried with the same ids.
    @Test
    void failuresAreRetriedWithTheSameIds() {
        AtomicInteger calls = new AtomicInteger();
        fake.answer = n -> {
            switch (calls.getAndIncrement()) {
                case 0:
                    return answer(503, "", 0);
                case 1:
                    return new IOException("connection reset");
                case 2:
                    return new java.net.SocketTimeoutException("read timed out");
                default:
                    return answer(202, "{\"accepted\":2,\"rejected\":0}", 0);
            }
        };
        Observa observa = make(builder());
        observa.track("1", "order.paid");
        observa.track("2", "order.paid");
        assertTrue(observa.flush(Duration.ofSeconds(5)));

        assertEquals(4, fake.requests.size());
        List<Object> first = new ArrayList<>();
        fake.events(0).forEach(e -> first.add(e.get("id")));
        for (int i = 1; i < 4; i++) {
            List<Object> ids = new ArrayList<>();
            fake.events(i).forEach(e -> ids.add(e.get("id")));
            assertEquals(first, ids, "attempt " + i + " resends the same events with the same ids");
        }
        Stats stats = observa.stats();
        assertEquals(2, stats.sent());
        assertEquals(3, stats.retried());
        assertEquals(0, stats.dropped());
    }

    @Test
    void aRetryWaitsLongerEachTimeAndAtLeastRetryAfter() {
        AtomicInteger calls = new AtomicInteger();
        fake.answer = n -> calls.getAndIncrement() == 0 ? answer(429, "", 1) : answer(202, "{\"accepted\":1}", 0);
        Observa observa = make(builder());
        observa.track("1", "order.paid");
        assertTrue(observa.flush(Duration.ofSeconds(5)));
        assertTrue(fake.times.get(1) - fake.times.get(0) >= 950, "Retry-After: 1 means at least a second, not the 40 ms backoff");

        FakeTransport timing = new FakeTransport();
        timing.answer = n -> n < 3 ? answer(500, "", 0) : answer(202, "{\"accepted\":1}", 0);
        Observa backoff = make(builder().transport(timing).retryInitialDelay(Duration.ofMillis(100)).retryMaxDelay(Duration.ofSeconds(1)));
        backoff.track("1", "order.paid");
        assertTrue(backoff.flush(Duration.ofSeconds(5)));
        long first = timing.times.get(1) - timing.times.get(0);
        long second = timing.times.get(2) - timing.times.get(1);
        long third = timing.times.get(3) - timing.times.get(2);
        assertTrue(first >= 100 && second >= 200 && third >= 400, "backoff doubles: " + first + ", " + second + ", " + third);
    }

    // 4. 400, 401, 403 are not retried; a 401 is logged once.
    @Test
    void refusedBatchesAreNotRetriedAndAKeyProblemIsLoggedOnce() {
        fake.answer = n -> answer(401, "{\"code\":\"OBS-ING-4011\",\"detail\":\"Unknown or missing key.\"}", 0);
        Observa observa = make(builder().maxBatchSize(1));
        observa.track("1", "a.b");
        observa.track("2", "a.b");
        observa.track("3", "a.b");
        observa.flush();
        assertEquals(3, fake.requests.size(), "each batch once, no retries");
        assertEquals(3, observa.stats().rejected());
        assertEquals(1, logs.stream().filter(r -> r.getMessage().contains("refused the key")).count());

        FakeTransport bad = new FakeTransport();
        bad.answer = n -> answer(400, "{\"detail\":\"Body is not a valid event batch.\"}", 0);
        Observa other = make(builder().transport(bad));
        other.track("1", "a.b");
        other.flush();
        assertEquals(1, bad.requests.size());
        assertEquals(1, other.stats().rejected());
    }

    // 5. 413 splits the batch.
    @Test
    void aBatchThatIsTooLargeIsSplitInHalves() {
        fake.answer = n -> {
            int size = fake.events(n).size();
            return size > 2 ? answer(413, "", 0) : answer(202, "{\"accepted\":" + size + "}", 0);
        };
        Observa observa = make(builder().maxBatchSize(8).flushInterval(Duration.ofSeconds(30)));
        for (int i = 0; i < 8; i++) {
            observa.track("u", "page.viewed", Props.of("n", i));
        }
        assertTrue(observa.flush());
        assertEquals(8, observa.stats().sent());
        assertEquals(8, new java.util.HashSet<>(fake.allIds()).size());
        // In order: the halves keep the events' order.
        List<Object> order = new ArrayList<>();
        for (int i = 0; i < fake.requests.size(); i++) {
            if (fake.events(i).size() <= 2) {
                fake.events(i).forEach(e -> order.add(((Map<?, ?>) e.get("body")).get("n")));
            }
        }
        assertEquals(java.util.Arrays.asList(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L), order);
    }

    // 6. A 202 with rejections counts them and does not resend them.
    @Test
    void eventsObservaRefusesAreCountedNotResent() {
        fake.answer = n -> answer(202, "{\"accepted\":2,\"rejected\":1,\"errors\":[{\"index\":1,\"reasons\":[{\"field\":\"body\",\"message\":\"too big\"}]}]}", 0);
        Observa observa = make(builder().debug(true));
        observa.track("1", "a.b");
        observa.track("2", "a.b");
        observa.track("3", "a.b");
        assertTrue(observa.flush());
        assertEquals(1, fake.requests.size());
        assertEquals(2, observa.stats().sent());
        assertEquals(1, observa.stats().rejected());
        assertTrue(logs.stream().anyMatch(r -> r.getMessage().contains("refused event #1")));
    }

    // 7. A full queue drops the oldest.
    @Test
    void aFullQueueDropsTheOldestEvents() {
        fake.answer = n -> answer(503, "", 0);
        Observa observa = make(builder().maxQueueSize(5).maxBatchSize(500).flushInterval(Duration.ofSeconds(30))
                .retryInitialDelay(Duration.ofSeconds(30)));
        for (int i = 0; i < 12; i++) {
            observa.track("u", "page.viewed", Props.of("n", i));
        }
        Stats stats = observa.stats();
        assertEquals(5, stats.queued());
        assertEquals(7, stats.dropped());
    }

    // 8. An invalid type, a missing person and an over-sized event are rejected without throwing.
    @Test
    void invalidEventsAreRejectedWithoutThrowing() {
        Observa observa = make(builder());
        observa.track("1", "Order Created");
        observa.track("1", null);
        observa.track(null, "order.created");
        observa.track("  ", "order.created");
        observa.track((Event) null);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            big.append('x');
        }
        observa.track("1", "note.added", Props.of("text", big.toString()));
        observa.consent("1", null);
        observa.track("1", "order.created");
        assertTrue(observa.flush());
        assertEquals(7, observa.stats().rejected());
        assertEquals(1, observa.stats().sent());
    }

    // 9. Nothing ever throws into the caller, also when the server is down and when the SDK is closed.
    @Test
    void nothingThrowsWhenObservaIsDownOrTheSdkIsClosed() {
        fake.answer = n -> {
            throw new IllegalStateException("a transport bug");
        };
        Observa observa = make(builder().shutdownTimeout(Duration.ofMillis(200)));
        observa.track("1", "a.b", Props.of("self", null));
        Map<String, Object> cyclic = new java.util.HashMap<>();
        cyclic.put("me", cyclic);
        observa.track("1", "a.b", cyclic);
        observa.flush(Duration.ofMillis(300));
        observa.close();
        observa.track("1", "a.b");
        observa.identify("1", null);
        observa.close();
        assertEquals(2, observa.stats().dropped(), "what could not be sent before closing is counted, nothing thrown");
        assertEquals(0, observa.stats().queued());
    }

    // 10. flush() sends everything and returns; close() flushes and then ignores further calls.
    @Test
    void closeSendsWhatIsQueuedThenIgnoresCalls() {
        Observa observa = make(builder().flushInterval(Duration.ofSeconds(30)));
        observa.track("1", "a.b");
        observa.track("2", "a.b");
        observa.close();
        assertEquals(2, observa.stats().sent());
        observa.track("3", "a.b");
        assertEquals(1, fake.requests.size());
        assertEquals(0, observa.stats().queued());
    }

    @Test
    void closeGivesUpAfterItsTimeoutAndCountsWhatWasLeft() {
        fake.answer = n -> answer(503, "", 0);
        Observa observa = make(builder().shutdownTimeout(Duration.ofMillis(200)).retryInitialDelay(Duration.ofSeconds(10)));
        observa.track("1", "a.b");
        observa.track("2", "a.b");
        long start = System.currentTimeMillis();
        observa.close();
        assertTrue(System.currentTimeMillis() - start < 2000);
        assertEquals(2, observa.stats().dropped());
    }

    // 11. The wrong kind of key is refused at start (without stopping the application).
    @Test
    void theWrongKeyTurnsTheSdkOffInsteadOfFailing() {
        for (String key : new String[] {null, "", "pk_dev_abc", "abc"}) {
            Observa observa = make(builder().key(key));
            assertFalse(observa.isEnabled(), "key " + key);
            observa.track("1", "a.b");
            assertTrue(observa.flush());
        }
        assertFalse(make(builder().endpoint("observa.test")).isEnabled());
        assertTrue(fake.requests.isEmpty());
        assertTrue(logs.stream().anyMatch(r -> r.getMessage().contains("publishable key")));
        Observa off = make(builder().enabled(false));
        off.track("1", "a.b");
        assertTrue(fake.requests.isEmpty());
        assertNotEquals(null, off.stats());
    }

    @Test
    void manyThreadsCanTrackAtOnce() throws Exception {
        Observa observa = make(builder().maxBatchSize(50));
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 250; i++) {
                    observa.track("u", "page.viewed");
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertTrue(observa.flush());
        assertEquals(2000, observa.stats().sent());
        assertEquals(2000, new java.util.HashSet<>(fake.allIds()).size(), "every event once, with its own id");
    }

    static void waitFor(java.util.function.BooleanSupplier condition, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(10);
        }
    }
}
