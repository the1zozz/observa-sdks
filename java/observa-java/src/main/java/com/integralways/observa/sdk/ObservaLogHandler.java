package com.integralways.observa.sdk;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Sends {@code java.util.logging} errors to Observa: every record at SEVERE (or the level you set) becomes an
 * {@code $error} event, with its exception when it has one. For applications that log with JUL; Logback and Log4j2
 * have their own integrations.
 * <pre>{@code
 * Logger.getLogger("").addHandler(new ObservaLogHandler());   // the root logger: every logger's errors
 * }</pre>
 * It sends through the application's {@link Observa#current() Observa}, so build that first. The person comes from
 * the Observa's user resolver. The SDK's own logs are never sent.
 */
public class ObservaLogHandler extends Handler {

    private volatile Observa observa;

    /** Sends through the application's Observa ({@link Observa#current()}), whenever there is one. */
    public ObservaLogHandler() {
        setLevel(Level.SEVERE);
    }

    /** Sends through this Observa. */
    public ObservaLogHandler(Observa observa) {
        this();
        this.observa = observa;
    }

    @Override
    public void publish(LogRecord record) {
        try {
            if (record == null || !isLoggable(record)
                    || (record.getLoggerName() != null && record.getLoggerName().startsWith(Observa.LOGGER))) {
                return;
            }
            Observa target = observa != null ? observa : Observa.current();
            if (target == null) {
                return;
            }
            String message = record.getMessage();
            try {
                if (getFormatter() != null) {
                    message = getFormatter().formatMessage(record);
                } else if (message != null && record.getParameters() != null && record.getParameters().length > 0) {
                    message = java.text.MessageFormat.format(message, record.getParameters());
                }
            } catch (RuntimeException e) {
                // keep the raw message
            }
            ErrorReport report = record.getThrown() != null
                    ? ErrorReport.of(record.getThrown()).property("logMessage", ErrorReport.truncate(message, ErrorReport.MAX_MESSAGE))
                    : ErrorReport.of("log", message, null);
            target.error(report.logger(record.getLoggerName()).level(level(record.getLevel())).handled(true));
        } catch (RuntimeException e) {
            // A log handler must never throw.
        }
    }

    private static String level(Level level) {
        if (level == null) {
            return "ERROR";
        }
        if (level.intValue() >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        return level.intValue() >= Level.WARNING.intValue() ? "WARN" : "INFO";
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
