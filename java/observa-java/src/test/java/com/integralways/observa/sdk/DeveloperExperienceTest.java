package com.integralways.observa.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What makes the SDK easy to get right the first time. */
class DeveloperExperienceTest {

    final List<LogRecord> logs = new CopyOnWriteArrayList<>();
    final Handler handler = new Handler() {
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
    final ObservaTesting.Capture capture;

    {
        Logger.getLogger(Observa.LOGGER).addHandler(handler);
        capture = ObservaTesting.capture();
    }

    @AfterEach
    void stop() {
        capture.close();
        Logger.getLogger(Observa.LOGGER).removeHandler(handler);
    }

    long logged(Level level, String text) {
        return logs.stream().filter(r -> r.getLevel() == level && r.getMessage().contains(text)).count();
    }

    // ------------------------------------------------------------------ objects as event data

    public static class Guest {
        public String getName() {
            return "Lina";
        }
    }

    public static class Booking {
        public String getId() {
            return "B-17";
        }

        public int getNights() {
            return 3;
        }

        public BigDecimal getTotal() {
            return new BigDecimal("450.00");
        }

        public boolean isPaid() {
            return true;
        }

        public LocalDate getCheckIn() {
            return LocalDate.of(2026, 12, 1);
        }

        public Guest getGuest() {
            return new Guest();
        }

        public List<String> getTags() {
            return Arrays.asList("sea view", "late check-out");
        }

        public String getLazyNotes() {
            throw new IllegalStateException("not loaded");
        }

        public String cancel() {
            throw new AssertionError("only getters are read");
        }

        public static String getStatic() {
            throw new AssertionError("static methods are not read");
        }
    }

    /** Not public: its getters are still read. */
    private static final class Ticket {
        private final String id = "T-9";

        public String getId() {
            return id;
        }
    }

    @Test
    void yourOwnObjectsAreSentAsTheirFields() {
        Observa observa = capture.observa();
        observa.track("42", "booking.confirmed", new Booking());
        observa.track(Event.of("ticket.opened").user("42").data(new Ticket()));

        ObservaTesting.CapturedEvent booking = capture.single("booking.confirmed");
        assertEquals("B-17", booking.property("id"));
        assertEquals(3L, booking.property("nights"));
        assertEquals(450.0, booking.property("total"), "a BigDecimal keeps its decimals");
        assertEquals(true, booking.property("paid"));
        assertEquals("2026-12-01", booking.property("checkIn"));
        assertEquals("Lina", booking.property("guest.name"));
        assertEquals(Arrays.asList("sea view", "late check-out"), booking.property("tags"));
        assertFalse(booking.properties().containsKey("lazyNotes"), "a getter that throws is left out");
        assertFalse(booking.properties().containsKey("class"));
        assertEquals("T-9", capture.single("ticket.opened").property("id"));
    }

    @Test
    void mapsAndJdkValuesKeepWorkingAsBefore() {
        Observa observa = capture.observa();
        observa.track("42", "a.map", (Object) Props.of("k", "v"));
        observa.track("42", "a.text", (Object) "just text");
        observa.track("42", "a.nothing", (Object) null);
        assertEquals("v", capture.single("a.map").property("k"));
        assertEquals("just text", capture.single("a.text").property("value"));
        assertTrue(capture.single("a.nothing").properties().isEmpty());
    }

    // ------------------------------------------------------------------ mistakes say how to fix them

    @Test
    void aWrongTypeIsAWarningOnceWithTheFix() {
        Observa observa = capture.observa();
        observa.track("42", "Order Created");
        observa.track("42", "Order Created");
        observa.track("42", "orderShipped");

        assertEquals(1, logged(Level.WARNING, "e.g. \"order.created\""));
        assertEquals(1, logged(Level.WARNING, "is not an event type"), "shown once, whatever the type");
        assertEquals(3, capture.rejected());
        assertTrue(capture.events().isEmpty());

        assertEquals("order.created", Observa.suggest("Order Created"));
        assertEquals("order.created", Observa.suggest("orderCreated"));
        assertEquals("payment.failed", Observa.suggest("payment-failed"));
        assertNull(Observa.suggest("signup"), "one word: no guess");
    }

    @Test
    void aMissingPersonIsAWarningOnce() {
        capture.observa().track(null, "booking.confirmed");
        capture.observa().track("", "booking.confirmed");
        assertEquals(1, logged(Level.WARNING, "names no person"));
    }

    @Test
    void whatObservaRefusesIsAWarningOncePerReason() {
        ObservaTest.FakeTransport fake = new ObservaTest.FakeTransport();
        fake.answer = n -> new Transport.Response(202, "{\"accepted\":1,\"rejected\":1,\"errors\":[{\"index\":0,"
                + "\"reasons\":[{\"field\":\"body.email\",\"message\":\"not allowed\"}]}]}", 0);
        Observa observa = Observa.builder().key("sk_test_abc").endpoint("http://observa.test").transport(fake)
                .shutdownHook(false).build();
        try {
            observa.track("1", "lead.created");
            observa.track("2", "lead.created");
            assertTrue(observa.flush());
            observa.track("3", "lead.created");
            observa.track("4", "lead.created");
            assertTrue(observa.flush());
            assertEquals(1, logged(Level.WARNING, "refused event #0 (lead.created)"));
        } finally {
            observa.close();
        }
    }

    // ------------------------------------------------------------------ is it connected?

    @Test
    void theLogSaysWhereItSendsAndWhenItIsConnected() {
        ObservaTest.FakeTransport fake = new ObservaTest.FakeTransport();
        Observa observa = Observa.builder().key("sk_prod_0123456789abcdef").endpoint("https://observa.example.com/")
                .transport(fake).shutdownHook(false).build();
        try {
            assertEquals(1, logged(Level.INFO, "sending events to https://observa.example.com with the key sk_prod_…cdef"));
            assertEquals(0, logs.stream().filter(r -> r.getMessage().contains("0123456789")).count(), "never the whole key");
            observa.track("1", "a.b");
            assertTrue(observa.flush());
            observa.track("2", "a.b");
            assertTrue(observa.flush());
            assertEquals(1, logged(Level.INFO, "connected"));
        } finally {
            observa.close();
        }
        assertEquals("sk_test_…", Observa.masked("sk_test_abc"));
        assertEquals("sk_…", Observa.masked("sk_x"));
    }

    // ------------------------------------------------------------------ the test helper

    @Test
    void theCaptureShowsWhatWasTrackedAndFailsClearly() {
        Observa observa = capture.observa();
        observa.identify("42", Props.of("email", "lina@example.com"));
        observa.track("42", "booking.confirmed", Props.of("bookingId", "B-17"));
        observa.track(Event.of("page.viewed").anonymous("a-1").attribute("region", "riyadh"));

        assertEquals(3, capture.events().size());
        assertEquals("$identify", capture.events().get(0).type());
        assertEquals("lina@example.com", capture.single("$identify").property("email"));
        ObservaTesting.CapturedEvent page = capture.single("page.viewed");
        assertEquals("a-1", page.anonymousId());
        assertNull(page.userId());
        assertEquals("riyadh", page.attributes().get("region"));
        assertTrue(page.occurredAt() != null && page.id().length() == 36);

        AssertionError missing = assertThrows(AssertionError.class, () -> capture.single("booking.cancelled"));
        assertTrue(missing.getMessage().contains("booking.confirmed(user 42, {bookingId=B-17})"), missing.getMessage());

        capture.clear();
        assertTrue(capture.events().isEmpty());
        assertEquals(3, capture.observa().stats().sent(), "the capture counts as sent, like a real Observa");
    }
}
