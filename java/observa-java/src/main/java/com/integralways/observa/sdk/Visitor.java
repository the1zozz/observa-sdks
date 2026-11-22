package com.integralways.observa.sdk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * The browser's visitor and session, as the web SDK sends them to your servers in the {@code Observa-Visitor} header
 * (the SDK contract, §9): {@code Observa-Visitor: <anonymousId>;<sessionId>}. Two random ids, nothing personal, and
 * nothing a server trusts about who the person is: a visitor is never used to set a user id, contact details or
 * consent.
 * <p>
 * A malformed header is not an error: {@link #parse} gives null and events are recorded exactly as without it.
 */
public final class Visitor {

    /** The header the web SDK adds to the application's own requests, and servers forward to the services they call. */
    public static final String HEADER = "Observa-Visitor";
    /** The W3C baggage entry that may carry the same value, for systems that already propagate OpenTelemetry baggage. */
    public static final String BAGGAGE_KEY = "observa.visitor";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final String anonymousId;
    private final String sessionId;

    private Visitor(String anonymousId, String sessionId) {
        this.anonymousId = anonymousId;
        this.sessionId = sessionId;
    }

    /** A visitor from its ids; null if the anonymous id is missing or either id is not well formed. */
    public static Visitor of(String anonymousId, String sessionId) {
        String a = anonymousId == null ? "" : anonymousId.trim();
        String s = sessionId == null ? "" : sessionId.trim();
        if (!ID.matcher(a).matches() || (!s.isEmpty() && !ID.matcher(s).matches())) {
            return null;
        }
        return new Visitor(a, s.isEmpty() ? null : s);
    }

    /**
     * The visitor in a header's value, {@code <anonymousId>;<sessionId>} (the session may be left out); null when the
     * value is missing or malformed: two ids at most, each 1 to 128 letters, digits, {@code -} or {@code _}.
     */
    public static Visitor parse(String header) {
        if (header == null || header.length() > 300) {
            return null;
        }
        String[] parts = header.split(";", -1);
        if (parts.length > 2) {
            return null;
        }
        return of(parts[0], parts.length == 2 ? parts[1] : null);
    }

    /**
     * The visitor in a W3C {@code baggage} header ({@code baggage: observa.visitor=<anonymousId>;<sessionId>}, the
     * {@code ;} raw or written {@code %3B}); null when it has none.
     */
    public static Visitor fromBaggage(String baggage) {
        if (baggage == null || baggage.length() > 8192) {
            return null;
        }
        for (String member : baggage.split(",")) {
            int eq = member.indexOf('=');
            if (eq > 0 && member.substring(0, eq).trim().equals(BAGGAGE_KEY)) {
                return parse(member.substring(eq + 1).trim().replace("%3B", ";").replace("%3b", ";"));
            }
        }
        return null;
    }

    /**
     * A visitor made by the server itself, for a visit that starts without the web SDK (a server-rendered site, an app
     * without an Observa SDK, a kiosk): from an id the server already has for the device or the visit, such as its own
     * cookie or an app's device id. The ids are one-way hashes of those values, so the same value always gives the same
     * visitor, any value works, and the value itself (a session cookie, say) never leaves the server.
     * <p>
     * Null when {@code anonymousSource} is missing; {@code sessionSource} may be null for a visitor without a visit.
     */
    public static Visitor derived(String anonymousSource, String sessionSource) {
        if (anonymousSource == null || anonymousSource.trim().isEmpty()) {
            return null;
        }
        String session = sessionSource == null || sessionSource.trim().isEmpty() ? null
                : "s" + hash("observa.session\n" + sessionSource.trim());
        return new Visitor("v" + hash("observa.visitor\n" + anonymousSource.trim()), session);
    }

    /** The first 32 hex digits of the value's SHA-256. */
    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xf, 16)).append(Character.forDigit(digest[i] & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** The browser's anonymous id. */
    public String anonymousId() {
        return anonymousId;
    }

    /** The visit's session id, or null when the header named none. */
    public String sessionId() {
        return sessionId;
    }

    /** The header's value, {@code <anonymousId>;<sessionId>}, to forward to another service. */
    public String header() {
        return sessionId == null ? anonymousId : anonymousId + ";" + sessionId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Visitor && ((Visitor) o).header().equals(header());
    }

    @Override
    public int hashCode() {
        return header().hashCode();
    }

    @Override
    public String toString() {
        return "Visitor(" + header() + ")";
    }
}
