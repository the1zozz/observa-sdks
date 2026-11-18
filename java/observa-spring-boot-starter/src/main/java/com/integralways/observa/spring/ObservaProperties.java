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
