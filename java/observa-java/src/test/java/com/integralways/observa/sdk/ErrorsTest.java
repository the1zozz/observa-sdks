package com.integralways.observa.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/** Errors as $error events (the SDK contract, §8). */
class ErrorsTest {

    static IllegalStateException thrownHere(String message) {
        return new IllegalStateException(message);
    }

    @Test
    void anErrorArrivesWithWhatTheContractAsksFor() {
        try (ObservaTesting.Capture capture = ObservaTesting.capture(b -> b.service("booking-api"))) {
            IllegalStateException e = new IllegalStateException("Card declined", new RuntimeException("gateway timeout"));
            capture.observa().error(ErrorReport.of(e).user("42").requestId("req-1").property("bookingId", "B-17"));

            ObservaTesting.CapturedEvent error = capture.single("$error");
            assertEquals("42", error.userId());
            assertEquals("java.lang.IllegalStateException", error.property("kind"));
            assertEquals("Card declined", error.property("message"));
            String stack = (String) error.property("stack");
            assertTrue(stack.contains("ErrorsTest") && stack.contains("Caused by: java.lang.RuntimeException: gateway timeout"));
            assertEquals(16, ((String) error.property("fingerprint")).length());
            assertEquals("ERROR", error.property("level"));
            assertEquals("booking-api", error.property("service"));
            assertEquals("req-1", error.property("requestId"));
            assertEquals("B-17", error.property("bookingId"));
        }
    }

    @Test
    void theSameFailureAtTheSamePlaceGroupsTogether() {
        String a = ErrorReport.of(thrownHere("first")).fingerprint();
        String b = ErrorReport.of(thrownHere("second, with other words")).fingerprint();
        String elsewhere = ErrorReport.of(new IllegalStateException("first")).fingerprint();
        String otherKind = ErrorReport.of(new IllegalArgumentException("first")).fingerprint();
        assertEquals(a, b, "the message does not change the group");
        assertNotEquals(a, elsewhere, "another place is another group");
        assertNotEquals(a, otherKind);
        // An error known only from its text groups by its stack's frames, without line numbers.
        String s1 = ErrorReport.of("x.Boom", "m", "x.Boom: m\n\tat a.B.c(B.java:10)\n\tat a.B.d(B.java:20)").fingerprint();
        String s2 = ErrorReport.of("x.Boom", "m", "x.Boom: m\n\tat a.B.c(B.java:11)\n\tat a.B.d(B.java:25)").fingerprint();
        assertEquals(s1, s2);
    }

    @Test
    void thePersonComesFromTheCallTheResolverOrTheService() {
        try (ObservaTesting.Capture capture = ObservaTesting.capture(b -> b.service("billing").userResolver(() -> "7"))) {
            capture.observa().error("42", new IllegalStateException("a"));
            capture.observa().error(new IllegalStateException("b"));
            List<ObservaTesting.CapturedEvent> errors = capture.events("$error");
            assertEquals("42", errors.get(0).userId());
            assertEquals("7", errors.get(1).userId(), "nobody named: the resolver's user");
        }
        try (ObservaTesting.Capture capture = ObservaTesting.capture(b -> b.service("billing").userResolver(() -> null))) {
            capture.observa().error(new IllegalStateException("c"));
            ObservaTesting.CapturedEvent error = capture.single("$error");
            assertNull(error.userId());
            assertEquals("service:billing", error.anonymousId(), "nobody at all: the service itself");
        }
        try (ObservaTesting.Capture capture = ObservaTesting.capture(b -> b.userResolver(() -> {
            throw new IllegalStateException("resolver bug");
        }))) {
            capture.observa().error(new IllegalStateException("d"));
            assertEquals("service:java", capture.single("$error").anonymousId(), "a failing resolver changes nothing");
        }
    }

    @Test
    void anErrorLoopCannotFloodAnything() {
        try (ObservaTesting.Capture capture = ObservaTesting.capture(b -> b.maxErrorsPerMinute(5))) {
            for (int i = 0; i < 50; i++) {
                capture.observa().error("42", new IllegalStateException("loop " + i));
            }
            assertEquals(5, capture.events("$error").size());
            assertEquals(45, capture.observa().stats().dropped());
        }
    }

    @Test
    void oneExceptionReportedTwiceOnAThreadIsSentOnce() {
        try (ObservaTesting.Capture capture = ObservaTesting.capture()) {
            IllegalStateException e = new IllegalStateException("once");
            capture.observa().error("42", e);   // e.g. logged by the application
            capture.observa().error("42", e);   // and seen again by the web filter
            capture.observa().error("42", new IllegalStateException("another"));
            assertEquals(2, capture.events("$error").size());
        }
    }

    @Test
    void theSdksOwnErrorsAreNeverSent() {
        try (ObservaTesting.Capture capture = ObservaTesting.capture()) {
            capture.observa().error(ErrorReport.of(new IllegalStateException("x")).user("1").logger("com.integralways.observa"));
            assertTrue(capture.events("$error").isEmpty());
        }
    }

    @Test
    void longMessagesAndStacksAreCut() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            big.append('x');
        }
        try (ObservaTesting.Capture capture = ObservaTesting.capture()) {
            capture.observa().error(ErrorReport.of("x.Big", big.toString(), big.toString()).user("1"));
            ObservaTesting.CapturedEvent error = capture.single("$error");
            assertEquals(ErrorReport.MAX_MESSAGE, ((String) error.property("message")).length());
            assertEquals(ErrorReport.MAX_STACK, ((String) error.property("stack")).length());
            assertEquals(0, capture.rejected(), "still under 32 KB");
        }
    }

    @Test
    void errorsDuringARequestCarryIt() {
        try (ObservaTesting.Capture capture = ObservaTesting.capture()) {
            Observa.setRequest(Props.of("method", "POST", "path", "/bookings"));
            try {
                capture.observa().error("42", new IllegalStateException("in a request"));
            } finally {
                Observa.clearRequest();
            }
            capture.observa().error("42", new IllegalStateException("after it"));
            List<ObservaTesting.CapturedEvent> errors = capture.events("$error");
            assertEquals("/bookings", errors.get(0).property("http.path"));
            assertNull(errors.get(1).property("http"));
        }
    }

    @Test
    void javaLoggingErrorsGoThroughTheHandler() {
        try (ObservaTesting.Capture capture = ObservaTesting.capture(b -> b.userResolver(() -> "42"))) {
            ObservaLogHandler handler = new ObservaLogHandler(capture.observa());
            LogRecord withException = new LogRecord(Level.SEVERE, "Payment {0} failed");
            withException.setParameters(new Object[] {"P-1"});
            withException.setThrown(new IllegalStateException("declined"));
            withException.setLoggerName("com.acme.Payments");
            handler.publish(withException);
            LogRecord textOnly = new LogRecord(Level.SEVERE, "Disk almost full");
            textOnly.setLoggerName("com.acme.Disk");
            handler.publish(textOnly);
            LogRecord info = new LogRecord(Level.INFO, "all fine");
            handler.publish(info);
            LogRecord ours = new LogRecord(Level.SEVERE, "the SDK's own");
            ours.setLoggerName("com.integralways.observa");
            handler.publish(ours);

            List<ObservaTesting.CapturedEvent> errors = capture.events("$error");
            assertEquals(2, errors.size());
            assertEquals("java.lang.IllegalStateException", errors.get(0).property("kind"));
            assertEquals("Payment P-1 failed", errors.get(0).property("logMessage"));
            assertEquals("com.acme.Payments", errors.get(0).property("logger"));
            assertEquals("42", errors.get(0).userId());
            assertEquals("log", errors.get(1).property("kind"));
            assertEquals("Disk almost full", errors.get(1).property("message"));
        }
    }

    @Test
    void theApplicationsObservaIsFoundByIntegrations() {
        Observa observa = Observa.builder().key("sk_test_abc").endpoint("http://observa.test")
                .transport((url, key, json) -> new Transport.Response(202, "{}", 0)).shutdownHook(false).build();
        assertSame(observa, Observa.current());
        observa.close();
        assertNull(Observa.current(), "a closed one is not used");
        Map<String, Object> none = Props.of();
        assertFalse(none.containsKey("x"));
        assertNotNull(ErrorReport.of((Throwable) null).kind());
    }
}
