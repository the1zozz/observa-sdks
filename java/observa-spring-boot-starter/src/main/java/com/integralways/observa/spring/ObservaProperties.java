package com.integralways.observa.spring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code observa.*} settings. Only the key and the endpoint are needed; Spring also reads them from the environment
 * variables {@code OBSERVA_KEY} and {@code OBSERVA_ENDPOINT}.
 */
@ConfigurationProperties(prefix = "observa")
public class ObservaProperties {

    /** The source's secret key, sk_… (Data → Sources → New key → Your servers). Keep it out of your code. */
    private String key;

    /** Observa's address, e.g. https://observa.example.com. */
    private String endpoint;

    /** Off sends nothing, e.g. in tests; Observa can still be injected and called. */
    private boolean enabled = true;

    /** Logs each batch and why Observa refused an event, at INFO. For development only. */
    private boolean debug;

    /** How often the queue is sent; a full batch goes at once. */
    private Duration flushInterval = Duration.ofSeconds(1);

    /** Events per request, 1 to 500. */
    private int maxBatchSize = 100;

    /** Events held while Observa cannot be reached; beyond this the oldest are dropped. */
    private int maxQueueSize = 10_000;

    /** How long one request may take. */
    private Duration requestTimeout = Duration.ofSeconds(10);

    /** How long the SDK keeps sending when the application stops. */
    private Duration shutdownTimeout = Duration.ofSeconds(5);

    /** The application's name in its errors, e.g. booking-api. Default: spring.application.name. */
    private String service;

    /** Errors: ERROR logs, exceptions and failed requests, sent as $error events. */
    private final Errors errors = new Errors();

    /** The browser's visitor, sent by the web SDK in the Observa-Visitor header (the SDK contract, §9). */
    private final Visitor visitor = new Visitor();

    public static class Visitor {

        /** Read the Observa-Visitor header (or observa.visitor in baggage) of each request and forward it on calls. */
        private boolean enabled = true;

        /**
         * Paths where the header is ignored, because only outsiders call them, e.g. /webhooks/**. {@code *} matches
         * within one path segment, {@code **} any number of them.
         */
        private java.util.List<String> ignorePaths = new java.util.ArrayList<>();

        /**
         * Hosts that outgoing calls forward the header to: your own services, e.g. billing, *.internal.example.com.
         * Empty (the default): every host the application's RestTemplate, RestClient and WebClient beans call.
         */
        private java.util.List<String> forwardHosts = new java.util.ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public java.util.List<String> getIgnorePaths() {
            return ignorePaths;
        }

        public void setIgnorePaths(java.util.List<String> ignorePaths) {
            this.ignorePaths = ignorePaths == null ? new java.util.ArrayList<>() : ignorePaths;
        }

        public java.util.List<String> getForwardHosts() {
            return forwardHosts;
        }

        public void setForwardHosts(java.util.List<String> forwardHosts) {
            this.forwardHosts = forwardHosts == null ? new java.util.ArrayList<>() : forwardHosts;
        }
    }

    public Visitor getVisitor() {
        return visitor;
    }

    public static class Errors {

        /** Send ERROR logs and server errors of web requests to Observa. */
        private boolean enabled = true;

        /** At most this many errors are sent per minute, so an error loop cannot flood anything. */
        private int maxPerMinute = 60;

        /** The lowest log level sent: ERROR, or WARN to send warnings too. */
        private String level = "ERROR";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxPerMinute() {
            return maxPerMinute;
        }

        public void setMaxPerMinute(int maxPerMinute) {
            this.maxPerMinute = maxPerMinute;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public Errors getErrors() {
        return errors;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public void setFlushInterval(Duration flushInterval) {
        this.flushInterval = flushInterval;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public void setMaxQueueSize(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }
}
