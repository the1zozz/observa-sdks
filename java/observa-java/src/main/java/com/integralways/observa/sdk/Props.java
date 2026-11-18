package com.integralways.observa.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** A quick way to write an event's body, traits or properties: {@code Props.of("bookingId", "B-17", "nights", 3)}. */
public final class Props {

    private Props() {
    }

    /** Name, value, name, value…; keys in the order given. A last name without a value is left out (and logged). */
    public static Map<String, Object> of(Object... namesAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (namesAndValues == null) {
            return map;
        }
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            if (namesAndValues[i] != null) {
                map.put(namesAndValues[i].toString(), namesAndValues[i + 1]);
            }
        }
        if (namesAndValues.length % 2 != 0) {
            Logger.getLogger(Observa.LOGGER).warning("Props.of: \"" + namesAndValues[namesAndValues.length - 1]
                    + "\" has no value and was left out");
        }
        return map;
    }
}
