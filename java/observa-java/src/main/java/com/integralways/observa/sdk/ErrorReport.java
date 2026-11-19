package com.integralways.observa.sdk;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One error for {@link Observa#error(ErrorReport)}, sent as an {@code $error} event (the SDK contract, §8):
 * <pre>{@code
 * observa.error(ErrorReport.of(e).user("42").handled(true).property("bookingId", "B-17"));
 * }</pre>
 * The SDK's logging integrations build these for you from ERROR logs and exceptions; call it yourself for an error you
 * catch and handle but still want to see in Observa.
 */
public final class ErrorReport {

    static final int MAX_MESSAGE = 1000;
    static final int MAX_STACK = 16 * 1024;

    final String kind;
    final String message;
    final String stack;
    final String fingerprint;
    final Throwable throwable;
    String userId;
    String anonymousId;
    String sessionId;
    String logger;
    String level = "ERROR";
    String requestId;
    Boolean handled;
    final Map<String, Object> properties = new LinkedHashMap<>();

    private ErrorReport(String kind, String message, String stack, String fingerprint, Throwable throwable) {
        this.kind = kind;
        this.message = message;
        this.stack = stack;
        this.fingerprint = fingerprint;
        this.throwable = throwable;
    }

    /** An exception: its class, message and stack trace (with its causes). */
    public static ErrorReport of(Throwable error) {
        if (error == null) {
            return of("unknown", null, null);
        }
        StringWriter out = new StringWriter();
        try {
            error.printStackTrace(new PrintWriter(out));
        } catch (RuntimeException e) {
            out.write(error.getClass().getName());
        }
        return new ErrorReport(error.getClass().getName(), truncate(error.getMessage(), MAX_MESSAGE),
                truncate(out.toString(), MAX_STACK), fingerprint(error.getClass().getName(), frames(error)), error);
    }

    /**
     * An error known only by its parts, e.g. from a log that carries no exception object: {@code kind} is the error's
     * type (an exception class, or "log" for an ERROR line without one).
     */
    public static ErrorReport of(String kind, String message, String stack) {
        String k = kind == null || kind.trim().isEmpty() ? "unknown" : kind.trim();
        String m = truncate(message, MAX_MESSAGE);
        String s = truncate(stack, MAX_STACK);
        // With no stack, the message is what tells two such errors apart.
        return new ErrorReport(k, m, s, fingerprint(k, s != null ? firstLines(s, 6) : m), null);
    }

    /** The person it happened to, by your user id. */
    public ErrorReport user(String userId) {
        this.userId = userId;
        return this;
    }

    /** The visitor, when the browser passed your server its anonymous id. */
    public ErrorReport anonymous(String anonymousId) {
        this.anonymousId = anonymousId;
        return this;
    }

    /** The visit, when the browser passed it to your server. */
    public ErrorReport session(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    /** The logger, or the class, that reported it. */
    public ErrorReport logger(String logger) {
        this.logger = logger;
        return this;
    }

    /** ERROR (the default), or FATAL, WARN… for logs captured at other levels. */
    public ErrorReport level(String level) {
        if (level != null && !level.trim().isEmpty()) {
            this.level = level.trim().toUpperCase(java.util.Locale.ROOT);
        }
        return this;
    }

    /** Your request or trace id, to find the request in your own logs. */
    public ErrorReport requestId(String requestId) {
        this.requestId = requestId;
        return this;
    }

    /** True when your code caught it and carried on; false when it went unhandled. */
    public ErrorReport handled(boolean handled) {
        this.handled = handled;
        return this;
    }

    /** More about what was going on, e.g. the booking id or the HTTP request. */
    public ErrorReport property(String name, Object value) {
        if (name != null) {
            properties.put(name, value);
        }
        return this;
    }

    /** The error's type, e.g. java.lang.IllegalStateException. */
    public String kind() {
        return kind;
    }

    /** The same for the same failure at the same place: what Observa groups errors by. */
    public String fingerprint() {
        return fingerprint;
    }

    Map<String, Object> body(String service) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        if (message != null) {
            body.put("message", message);
        }
        if (stack != null) {
            body.put("stack", stack);
        }
        body.put("fingerprint", fingerprint);
        if (logger != null) {
            body.put("logger", logger);
        }
        body.put("level", level);
        if (service != null) {
            body.put("service", service);
        }
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        if (handled != null) {
            body.put("handled", handled);
        }
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            body.putIfAbsent(e.getKey(), e.getValue());
        }
        return body;
    }

    // ------------------------------------------------------------------ helpers

    /** The class and method of the top frames, without line numbers: they change with every edit. */
    private static String frames(Throwable error) {
        StringBuilder out = new StringBuilder();
        StackTraceElement[] stack = error.getStackTrace();
        for (int i = 0; stack != null && i < Math.min(5, stack.length); i++) {
            out.append(stack[i].getClassName()).append('.').append(stack[i].getMethodName()).append('\n');
        }
        return out.toString();
    }

    private static String firstLines(String stack, int lines) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String line : stack.split("\n")) {
            String t = line.trim();
            if (t.startsWith("at ")) {
                // Without the "(File.java:42)" part: line numbers change with every edit.
                int paren = t.indexOf('(');
                out.append(paren > 0 ? t.substring(0, paren) : t).append('\n');
                if (++n >= lines) {
                    break;
                }
            }
        }
        return out.toString();
    }

    static String fingerprint(String kind, String where) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest((kind + "\n" + (where == null ? "" : where)).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString((kind + where).hashCode());
        }
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        int end = max - 1;
        // Never cut a surrogate pair in half.
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end) + "…";
    }
}
