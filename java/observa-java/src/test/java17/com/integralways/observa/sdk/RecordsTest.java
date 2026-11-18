package com.integralways.observa.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Records (Java 16+) are sent by their components, in their order. Compiled and run on Java 17 and later only. */
class RecordsTest {

    record Guest(String name, String city) {
    }

    record Booking(String id, int nights, Guest guest, List<String> tags) {
    }

    @Test
    void aRecordIsSentAsItsComponents() {
        try (ObservaTesting.Capture capture = ObservaTesting.capture()) {
            capture.observa().track("42", "booking.confirmed",
                    new Booking("B-17", 3, new Guest("Lina", "Riyadh"), List.of("sea view")));
            ObservaTesting.CapturedEvent e = capture.single("booking.confirmed");
            assertEquals(List.of("id", "nights", "guest", "tags"), List.copyOf(e.properties().keySet()));
            assertEquals(3L, e.property("nights"));
            assertEquals("Riyadh", e.property("guest.city"));
            assertEquals(List.of("sea view"), e.property("tags"));
        }
    }
}
