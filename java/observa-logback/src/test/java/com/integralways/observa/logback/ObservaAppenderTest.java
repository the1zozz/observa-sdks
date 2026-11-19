package com.integralways.observa.logback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.integralways.observa.sdk.ObservaTesting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Logback ERROR logs as $error events, through a real Logback logger. */
class ObservaAppenderTest {

    /** The application's Logback, as SLF4J sets it up. */
    final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    final ObservaTesting.Capture capture = ObservaTesting.capture(b -> b.service("booking-api"));
    final ObservaAppender appender = new ObservaAppender(capture.observa());

    {
        appender.setContext(context);
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    @AfterEach
    void stop() {
        MDC.clear();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
        capture.close();
    }

    @Test
    void anErrorWithItsExceptionIsSentWithTheMdcsUser() {
        Logger log = context.getLogger("com.acme.BookingService");
        MDC.put("userId", "42");
        MDC.put("traceId", "t-9");
        log.error("Booking {} failed", "B-17", new IllegalStateException("No room left"));

        ObservaTesting.CapturedEvent error = capture.single("$error");
        assertEquals("42", error.userId());
        assertEquals("java.lang.IllegalStateException", error.property("kind"));
        assertEquals("No room left", error.property("message"));
        assertEquals("Booking B-17 failed", error.property("logMessage"));
        assertEquals("com.acme.BookingService", error.property("logger"));
        assertEquals("ERROR", error.property("level"));
        assertEquals("t-9", error.property("requestId"));
        assertEquals("booking-api", error.property("service"));
        assertTrue(((String) error.property("stack")).contains("ObservaAppenderTest"));
    }

    @Test
    void onlyErrorsAndNeverTheSdksOwn() {
        Logger log = context.getLogger("com.acme.Jobs");
        log.info("started");
        log.warn("slow");
        log.error("Nightly import failed");
        context.getLogger("com.integralways.observa").error("the SDK's own trouble");

        List<ObservaTesting.CapturedEvent> errors = capture.events("$error");
        assertEquals(1, errors.size());
        assertEquals("log", errors.get(0).property("kind"));
        assertEquals("Nightly import failed", errors.get(0).property("message"));
        assertNull(errors.get(0).userId());
        assertEquals("service:booking-api", errors.get(0).anonymousId());
    }

    @Test
    void warningsTooWhenAsked() {
        appender.setLevel("WARN");
        appender.setUserKey("uid");
        MDC.put("uid", "7");
        context.getLogger("com.acme.Jobs").warn("Retrying the payment");
        ObservaTesting.CapturedEvent warning = capture.single("$error");
        assertEquals("WARN", warning.property("level"));
        assertEquals("7", warning.userId());
    }
}
