package com.integralways.observa.sdk;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An event with every detail, for {@link Observa#track(Event)}:
 * <pre>{@code Event.of("booking.confirmed").user("42").property("bookingId", "B-17").at(confirmedAt)}</pre>
 * For most calls {@link Observa#track(String, String, Map)} is shorter.
 */
public final class Event {

    final String type;
    String userId;
    String anonymousId;
    String sessionId;
    Instant occurredAt;
    String platform;
    final Map<String, Object> body = new LinkedHashMap<>();
    final Map<String, Object> attributes = new LinkedHashMap<>();

    private Event(String type) {
        this.type = type;
    }

    /** An event of {@code type}: dotted lowercase, e.g. booking.confirmed. */
    public static Event of(String type) {
        return new Event(type);
    }

    /** The person, by your own user id (the same one your apps pass to identify). */
    public Event user(String userId) {
        this.userId = userId;
        return this;
    }

    /** The visitor's anonymous id, when the browser passed it to your server and there is no user id yet. */
    public Event anonymous(String anonymousId) {
        this.anonymousId = anonymousId;
        return this;
    }

    /** The visit, when the browser passed it to your server. */
    public Event session(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    /** When it happened; now if not given. */
    public Event at(Instant occurredAt) {
        this.occurredAt = occurredAt;
        return this;
    }

    /** WEB, IOS, ANDROID or SERVER; SERVER if not given. */
    public Event platform(String platform) {
        this.platform = platform;
        return this;
    }

    public Event property(String name, Object value) {
        if (name != null) {
            body.put(name, value);
        }
        return this;
    }

    public Event properties(Map<String, ?> properties) {
        if (properties != null) {
            properties.forEach(this::property);
        }
        return this;
    }

    /**
     * The event's data from one of your own objects: a record's components, or a class's getters, become its properties
     * (nested objects too). A {@code Map} works as {@link #properties}.
     */
    @SuppressWarnings("unchecked")
    public Event data(Object data) {
        if (data instanceof Map) {
            return properties((Map<String, ?>) data);
        }
        Map<String, Object> fields = Json.fields(data);
        if (fields != null) {
            return properties(fields);
        }
        return data == null ? this : property("value", data);
    }

    /** A small tag to filter by (channel, region…), kept apart from the business data. */
    public Event attribute(String name, Object value) {
        if (name != null) {
            attributes.put(name, value);
        }
        return this;
    }
}
