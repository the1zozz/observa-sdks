package com.integralways.observa.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(c);
        }
        return b.toString();
    }
}
