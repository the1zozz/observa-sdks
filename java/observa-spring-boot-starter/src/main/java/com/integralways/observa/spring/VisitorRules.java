package com.integralways.observa.spring;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.integralways.observa.sdk.Observa;
import com.integralways.observa.sdk.Visitor;

/**
 * What the visitor filters (javax and jakarta) and the forwarding interceptors share: which requests the header is
 * read on, how it is read, and which hosts it is forwarded to.
 */
final class VisitorRules {

    private final List<Pattern> ignored = new ArrayList<>();
    private final List<Pattern> hosts = new ArrayList<>();
    private final Source anonymousFrom;
    private final Source sessionFrom;
    private final boolean trace;

    VisitorRules(ObservaProperties.Visitor settings) {
        for (String path : settings.getIgnorePaths()) {
            if (path != null && !path.trim().isEmpty()) {
                ignored.add(glob(path.trim(), '/'));
            }
        }
        for (String host : settings.getForwardHosts()) {
            if (host != null && !host.trim().isEmpty()) {
                hosts.add(glob(host.trim().toLowerCase(Locale.ROOT), '.'));
            }
        }
        anonymousFrom = Source.of("observa.visitor.anonymous-from", settings.getAnonymousFrom());
        sessionFrom = Source.of("observa.visitor.session-from", settings.getSessionFrom());
        trace = settings.isTrace();
        if (anonymousFrom == null && sessionFrom != null) {
            throw new IllegalArgumentException("observa.visitor.session-from needs observa.visitor.anonymous-from: "
                    + "a visit belongs to a visitor");
        }
    }

    /** A request cookie or header the server reads the visitor or the visit from. */
    static final class Source {
        final boolean cookie;
        final String name;

        private Source(boolean cookie, String name) {
            this.cookie = cookie;
            this.name = name;
        }

        static Source of(String property, String value) {
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            String v = value.trim();
            int colon = v.indexOf(':');
            String kind = colon < 0 ? "" : v.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String name = colon < 0 ? "" : v.substring(colon + 1).trim();
            if (name.isEmpty() || !(kind.equals("cookie") || kind.equals("header"))) {
                throw new IllegalArgumentException(property + "=" + value
                        + " is not a source: use cookie:<name> or header:<name>, e.g. cookie:device_id");
            }
            return new Source(kind.equals("cookie"), name);
        }

        String in(Function<String, String> headers, Function<String, String> cookies) {
            return cookie ? cookies.apply(name) : headers.apply(name);
        }
    }

    /**
     * The visitor of a request to {@code path}: the header, else baggage, else (when configured) one the server makes
     * from its own cookie or header; null on an ignored path or without any.
     */
    Visitor read(String path, Function<String, String> header, Function<String, String> cookie) {
        if (path != null) {
            for (Pattern p : ignored) {
                if (p.matcher(path).matches()) {
                    return null;
                }
            }
        }
        Visitor visitor = Visitor.parse(header.apply(Visitor.HEADER));
        if (visitor == null) {
            visitor = Visitor.fromBaggage(header.apply("baggage"));
        }
        if (visitor == null && anonymousFrom != null) {
            visitor = Visitor.derived(anonymousFrom.in(header, cookie),
                    sessionFrom == null ? null : sessionFrom.in(header, cookie));
        }
        return visitor;
    }

    /** Whether each request gets a trace id that its events carry and its calls forward (observa.visitor.trace). */
    boolean traces() {
        return trace;
    }

    /** The trace id for an incoming request: its traceparent's, or a new one; null when tracing is off. */
    String traceOf(String traceparent) {
        if (!trace) {
            return null;
        }
        String id = Observa.traceIdOf(traceparent);
        return id != null ? id : Observa.newTraceId();
    }

    /** Whether a call to {@code host} gets the header: any host when none are listed. */
    boolean forwardsTo(String host) {
        if (hosts.isEmpty()) {
            return true;
        }
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (Pattern p : hosts) {
            if (p.matcher(h).matches()) {
                return true;
            }
        }
        return false;
    }

    /** {@code *} within one part (between separators), {@code **} across them. */
    static Pattern glob(String glob, char separator) {
        String sep = Pattern.quote(String.valueOf(separator));
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^").append(sep).append("]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
