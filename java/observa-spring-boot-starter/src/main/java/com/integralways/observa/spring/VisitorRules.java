package com.integralways.observa.spring;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.integralways.observa.sdk.Visitor;

/**
 * What the visitor filters (javax and jakarta) and the forwarding interceptors share: which requests the header is
 * read on, how it is read, and which hosts it is forwarded to.
 */
final class VisitorRules {

    private final List<Pattern> ignored = new ArrayList<>();
    private final List<Pattern> hosts = new ArrayList<>();

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
    }

    /** The visitor of a request to {@code path}: the header, else baggage; null on an ignored path or without one. */
    Visitor read(String path, Function<String, String> header) {
        if (path != null) {
            for (Pattern p : ignored) {
                if (p.matcher(path).matches()) {
                    return null;
                }
            }
        }
        Visitor visitor = Visitor.parse(header.apply(Visitor.HEADER));
        return visitor != null ? visitor : Visitor.fromBaggage(header.apply("baggage"));
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
