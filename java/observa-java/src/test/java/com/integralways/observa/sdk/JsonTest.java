package com.integralways.observa.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The SDK's own small JSON: what applications put in events, and Observa's answers. */
class JsonTest {

    enum Plan { GOLD }

    @Test
    void writesWhatApplicationsPutInEvents() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "Line \"one\"\n\ttab \\ \u0001 عربي");
        m.put("int", 3);
        m.put("whole", 2.0);
        m.put("money", new BigDecimal("1200.50"));
        m.put("nan", Double.NaN);
        m.put("yes", true);
        m.put("none", null);
        m.put("when", Instant.parse("2026-11-17T09:00:00Z"));
        m.put("day", LocalDate.of(2026, 12, 1));
        m.put("id", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        m.put("plan", Plan.GOLD);
        m.put("maybe", Optional.of("x"));
        m.put("list", Arrays.asList(1, "a"));
        m.put("array", new int[] {1, 2});
        assertEquals("{\"text\":\"Line \\\"one\\\"\\n\\ttab \\\\ \\u0001 عربي\",\"int\":3,\"whole\":2,\"money\":1200.50,\"nan\":null,"
                + "\"yes\":true,\"none\":null,\"when\":\"2026-11-17T09:00:00Z\",\"day\":\"2026-12-01\","
                + "\"id\":\"00000000-0000-0000-0000-000000000001\",\"plan\":\"GOLD\",\"maybe\":\"x\",\"list\":[1,\"a\"],\"array\":[1,2]}",
                Json.write(m));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsObservasAnswersAndRefusesWhatIsNotJson() {
        Map<String, Object> answer = (Map<String, Object>) Json.read(
                " {\"accepted\": 2, \"rejected\": 1, \"errors\": [{\"index\": 1, \"reasons\": [{\"field\": \"type\", \"message\": \"must be \\u0022dotted\\u0022\"}]}], \"ratio\": 0.5, \"ok\": false, \"x\": null} ");
        assertEquals(2L, answer.get("accepted"));
        assertEquals(0.5, answer.get("ratio"));
        assertEquals(false, answer.get("ok"));
        assertEquals("must be \"dotted\"",
                ((Map<String, Object>) ((java.util.List<Object>) ((Map<String, Object>) ((java.util.List<Object>) answer.get("errors")).get(0))
                        .get("reasons")).get(0)).get("message"));
        assertNull(Json.read("<html>Bad gateway</html>"));
        assertNull(Json.read("{\"a\":"));
        assertNull(Json.read(""));
    }
}
