package com.integralways.observa.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A consent decision with its evidence, for {@link Observa#consent}: which channels the person agreed to (or withdrew
 * from), optionally for one topic, and the exact text they agreed to and where.
 * <pre>{@code Consent.email(true).and("sms", false).text("Send me offers by email").form("signup")}</pre>
 */
public final class Consent {

    private final Map<String, Boolean> channels = new LinkedHashMap<>();
    private String topic;
    private String text;
    private String form;

    private Consent() {
    }

    /** A decision for {@code channel}: email, sms, whatsapp or push. */
    public static Consent channel(String channel, boolean granted) {
        return new Consent().and(channel, granted);
    }

    public static Consent email(boolean granted) {
        return channel("email", granted);
    }

    public static Consent sms(boolean granted) {
        return channel("sms", granted);
    }

    public static Consent whatsapp(boolean granted) {
        return channel("whatsapp", granted);
    }

    /** Also a decision for {@code channel}. */
    public Consent and(String channel, boolean granted) {
        if (channel != null && !channel.trim().isEmpty()) {
            channels.put(channel.trim(), granted);
        }
        return this;
    }

    /** Only for this topic (e.g. offers), not the whole channel. */
    public Consent topic(String topic) {
        this.topic = topic;
        return this;
    }

    /** The words the person agreed to, kept as evidence. */
    public Consent text(String text) {
        this.text = text;
        return this;
    }

    /** Where they agreed, e.g. signup or settings. */
    public Consent form(String form) {
        this.form = form;
        return this;
    }

    boolean isEmpty() {
        return channels.isEmpty();
    }

    Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channels", new LinkedHashMap<>(channels));
        if (topic != null) {
            body.put("topic", topic);
        }
        if (text != null) {
            body.put("text", text);
        }
        if (form != null) {
            body.put("form", form);
        }
        return body;
    }
}
