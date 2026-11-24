package com.integralways.observa.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** One person across browser and server (the SDK contract, §9). */
class VisitorTest {

    static final String A = "3f2c9a4e-8b1d-4c7e-9f0a-2d6b5e8c1a47";
    static final String S = "9e1b7c20-4f3a-4d8e-b6c1-0a2f9d3e7b54";
    static final String HEADER = A + ";" + S;

    @AfterEach
    void clear() {
        Observa.clearVisitor();
        Observa.clearTrace();
    }

    @Test
    void aServerCanMakeTheVisitorFromItsOwnIds() {
        Visitor v = Visitor.derived("device 7/ä.x", "JSESSIONID=ABC.node0");
        assertTrue(v.anonymousId().matches("v[0-9a-f]{32}"), "any value works: " + v.anonymousId());
        assertTrue(v.sessionId().matches("s[0-9a-f]{32}"));
        assertEquals(v, Visitor.derived("device 7/ä.x", "JSESSIONID=ABC.node0"), "the same value, the same visitor");
        assertEquals(v, Visitor.parse(v.header()), "forwarded like any visitor");
        assertNotEquals(v.anonymousId(), Visitor.derived("device 8", null).anonymousId());
        Visitor same = Visitor.derived("x", "x");
        assertNotEquals(same.anonymousId().substring(1), same.sessionId().substring(1), "one value, two different ids");
        assertTrue(!v.header().contains("device") && !v.header().contains("ABC"), "the value itself never leaves");
        assertNull(Visitor.derived("d", null).sessionId());
        assertNull(Visitor.derived(" ", "s"), "no visitor, no visit");
        assertNull(Visitor.derived(null, null));
    }

    @Test
    void identifyWithAnAnonymousIdLinksItAndNothingElseDoes() {
        try (ObservaTesting.Capture capture = ObservaTesting.capture()) {
            Visitor v = Visitor.derived("device-7", "visit-1");
            Observa.setVisitor(v);
            capture.observa().track(null, "booking.started");
            capture.observa().identify("42", Props.of("plan", "gold"));
            capture.observa().identify("42", Observa.currentVisitor().anonymousId(), Props.of("plan", "gold"));
            List<ObservaTesting.CapturedEvent> identify = capture.events("$identify");
            assertNull(identify.get(0).anonymousId(), "the visitor is never taken implicitly");
            assertEquals(v.anonymousId(), identify.get(1).anonymousId(), "only when the code names it");
            assertEquals("42", identify.get(1).userId());
            assertNull(identify.get(0).sessionId());
            assertEquals(v.sessionId(), identify.get(1).sessionId(), "naming the visitor puts the sign-in in its visit");
            capture.observa().identify("43", "another-visitor", null);
            assertNull(capture.events("$identify").get(2).sessionId(), "another visitor: not this visit");
            assertEquals(v.anonymousId(), capture.single("booking.started").anonymousId());
        }
    }

    @Test
    void theHeaderIsReadOnlyWhenWellFormed() {
        assertEquals(A, Visitor.parse(HEADER).anonymousId());
        assertEquals(S, Visitor.parse(" " + A + " ; " + S + " ").sessionId());
        assertNull(Visitor.parse(A).sessionId(), "the session may be left out");
        assertEquals(A, Visitor.parse(A).header());
        assertNull(Visitor.parse(null));
        assertNull(Visitor.parse(""));
        assertNull(Visitor.parse(";" + S), "no visitor");
        assertNull(Visitor.parse(A + ";" + S + ";42"), "no third part: never a user id");
        assertNull(Visitor.parse("a b;c"));
        assertNull(Visitor.parse("<script>;x"));
        assertNull(Visitor.parse(repeat('x', 129)));
        assertEquals(repeat('x', 128), Visitor.parse(repeat('x', 128)).anonymousId());
        assertNull(Observa.setVisitor("not valid!"), "malformed: ignored");
        assertNull(Observa.currentVisitor());
    }

    @Test
    void baggageCanCarryIt() {
        assertEquals(Visitor.parse(HEADER), Visitor.fromBaggage("userTier=gold, observa.visitor=" + A + "%3B" + S + ",x=1"));
        assertEquals(Visitor.parse(HEADER), Visitor.fromBaggage("observa.visitor=" + HEADER));
        assertNull(Visitor.fromBaggage("userTier=gold"));
        assertNull(Visitor.fromBaggage(null));
    }

    @Test
    void eventsInTheWorkCarryTheVisitorAndTheVisit() {
        try (ObservaTesting.Capture capture = ObservaTesting.capture(b -> b.service("bookings"))) {
            Observa observa = capture.observa();
            Observa.setVisitor(HEADER);
            observa.track(null, "booking.started");
            observa.track("42", "booking.confirmed");
            observa.track(Event.of("booking.viewed").anonymous("other").session("s-other"));
            observa.error(new IllegalStateException("No room"));
            Observa.clearVisitor();
            observa.track(null, "booking.lost");
            observa.error(new IllegalStateException("At night"));

            List<ObservaTesting.CapturedEvent> events = capture.events();
            ObservaTesting.CapturedEvent started = events.get(0);
            assertNull(started.userId());
            assertEquals(A, started.anonymousId(), "nobody named: the visitor");
            assertEquals(S, started.sessionId());
            ObservaTesting.CapturedEvent confirmed = events.get(1);
            assertEquals("42", confirmed.userId(), "the code's user wins");
            assertEquals(S, confirmed.sessionId(), "in the visit");
            assertEquals("other", events.get(2).anonymousId(), "what the code names is kept");
            assertEquals("s-other", events.get(2).sessionId());
            assertEquals(A, events.get(3).anonymousId(), "an error goes to the visitor, not the service");
            assertEquals(S, events.get(3).sessionId());
            assertEquals("service:bookings", events.get(4).anonymousId(), "no visitor any more");
            assertNull(events.get(4).sessionId());
            assertEquals(1, capture.rejected(), "booking.lost names nobody");
        }
    }

    @Test
    void theSignedInUserComesBeforeTheVisitor() {
        try (ObservaTesting.Capture capture = ObservaTesting.capture(b -> b.userResolver(() -> "7"))) {
            Observa.setVisitor(HEADER);
            capture.observa().track(null, "booking.started");
            capture.observa().error(new IllegalStateException("x"));
            for (ObservaTesting.CapturedEvent e : capture.events()) {
                assertEquals("7", e.userId());
                assertEquals(S, e.sessionId());
            }
        }
    }

    @Test
    void profileEventsNeverCarryTheVisitor() {
        try (ObservaTesting.Capture capture = ObservaTesting.capture(b -> b.userResolver(() -> "7"))) {
            Observa observa = capture.observa();
            Observa.setVisitor(HEADER);
            observa.identify("42", Props.of("name", "Lina"));
            observa.setProfile("42", Props.of("plan", "gold"));
            observa.unsetProfile("42", "plan");
            observa.consent("42", Consent.email(true).text("Send me news"));
            observa.identify(null, Props.of("name", "nobody"));
            List<ObservaTesting.CapturedEvent> events = capture.events();
            assertEquals(4, events.size());
            for (ObservaTesting.CapturedEvent e : events) {
                assertEquals("42", e.userId());
                assertNull(e.anonymousId(), e.type() + " must not be linked to the header's visitor");
                assertNull(e.sessionId());
            }
            assertEquals(1, capture.rejected(), "neither the resolver nor the visitor names a person for identify");
        }
    }

    @Test
    void theVisitorTravelsWithTheWork() throws Exception {
        assertNull(Observa.visitorHeader());
        Observa.withVisitor(HEADER, () -> assertEquals(HEADER, Observa.visitorHeader()));
        assertNull(Observa.visitorHeader(), "restored afterwards");
        Observa.setVisitor("outer");
        assertEquals("inner", Observa.withVisitor("inner", () -> Observa.currentVisitor().anonymousId()));
        assertEquals("outer", Observa.visitorHeader());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            String[] seen = new String[2];
            executor.submit(() -> seen[0] = Observa.visitorHeader()).get(5, TimeUnit.SECONDS);
            executor.submit(Observa.carryVisitor(() -> seen[1] = Observa.visitorHeader())).get(5, TimeUnit.SECONDS);
            assertNull(seen[0], "another thread has its own");
            assertEquals("outer", seen[1], "carried");
            assertNull(executor.submit(Observa::visitorHeader).get(5, TimeUnit.SECONDS), "and cleaned up after");
        } finally {
            executor.shutdownNow();
        }
    }

    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void theTraceparentsIdIsReadOnlyWhenWellFormed() {
        assertEquals(TRACE, Observa.traceIdOf("00-" + TRACE + "-00f067aa0ba902b7-01"));
        assertEquals(TRACE, Observa.traceIdOf(" 01-" + TRACE + "-00f067aa0ba902b7-00-future "), "a later version, read the same way");
        assertNull(Observa.traceIdOf(null));
        assertNull(Observa.traceIdOf("00-" + repeat('0', 32) + "-00f067aa0ba902b7-01"), "all zeros is invalid");
        assertNull(Observa.traceIdOf("00-" + TRACE.toUpperCase() + "-00f067aa0ba902b7-01"), "lowercase only");
        assertNull(Observa.traceIdOf("ff-" + TRACE + "-00f067aa0ba902b7-01"), "version ff is invalid");
        assertNull(Observa.traceIdOf("00-" + TRACE + "-01"));
        String fresh = Observa.newTraceId();
        assertTrue(fresh.matches("[0-9a-f]{32}"), fresh);
        assertNotEquals(fresh, Observa.newTraceId());
    }

    @Test
    void eventsAndErrorsOfTheRequestCarryItsTraceAndTheService() throws Exception {
        try (ObservaTesting.Capture capture = ObservaTesting.capture(b -> b.service("bookings"))) {
            Observa observa = capture.observa();
            assertNull(Observa.traceparentHeader());
            Observa.setTraceId(TRACE);
            assertTrue(Observa.traceparentHeader().matches("00-" + TRACE + "-[0-9a-f]{16}-01"), Observa.traceparentHeader());
            observa.track("42", "booking.confirmed");
            observa.error(new IllegalStateException("No room"));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(Observa.carryVisitor(() -> observa.track("42", "booking.mailed"))).get(5, TimeUnit.SECONDS);
                assertNull(executor.submit(Observa::currentTraceId).get(5, TimeUnit.SECONDS), "cleaned up after");
            } finally {
                executor.shutdownNow();
            }
            Observa.setTraceId("not a trace id");
            assertNull(Observa.currentTraceId(), "a malformed id clears it");
            observa.track("42", "booking.viewed");

            assertEquals(TRACE, capture.single("booking.confirmed").traceId());
            assertEquals(TRACE, capture.single("$error").traceId(), "an error with nobody to put it on still has its request");
            assertEquals("service:bookings", capture.single("$error").anonymousId());
            assertEquals(TRACE, capture.single("booking.mailed").traceId(), "carried to the executor");
            assertNull(capture.single("booking.viewed").traceId());
            assertEquals("bookings", capture.single("booking.viewed").service());
        }
    }

    static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(c);
        }
        return b.toString();
    }
}
