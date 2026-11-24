package com.integralways.observa.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.lang.ref.WeakReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Sends events from a Java server to Observa, following the SDK contract ({@code CONTRACT.md}).
 * <pre>{@code
 * Observa observa = Observa.builder().key(System.getenv("OBSERVA_KEY")).endpoint("https://observa.example.com").build();
 * observa.identify("42", Props.of("email", "lina@example.com", "plan", "gold"));
 * observa.track("42", "booking.confirmed", Props.of("bookingId", "B-17", "nights", 3));
 * }</pre>
 * Calls only queue the event and return: a background thread sends batches, retries what Observa could not take yet
 * with the same event ids (so nothing is stored twice), and flushes when the JVM stops. Nothing here ever throws into
 * your code or waits on the network, except {@link #flush} and {@link #close}, which take a timeout. Thread-safe: make
 * one per application and share it.
 */
public final class Observa implements AutoCloseable {

    /** The SDK's version, sent with every event. */
    public static final String VERSION = version();
    static final String LOGGER = "com.integralways.observa";

    static final int MAX_BATCH = 500;
    static final int MAX_REQUEST_BYTES = 512 * 1024;
    static final int MAX_EVENT_BYTES = 32 * 1024;
    static final Pattern TYPE = Pattern.compile("^(\\$[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*|[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+)$");
    private static final String LIBRARY = "\"library\":{\"name\":\"observa-java\",\"version\":\"" + VERSION + "\"}";

    private static final Logger log = Logger.getLogger(LOGGER);

    // ------------------------------------------------------------------ configuration

    /** Builds an {@link Observa}. Only the key and the endpoint are needed; both fall back to the environment. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String key = System.getenv("OBSERVA_KEY");
        private String endpoint = System.getenv("OBSERVA_ENDPOINT");
        private boolean enabled = true;
        private boolean debug;
        private Duration flushInterval = Duration.ofSeconds(1);
        private int maxBatchSize = 100;
        private int maxQueueSize = 10_000;
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration shutdownTimeout = Duration.ofSeconds(5);
        private Duration retryInitialDelay = Duration.ofSeconds(1);
        private Duration retryMaxDelay = Duration.ofSeconds(60);
        private boolean shutdownHook = true;
        private Transport transport;
        private Clock clock = Clock.systemUTC();
        private String service = System.getenv("OBSERVA_SERVICE");
        private int maxErrorsPerMinute = 60;
        private Supplier<String> userResolver;

        private Builder() {
        }

        /** The source's secret key, {@code sk_…} (Data → Sources → New key → Your servers). Default: {@code OBSERVA_KEY}. */
        public Builder key(String key) {
            this.key = key;
            return this;
        }

        /** Observa's address, e.g. {@code https://observa.example.com}. Default: {@code OBSERVA_ENDPOINT}. */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** Off sends nothing: for tests and local runs. On by default. */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** Logs each batch and why Observa refused an event, at INFO. For development only. */
        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        /** How often the queue is sent (default 1 s); a full batch goes at once. */
        public Builder flushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
            return this;
        }

        /** Events per request, 1 to 500 (default 100). */
        public Builder maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        /** Events held while Observa cannot be reached (default 10 000); beyond that the oldest are dropped. */
        public Builder maxQueueSize(int maxQueueSize) {
            this.maxQueueSize = maxQueueSize;
            return this;
        }

        /** How long one request may take (default 10 s). */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /** How long {@link Observa#close} keeps sending before giving up (default 5 s). */
        public Builder shutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        /** The first wait before a retry (default 1 s); it doubles up to {@link #retryMaxDelay}. */
        public Builder retryInitialDelay(Duration retryInitialDelay) {
            this.retryInitialDelay = retryInitialDelay;
            return this;
        }

        /** The longest wait between retries (default 60 s). */
        public Builder retryMaxDelay(Duration retryMaxDelay) {
            this.retryMaxDelay = retryMaxDelay;
            return this;
        }

        /** Flush when the JVM stops (default on). Frameworks that close the SDK themselves turn it off. */
        public Builder shutdownHook(boolean shutdownHook) {
            this.shutdownHook = shutdownHook;
            return this;
        }

        /** Replaces how batches are sent, e.g. through your own proxy, or in tests. */
        public Builder transport(Transport transport) {
            this.transport = transport;
            return this;
        }

        /** Replaces the clock that stamps events, for tests. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** Your application's name in errors, e.g. booking-api. Default: {@code OBSERVA_SERVICE}. */
        public Builder service(String service) {
            this.service = service;
            return this;
        }

        /** At most this many errors are sent per minute (default 60), so an error loop cannot flood anything. */
        public Builder maxErrorsPerMinute(int maxErrorsPerMinute) {
            this.maxErrorsPerMinute = maxErrorsPerMinute;
            return this;
        }

        /**
         * Who the person is when an event or error names none: called on the thread that records it, e.g. to read the
         * signed-in user of the current request. The Spring Boot starter sets it for you.
         */
        public Builder userResolver(Supplier<String> userResolver) {
            this.userResolver = userResolver;
            return this;
        }

        /**
         * The SDK. A missing or wrong key never stops your application: the SDK logs why and sends nothing.
         */
        public Observa build() {
            return new Observa(this);
        }
    }

    // ------------------------------------------------------------------ state

    private final boolean active;
    private final String key;
    private final String url;
    private final boolean debug;
    private final long flushIntervalMillis;
    private final int maxBatchSize;
    private final int maxQueueSize;
    private final long shutdownTimeoutMillis;
    private final long retryInitialMillis;
    private final long retryMaxMillis;
    private final Transport transport;
    private final Clock clock;
    private final Thread hook;
    private final String service;
    private final int maxErrorsPerMinute;
    private final Supplier<String> userResolver;
    private long errorWindowStart;
    private int errorsInWindow;
    private boolean errorLimitLogged;
    /** The last exception reported on each thread: a log and the web filter often report the same one. */
    private final ThreadLocal<WeakReference<Throwable>> lastError = new ThreadLocal<>();

    /** What a web integration knows about the request being handled on this thread: added to its errors. */
    private static final ThreadLocal<Map<String, Object>> REQUEST = new ThreadLocal<>();

    /** The browser's visitor and session for the work on this thread (the SDK contract, §9). */
    private static final ThreadLocal<Visitor> VISITOR = new ThreadLocal<>();

    /** The W3C header that carries the request's trace id from service to service (contract §9, rules 8 and 9). */
    public static final String TRACEPARENT = "traceparent";

    /** The trace id of the request this thread is handling: shared by every service the request goes through (§9, rules 8 and 9). */
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    /** The application's Observa, for integrations made by other frameworks (log appenders): the latest one built. */
    private static volatile Observa current;

    private final Object lock = new Object();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    /** The batch being sent or waiting for its retry, oldest first; null when none. */
    private List<String> pending;
    private boolean sending;
    private boolean flushRequested;
    private long retryAt;
    private int attempt;
    private boolean closed;
    private boolean stopping;
    private boolean authLogged;
    private boolean connected;
    /** The kinds of mistake already logged as a warning: each is shown once, then at debug level. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Thread worker;

    private long sent;
    private long rejected;
    private long dropped;
    private long retried;

    private Observa(Builder b) {
        this.key = b.key == null ? null : b.key.trim();
        this.debug = b.debug;
        this.flushIntervalMillis = Math.max(1, millis(b.flushInterval, 1000));
        this.maxBatchSize = Math.max(1, Math.min(MAX_BATCH, b.maxBatchSize));
        this.maxQueueSize = Math.max(1, b.maxQueueSize);
        this.shutdownTimeoutMillis = Math.max(0, millis(b.shutdownTimeout, 5000));
        this.retryInitialMillis = Math.max(1, millis(b.retryInitialDelay, 1000));
        this.retryMaxMillis = Math.max(retryInitialMillis, millis(b.retryMaxDelay, 60_000));
        this.clock = b.clock == null ? Clock.systemUTC() : b.clock;
        this.service = b.service == null || b.service.trim().isEmpty() ? null : b.service.trim();
        this.maxErrorsPerMinute = Math.max(0, b.maxErrorsPerMinute);
        this.userResolver = b.userResolver;
        this.transport = b.transport != null ? b.transport : new HttpTransport((int) Math.max(1000, millis(b.requestTimeout, 10_000)));
        String endpoint = b.endpoint == null ? "" : b.endpoint.trim();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        this.url = endpoint + "/ingest/v1/events";

        String problem = !b.enabled ? null
                : key == null || key.isEmpty() ? "no key: set OBSERVA_KEY or Observa.builder().key(\"sk_…\")"
                : key.startsWith("pk_") ? "a publishable key (pk_) is for browsers and apps; use the source's secret key (sk_…)"
                : !key.startsWith("sk_") ? "the key must be a secret key, starting with sk_"
                : endpoint.isEmpty() ? "no endpoint: set OBSERVA_ENDPOINT or Observa.builder().endpoint(\"https://…\")"
                : !(endpoint.startsWith("https://") || endpoint.startsWith("http://")) ? "the endpoint must start with https:// or http://"
                : null;
        if (problem != null) {
            log.severe("Observa is off, nothing will be sent: " + problem);
        }
        this.active = b.enabled && problem == null;
        if (active) {
            log.info("Observa " + VERSION + ": sending events to " + endpoint + " with the key " + masked(key)
                    + (debug ? " (debug on)" : ""));
        } else if (!b.enabled) {
            log.info("Observa is off (enabled false): nothing will be sent");
        }
        if (active) {
            this.worker = new Thread(this::run, "observa-sender");
            this.worker.setDaemon(true);
            this.worker.start();
            this.hook = b.shutdownHook ? new Thread(this::close, "observa-shutdown") : null;
            if (hook != null) {
                try {
                    Runtime.getRuntime().addShutdownHook(hook);
                } catch (IllegalStateException | SecurityException e) {
                    // The JVM is already stopping, or hooks are not allowed: close() still flushes when called.
                }
            }
        } else {
            this.worker = null;
            this.hook = null;
        }
        current = this;
    }

    /**
     * The application's Observa: the one built last and not closed, or null. Logging integrations, which the logging
     * framework creates, use it to send errors.
     */
    public static Observa current() {
        Observa o = current;
        return o == null || o.isClosed() ? null : o;
    }

    // ------------------------------------------------------------------ the API

    /**
     * Records that the person {@code userId} did {@code type}, e.g. booking.confirmed. A null {@code userId} means the
     * person the user resolver gives, or else the browser visitor of the current request ({@link #setVisitor}).
     */
    public void track(String userId, String type) {
        track(Event.of(type).user(userId));
    }

    /** Records that the person {@code userId} did {@code type}, with its data, e.g. {@code Props.of("bookingId", "B-17")}. */
    public void track(String userId, String type, Map<String, ?> body) {
        track(Event.of(type).user(userId).properties(body));
    }

    /**
     * Records that the person {@code userId} did {@code type}, with one of your own objects as its data: a record's
     * components or a class's getters become the event's properties, e.g. {@code track("42", "booking.confirmed", booking)}.
     */
    public void track(String userId, String type, Object data) {
        try {
            track(Event.of(type).user(userId).data(data));
        } catch (RuntimeException e) {
            log.log(Level.FINE, "Observa: event not recorded", e);
        }
    }

    /** Records an event with every detail: another time, an anonymous id, attributes. */
    public void track(Event event) {
        try {
            if (event == null) {
                reject("empty", "an empty event (null)");
                return;
            }
            record(event);
        } catch (RuntimeException e) {
            // Never into the caller, whatever happened.
            log.log(Level.FINE, "Observa: event not recorded", e);
        }
    }

    /**
     * Tells Observa who the person {@code userId} is: traits such as email, phone, name, plan. Your server is trusted, so
     * email and phone are kept (encrypted) on the person.
     */
    public void identify(String userId, Map<String, ?> traits) {
        track(Event.of("$identify").user(userId).properties(traits));
    }

    /**
     * Tells Observa that the anonymous visitor {@code anonymousId} is the user {@code userId}, so the visitor's earlier
     * events, errors and visits count for the person, and records the person's traits. Use it at sign-in when the visit
     * did not start in the web SDK (which links on its own): with {@code Observa.currentVisitor().anonymousId()} for a
     * visitor the server made ({@link Visitor#derived}), or an app's own anonymous id. A visitor is never linked
     * without this call.
     */
    public void identify(String userId, String anonymousId, Map<String, ?> traits) {
        track(Event.of("$identify").user(userId).anonymous(anonymousId).properties(traits));
    }

    /** Sets properties of the person's profile, e.g. {@code Props.of("plan", "gold")}. */
    public void setProfile(String userId, Map<String, ?> properties) {
        track(Event.of("$profile.set").user(userId).properties(properties));
    }

    /** Removes properties from the person's profile. */
    public void unsetProfile(String userId, String... properties) {
        track(Event.of("$profile.unset").user(userId)
                .property("properties", properties == null ? Collections.emptyList() : Arrays.asList(properties)));
    }

    /** Records a consent decision with its evidence: {@code Consent.email(true).text("Send me offers")}. */
    public void consent(String userId, Consent consent) {
        if (consent == null || consent.isEmpty()) {
            reject("consent", "a consent names no channel: use Consent.email(true) or Consent.channel(\"sms\", false)");
            return;
        }
        track(Event.of("$consent.update").user(userId).properties(consent.body()));
    }

    // ------------------------------------------------------------------ errors

    /** Records an error you caught, as an {@code $error} event; the person comes from the user resolver, if any. */
    public void error(Throwable error) {
        error(ErrorReport.of(error).handled(true));
    }

    /** Records an error that happened to the person {@code userId}. */
    public void error(String userId, Throwable error) {
        error(ErrorReport.of(error).user(userId).handled(true));
    }

    /**
     * Records an error with every detail (the SDK contract, §8). At most {@code maxErrorsPerMinute} are sent; the same
     * exception reported twice on one thread (by a log and by the web filter, say) is sent once. An error that names
     * no person goes to the person the user resolver gives, else to the visitor of the current request
     * ({@link #setVisitor}), else to the service itself ({@code service:<name>}).
     */
    public void error(ErrorReport report) {
        try {
            if (!active || report == null || isClosed()) {
                return;
            }
            if (report.logger != null && report.logger.startsWith(LOGGER)) {
                return; // never our own errors: they would report themselves forever
            }
            if (report.throwable != null) {
                WeakReference<Throwable> last = lastError.get();
                if (last != null && last.get() == report.throwable) {
                    return;
                }
                lastError.set(new WeakReference<>(report.throwable));
            }
            if (!allowError()) {
                return;
            }
            Map<String, Object> body = report.body(service);
            Map<String, Object> request = REQUEST.get();
            if (request != null && !body.containsKey("http")) {
                body.put("http", request);
            }
            Event event = Event.of("$error").properties(body)
                    .user(report.userId).anonymous(report.anonymousId).session(report.sessionId);
            record(event);
        } catch (RuntimeException e) {
            log.log(Level.FINE, "Observa: error not recorded", e);
        }
    }

    /**
     * For web integrations: the request this thread is handling, e.g. {@code {"method": "POST", "path": "/bookings"}}.
     * Errors reported on this thread until {@link #clearRequest()} carry it as {@code http}.
     */
    public static void setRequest(Map<String, Object> request) {
        if (request == null) {
            REQUEST.remove();
        } else {
            REQUEST.set(request);
        }
    }

    /** For web integrations: the request is over. */
    public static void clearRequest() {
        REQUEST.remove();
    }

    // ------------------------------------------------------------------ the browser's visitor (contract §9)

    /**
     * The work on this thread is for the browser visitor in {@code header} (the {@code Observa-Visitor} header's value,
     * {@code <anonymousId>;<sessionId>}): until {@link #clearVisitor()}, {@code track} and {@code error} calls on this
     * thread carry its session, and its anonymous id when they name none. Identify, profile and consent events never
     * carry it. A malformed value is ignored. The Spring Boot starter does this for each request.
     *
     * @return the visitor, or null when the value was missing or malformed (and the thread then has none)
     */
    public static Visitor setVisitor(String header) {
        Visitor visitor = Visitor.parse(header);
        setVisitor(visitor);
        return visitor;
    }

    /** As {@link #setVisitor(String)}, with a visitor already read; null clears it. */
    public static void setVisitor(Visitor visitor) {
        if (visitor == null) {
            VISITOR.remove();
        } else {
            VISITOR.set(visitor);
        }
    }

    /** The visitor of the work on this thread, or null. */
    public static Visitor currentVisitor() {
        return VISITOR.get();
    }

    /** The work for the visitor is over (the end of the request, the message handled). */
    public static void clearVisitor() {
        VISITOR.remove();
    }

    /**
     * The value to send as the {@code Observa-Visitor} header when this work calls another of your services, or null
     * when there is no visitor: {@code request.header(Visitor.HEADER, Observa.visitorHeader())}.
     */
    public static String visitorHeader() {
        Visitor visitor = VISITOR.get();
        return visitor == null ? null : visitor.header();
    }

    /**
     * Runs {@code task} for the visitor in {@code header}, e.g. a message whose headers carry it, then restores what this
     * thread had before: {@code Observa.withVisitor(message.header("Observa-Visitor"), () -> handle(message))}.
     */
    public static void withVisitor(String header, Runnable task) {
        Visitor before = VISITOR.get();
        setVisitor(header);
        try {
            task.run();
        } finally {
            setVisitor(before);
        }
    }

    /** As {@link #withVisitor(String, Runnable)}, for a task that returns a value. */
    public static <T> T withVisitor(String header, Supplier<T> task) {
        Visitor before = VISITOR.get();
        setVisitor(header);
        try {
            return task.get();
        } finally {
            setVisitor(before);
        }
    }

    /**
     * {@code task}, carrying this thread's visitor and request trace to the thread that runs it: for work handed to an
     * executor, {@code executor.submit(Observa.carryVisitor(() -> sendReminder(id)))}.
     */
    public static Runnable carryVisitor(Runnable task) {
        Visitor visitor = VISITOR.get();
        String trace = TRACE.get();
        if ((visitor == null && trace == null) || task == null) {
            return task;
        }
        return () -> {
            Visitor before = VISITOR.get();
            String traceBefore = TRACE.get();
            setVisitor(visitor);
            setTraceId(trace);
            try {
                task.run();
            } finally {
                setVisitor(before);
                setTraceId(traceBefore);
            }
        };
    }

    /**
     * The trace id in a W3C {@code traceparent} header ({@code 00-<32 hex>-<16 hex>-<2 hex>}), or null when the value is
     * missing or malformed.
     */
    public static String traceIdOf(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length < 4 || !parts[0].matches("[0-9a-f]{2}") || "ff".equals(parts[0])
                || !parts[2].matches("[0-9a-f]{16}") || !parts[3].matches("[0-9a-f]{2}")) {
            return null;
        }
        return validTraceId(parts[1]) ? parts[1] : null;
    }

    /** A new random trace id, for a request that came in without one. */
    public static String newTraceId() {
        return randomHex(16);
    }

    /**
     * The work on this thread is the request with trace id {@code traceId} (32 lowercase hex digits): until
     * {@link #clearTrace()}, its events and errors carry it, and calls to your other services forward it. The Spring Boot
     * starter does this for every request: the incoming {@code traceparent}'s id, or a new one. Null or malformed
     * clears it.
     */
    public static void setTraceId(String traceId) {
        if (traceId == null || !validTraceId(traceId)) {
            TRACE.remove();
        } else {
            TRACE.set(traceId);
        }
    }

    /** The trace id of the request this thread is handling, or null. */
    public static String currentTraceId() {
        return TRACE.get();
    }

    /** Forgets the trace id: the thread's work is no longer part of the request. */
    public static void clearTrace() {
        TRACE.remove();
    }

    /**
     * The value to send as the {@code traceparent} header when this work calls another of your services (the same trace
     * id, a new span id), or null when there is no trace: {@code request.header(Observa.TRACEPARENT, Observa.traceparentHeader())}.
     */
    public static String traceparentHeader() {
        String trace = TRACE.get();
        return trace == null ? null : "00-" + trace + "-" + randomHex(8) + "-01";
    }

    private static boolean validTraceId(String id) {
        return id.length() == 32 && id.matches("[0-9a-f]{32}") && !id.equals("00000000000000000000000000000000");
    }

    /** {@code bytes} random bytes as lowercase hex, never all zeros (W3C: an all-zero id is invalid). */
    private static String randomHex(int bytes) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (true) {
            StringBuilder out = new StringBuilder(bytes * 2);
            boolean zero = true;
            for (int i = 0; i < bytes * 2; i++) {
                int digit = random.nextInt(16);
                zero &= digit == 0;
                out.append(Character.forDigit(digit, 16));
            }
            if (!zero) {
                return out.toString();
            }
        }
    }

    /** Within the per-minute limit; past it the error is counted as dropped. */
    private boolean allowError() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (now - errorWindowStart >= 60_000) {
                errorWindowStart = now;
                errorsInWindow = 0;
            }
            if (errorsInWindow >= maxErrorsPerMinute) {
                dropped++;
                if (!errorLimitLogged) {
                    errorLimitLogged = true;
                    log.warning("Observa: more than " + maxErrorsPerMinute + " errors in a minute; the rest of this minute's"
                            + " are not sent (counted as dropped). Raise maxErrorsPerMinute if that is expected.");
                }
                return false;
            }
            errorsInWindow++;
            return true;
        }
    }

    private boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** The name errors carry as their service, or null. */
    public String service() {
        return service;
    }

    /** Sends what is queued now and waits until it is sent, for at most 10 seconds; true if all of it was. */
    public boolean flush() {
        return flush(Duration.ofSeconds(10));
    }

    /** Sends what is queued now and waits until it is sent, for at most {@code timeout}; true if all of it was. */
    public boolean flush(Duration timeout) {
        if (!active) {
            return true;
        }
        long deadline = System.nanoTime() + Duration.ofMillis(Math.max(0, millis(timeout, 10_000))).toNanos();
        synchronized (lock) {
            flushRequested = true;
            lock.notifyAll();
            while (!queue.isEmpty() || pending != null || sending) {
                long left = deadline - System.nanoTime();
                if (left <= 0 || !worker.isAlive()) {
                    return false;
                }
                try {
                    lock.wait(Math.max(1, left / 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Stops: sends what is queued (for at most the shutdown timeout, 5 s by default), then ignores further calls. What
     * could not be sent is counted as dropped.
     */
    @Override
    public void close() {
        if (!active) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        if (current == this) {
            current = null;
        }
        flush(Duration.ofMillis(shutdownTimeoutMillis));
        synchronized (lock) {
            stopping = true;
            int left = queue.size() + (pending == null ? 0 : pending.size());
            if (left > 0) {
                dropped += left;
                log.warning("Observa: " + left + " events could not be sent before shutdown");
            }
            queue.clear();
            pending = null;
            lock.notifyAll();
        }
        if (hook != null && Thread.currentThread() != hook) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException | SecurityException e) {
                // Already stopping.
            }
        }
    }

    /** What the SDK did so far. */
    public Stats stats() {
        synchronized (lock) {
            return new Stats(queue.size() + (pending == null ? 0 : pending.size()), sent, rejected, dropped, retried);
        }
    }

    /** False when a missing or wrong key or {@code enabled(false)} turned the SDK off. */
    public boolean isEnabled() {
        return active;
    }

    // ------------------------------------------------------------------ recording

    private void record(Event e) {
        if (!active) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
        }
        if (e.type == null || e.type.length() > 128 || !TYPE.matcher(e.type).matches()) {
            String better = suggest(e.type);
            reject("type", "\"" + e.type + "\" is not an event type: use a dotted lowercase name, "
                    + (better != null ? "e.g. \"" + better + "\"" : "at least two words, like booking.confirmed"));
            return;
        }
        String userId = e.userId;
        String anonymousId = e.anonymousId;
        String sessionId = e.sessionId;
        // Who it goes to (contract §9): the ids the code names, the user resolver, the request's visitor, and for
        // errors the service itself. Profile events ($identify, $profile.*, $consent.update) only ever take the code's
        // ids: a header must never link a visitor to a user or change a profile.
        if (!e.type.startsWith("$") || e.type.equals("$error")) {
            if (blank(userId) && blank(anonymousId) && userResolver != null) {
                try {
                    userId = userResolver.get();
                } catch (RuntimeException ex) {
                    log.log(Level.FINE, "Observa: the user resolver failed", ex);
                }
            }
            Visitor visitor = VISITOR.get();
            if (visitor != null) {
                if (blank(anonymousId)) {
                    anonymousId = visitor.anonymousId();
                }
                if (blank(sessionId)) {
                    sessionId = visitor.sessionId();
                }
            }
            if (e.type.equals("$error") && blank(userId) && blank(anonymousId)) {
                anonymousId = "service:" + (service == null ? "java" : service);
            }
        } else if (blank(sessionId) && !blank(anonymousId)) {
            // A sign-in that names the request's own visitor (identify(user, visitor's id, …)) is part of its visit.
            Visitor visitor = VISITOR.get();
            if (visitor != null && anonymousId.equals(visitor.anonymousId())) {
                sessionId = visitor.sessionId();
            }
        }
        if (blank(userId) && blank(anonymousId)) {
            reject("person", "\"" + e.type + "\" names no person: pass the user id (or Event.anonymous(…) with the browser's visitor id)");
            return;
        }
        String json = write(e, userId, anonymousId, sessionId, TRACE.get());
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) {
            reject("size", "\"" + e.type + "\" is larger than 32 KB once written: send ids and the values you need, not whole documents");
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            queue.addLast(json);
            int over = queue.size() + (pending == null ? 0 : pending.size()) - maxQueueSize;
            for (int i = 0; i < over && !queue.isEmpty(); i++) {
                queue.removeFirst();
                dropped++;
            }
            if (over > 0) {
                debug("queue full; dropped the oldest " + over + " events");
            }
            if (queue.size() >= maxBatchSize) {
                lock.notifyAll();
            }
        }
    }

    private String write(Event e, String userId, String anonymousId, String sessionId, String traceId) {
        Map<String, Object> subject = new LinkedHashMap<>();
        if (!blank(userId)) {
            subject.put("userId", userId.trim());
        }
        if (!blank(anonymousId)) {
            subject.put("anonymousId", anonymousId.trim());
        }
        Instant at = e.occurredAt != null ? e.occurredAt : Instant.ofEpochMilli(clock.millis());
        StringBuilder out = new StringBuilder(256)
                .append("{\"id\":\"").append(UUID.randomUUID())
                .append("\",\"type\":").append(Json.write(e.type))
                .append(",\"occurredAt\":\"").append(Instant.ofEpochMilli(at.toEpochMilli())).append('"');
        if (!blank(e.platform)) {
            out.append(",\"platform\":").append(Json.write(e.platform.trim().toUpperCase(java.util.Locale.ROOT)));
        }
        if (!blank(sessionId)) {
            out.append(",\"sessionId\":").append(Json.write(sessionId.trim()));
        }
        out.append(",\"subject\":").append(Json.write(subject))
                .append(",\"body\":").append(Json.write(e.body));
        if (!e.attributes.isEmpty()) {
            out.append(",\"attributes\":").append(Json.write(e.attributes));
        }
        out.append(",\"context\":{").append(LIBRARY);
        if (traceId != null) {
            out.append(",\"traceId\":\"").append(traceId).append('"');
        }
        if (service != null) {
            out.append(",\"service\":").append(Json.write(service));
        }
        return out.append("}}").toString();
    }

    /** Counts a mistake in the caller's event; the first of each kind is a warning that says how to fix it. */
    private void reject(String kind, String why) {
        synchronized (lock) {
            rejected++;
        }
        warnOnce(kind, "not recorded: " + why);
    }

    private void warnOnce(String kind, String message) {
        if (active && warned.add(kind)) {
            log.warning("Observa: " + message + ". (Shown once; the next ones are logged at debug level.)");
        } else {
            debug(message);
        }
    }

    /** What a near-miss type was probably meant to be: "Order Created" and "orderCreated" → order.created. */
    static String suggest(String type) {
        if (type == null) {
            return null;
        }
        String s = type.trim().replaceAll("([a-z0-9])([A-Z])", "$1.$2").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.]+", ".").replaceAll("\\.{2,}", ".").replaceAll("^[._0-9]+|\\.+$", "");
        return s.length() <= 128 && TYPE.matcher(s).matches() ? s : null;
    }

    /** sk_prod_…wxyz: enough to tell keys apart in a log, not enough to use. */
    static String masked(String key) {
        int second = key.indexOf('_', 3);
        String prefix = second > 0 ? key.substring(0, second + 1) : key.substring(0, Math.min(3, key.length()));
        return key.length() > prefix.length() + 8 ? prefix + "…" + key.substring(key.length() - 4) : prefix + "…";
    }

    // ------------------------------------------------------------------ sending

    private void run() {
        long nextFlush = System.currentTimeMillis() + flushIntervalMillis;
        while (true) {
            List<String> batch;
            synchronized (lock) {
                try {
                    while (true) {
                        if (stopping) {
                            lock.notifyAll();
                            return;
                        }
                        long now = System.currentTimeMillis();
                        if (pending != null) {
                            if (now >= retryAt) {
                                break;
                            }
                            lock.wait(retryAt - now);
                            continue;
                        }
                        if (!queue.isEmpty() && (queue.size() >= maxBatchSize || flushRequested || closed || now >= nextFlush)) {
                            pending = takeBatch();
                            break;
                        }
                        if (queue.isEmpty()) {
                            flushRequested = false;
                            nextFlush = now + flushIntervalMillis;
                            lock.notifyAll();
                            lock.wait(flushIntervalMillis);
                        } else {
                            lock.wait(Math.max(1, nextFlush - now));
                        }
                    }
                } catch (InterruptedException e) {
                    return;
                }
                batch = pending;
                sending = true;
            }
            try {
                send(batch);
            } catch (RuntimeException e) {
                // A bug of ours must not stop the sender: drop this batch and carry on.
                log.log(Level.WARNING, "Observa: a batch could not be sent", e);
                synchronized (lock) {
                    if (pending == batch) {
                        dropped += batch.size();
                        pending = null;
                    }
                }
            } finally {
                synchronized (lock) {
                    sending = false;
                    nextFlush = System.currentTimeMillis() + flushIntervalMillis;
                    lock.notifyAll();
                }
            }
        }
    }

    /** The oldest events that fit one request: at most the batch size, and under 512 KB. */
    private List<String> takeBatch() {
        List<String> batch = new ArrayList<>(Math.min(maxBatchSize, queue.size()));
        int bytes = 64;
        while (!queue.isEmpty() && batch.size() < maxBatchSize) {
            int size = queue.peekFirst().length() * 3 + 1;
            if (!batch.isEmpty() && bytes + size > MAX_REQUEST_BYTES - 1024) {
                break;
            }
            batch.add(queue.removeFirst());
            bytes += size;
        }
        return batch;
    }

    private void send(List<String> batch) {
        String body = "{\"sentAt\":\"" + Instant.ofEpochMilli(clock.millis()) + "\",\"events\":[" + String.join(",", batch) + "]}";
        Transport.Response response;
        try {
            response = transport.send(url, key, body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            retry(batch, 0, "no answer (" + e + ")");
            return;
        }
        int status = response.status();
        if (status >= 200 && status < 300) {
            accepted(batch, response.body());
        } else if (status == 408 || status == 429 || status >= 500) {
            retry(batch, status == 429 || status == 503 ? response.retryAfterSeconds() : 0, "status " + status);
        } else if (status == 413 && batch.size() > 1) {
            split(batch);
        } else {
            refused(batch, status, response.body());
        }
    }

    private void accepted(List<String> batch, String answer) {
        Object outcome = Json.read(answer);
        long refused = 0;
        if (outcome instanceof Map) {
            Object r = ((Map<?, ?>) outcome).get("rejected");
            refused = r instanceof Number ? Math.min(batch.size(), ((Number) r).longValue()) : 0;
            Object errors = ((Map<?, ?>) outcome).get("errors");
            if (refused > 0 && errors instanceof List) {
                for (Object error : (List<?>) errors) {
                    if (error instanceof Map) {
                        Object index = ((Map<?, ?>) error).get("index");
                        Object reasons = ((Map<?, ?>) error).get("reasons");
                        warnOnce("server " + reasons, "Observa refused event #" + index + typeOf(batch, index) + ": " + reasons);
                    }
                }
            }
        }
        boolean first;
        synchronized (lock) {
            if (pending == batch) {
                pending = null;
            }
            sent += batch.size() - refused;
            rejected += refused;
            attempt = 0;
            retryAt = 0;
            first = !connected && batch.size() > refused;
            connected = true;
        }
        if (first) {
            log.info("Observa: connected; Observa accepted the first " + (batch.size() - refused) + " events");
        }
        debug("sent " + (batch.size() - refused) + " events" + (refused > 0 ? ", " + refused + " refused" : ""));
    }

    private void retry(List<String> batch, long retryAfterSeconds, String why) {
        synchronized (lock) {
            if (pending != batch) {
                return;
            }
            attempt++;
            long backoff = Math.min(retryMaxMillis, retryInitialMillis << Math.min(20, attempt - 1));
            long jitter = (long) (backoff * 0.2 * ThreadLocalRandom.current().nextDouble());
            long wait = Math.max(backoff + jitter, retryAfterSeconds * 1000);
            retryAt = System.currentTimeMillis() + wait;
            retried++;
            debug("retry #" + attempt + " in " + wait + " ms: " + why);
        }
    }

    /** Too large: the halves go separately, in order. */
    private void split(List<String> batch) {
        synchronized (lock) {
            if (pending != batch) {
                return;
            }
            List<String> first = new ArrayList<>(batch.subList(0, batch.size() / 2));
            List<String> second = batch.subList(batch.size() / 2, batch.size());
            for (int i = second.size() - 1; i >= 0; i--) {
                queue.addFirst(second.get(i));
            }
            pending = first;
            retryAt = 0;
        }
        debug("batch too large; sending it in two halves");
    }

    private void refused(List<String> batch, int status, String answer) {
        boolean auth = status == 401 || status == 403;
        synchronized (lock) {
            if (pending == batch) {
                pending = null;
            }
            rejected += batch.size();
            attempt = 0;
            retryAt = 0;
            if (auth) {
                if (authLogged) {
                    return;
                }
                authLogged = true;
            }
        }
        String why = describe(answer);
        if (auth) {
            log.severe("Observa refused the key (status " + status + "): " + why
                    + ". Check OBSERVA_KEY is the source's active secret key (sk_…). Events are not being stored.");
        } else {
            log.warning("Observa refused a batch of " + batch.size() + " events (status " + status + "): " + why);
        }
    }

    /** " (booking.confirmed)": the type of the batch's event at {@code index}, for a log line. */
    private static String typeOf(List<String> batch, Object index) {
        if (!(index instanceof Number) || ((Number) index).intValue() < 0 || ((Number) index).intValue() >= batch.size()) {
            return "";
        }
        Object event = Json.read(batch.get(((Number) index).intValue()));
        return event instanceof Map && ((Map<?, ?>) event).get("type") != null ? " (" + ((Map<?, ?>) event).get("type") + ")" : "";
    }

    /** The problem's {@code detail} from Observa's answer, or the answer itself. */
    private static String describe(String answer) {
        Object problem = Json.read(answer);
        if (problem instanceof Map && ((Map<?, ?>) problem).get("detail") != null) {
            Object code = ((Map<?, ?>) problem).get("code");
            return ((Map<?, ?>) problem).get("detail") + (code == null ? "" : " (" + code + ")");
        }
        return answer == null || answer.isEmpty() ? "no details" : answer.substring(0, Math.min(300, answer.length()));
    }

    // ------------------------------------------------------------------ small things

    private void debug(String message) {
        log.log(debug ? Level.INFO : Level.FINE, "Observa: " + message);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static long millis(Duration d, long fallback) {
        return d == null ? fallback : d.toMillis();
    }

    private static String version() {
        try (InputStream in = Observa.class.getResourceAsStream("/observa-java.properties")) {
            Properties p = new Properties();
            if (in != null) {
                p.load(in);
            }
            String v = p.getProperty("version");
            return v == null || v.startsWith("$") ? "dev" : v;
        } catch (IOException | RuntimeException e) {
            return "dev";
        }
    }
}
