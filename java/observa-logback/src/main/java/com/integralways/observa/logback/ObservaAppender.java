package com.integralways.observa.logback;

import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.integralways.observa.sdk.ErrorReport;
import com.integralways.observa.sdk.Observa;

/**
 * Sends Logback ERROR logs, with their exceptions, to Observa as {@code $error} events. In {@code logback.xml}:
 * <pre>{@code
 * <appender name="OBSERVA" class="com.integralways.observa.logback.ObservaAppender" />
 * <root level="INFO">
 *   <appender-ref ref="OBSERVA" />
 * </root>
 * }</pre>
 * The Spring Boot starter adds it by itself. The person is the MDC's {@code userId} (set it where you know who is
 * signed in: {@code MDC.put("userId", user.getId())}), or else what the Observa's user resolver gives. A request id
 * is taken from the MDC's {@code requestId} or {@code traceId}. It sends through the application's
 * {@link Observa#current() Observa}; the SDK's own logs are never sent.
 */
public class ObservaAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private volatile Observa observa;
    private Level level = Level.ERROR;
    private String userKey = "userId";
    private String[] requestIdKeys = {"requestId", "traceId"};

    /** Sends through the application's Observa ({@link Observa#current()}), whenever there is one. */
    public ObservaAppender() {
    }

    /** Sends through this Observa. */
    public ObservaAppender(Observa observa) {
        this.observa = observa;
    }

    /** The lowest level sent: ERROR by default. WARN sends warnings too. */
    public void setLevel(String level) {
        this.level = Level.toLevel(level, Level.ERROR);
    }

    /** The MDC key that holds the user id (default {@code userId}). */
    public void setUserKey(String userKey) {
        if (userKey != null && !userKey.trim().isEmpty()) {
            this.userKey = userKey.trim();
        }
    }

    /** The MDC keys tried for a request id, comma separated (default {@code requestId,traceId}). */
    public void setRequestIdKeys(String keys) {
        if (keys != null && !keys.trim().isEmpty()) {
            this.requestIdKeys = keys.trim().split("\\s*,\\s*");
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            if (event == null || !event.getLevel().isGreaterOrEqual(level)) {
                return;
            }
            String loggerName = event.getLoggerName();
            if (loggerName != null && loggerName.startsWith("com.integralways.observa")) {
                return;
            }
            Observa target = observa != null ? observa : Observa.current();
            if (target == null) {
                return;
            }
            target.error(report(event));
        } catch (RuntimeException e) {
            // An appender must never throw into the application's logging.
        }
    }

    ErrorReport report(ILoggingEvent event) {
        IThrowableProxy proxy = event.getThrowableProxy();
        String message = event.getFormattedMessage();
        ErrorReport report;
        if (proxy instanceof ThrowableProxy) {
            report = ErrorReport.of(((ThrowableProxy) proxy).getThrowable());
            if (message != null) {
                report.property("logMessage", message);
            }
        } else if (proxy != null) {
            report = ErrorReport.of(proxy.getClassName(), proxy.getMessage(), ThrowableProxyUtil.asString(proxy));
            if (message != null) {
                report.property("logMessage", message);
            }
        } else {
            report = ErrorReport.of("log", message, null);
        }
        report.logger(event.getLoggerName()).level(event.getLevel().toString()).handled(true);
        if (event.getThreadName() != null) {
            report.property("thread", event.getThreadName());
        }
        Map<String, String> mdc;
        try {
            mdc = event.getMDCPropertyMap();
        } catch (RuntimeException e) {
            mdc = null; // a Logback set up without an MDC: send the error without it
        }
        if (mdc != null) {
            String user = mdc.get(userKey);
            if (user != null && !user.trim().isEmpty()) {
                report.user(user.trim());
            }
            for (String key : requestIdKeys) {
                String id = mdc.get(key);
                if (id != null && !id.trim().isEmpty()) {
                    report.requestId(id.trim());
                    break;
                }
            }
        }
        return report;
    }
}
