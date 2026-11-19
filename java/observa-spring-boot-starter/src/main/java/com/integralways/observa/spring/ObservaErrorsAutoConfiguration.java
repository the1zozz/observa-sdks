package com.integralways.observa.spring;

import com.integralways.observa.logback.ObservaAppender;
import com.integralways.observa.sdk.Observa;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Errors in Observa (the SDK contract, §8), with nothing to write: ERROR logs through Logback, and web requests that
 * fail with a server error, each linked to the signed-in user. Off with {@code observa.errors.enabled=false}.
 */
@AutoConfiguration(after = ObservaAutoConfiguration.class)
@ConditionalOnClass(Observa.class)
@ConditionalOnBean(Observa.class)
@ConditionalOnProperty(prefix = "observa.errors", name = "enabled", matchIfMissing = true)
public class ObservaErrorsAutoConfiguration {

    /** With Spring Security: the authenticated name, unless the application says otherwise. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    static class Security {
        @Bean
        @ConditionalOnMissingBean
        ObservaUserResolver observaUserResolver() {
            return new SecurityUserResolver();
        }
    }

    /** Logback, Spring Boot's default logging: an appender on the root logger, removed when the context closes. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"ch.qos.logback.classic.LoggerContext", "org.slf4j.LoggerFactory"})
    static class Logback {
        @Bean
        LogbackErrors observaLogbackErrors(Observa observa, ObservaProperties properties) {
            return new LogbackErrors(observa, properties.getErrors().getLevel());
        }
    }

    /**
     * Which servlet API is the one Spring runs on, when an application has both on its classpath: Spring 6 and later
     * (Boot 3 and 4) use jakarta.servlet, and only they have this class.
     */
    static final String SPRING_6 = "org.springframework.aot.AotDetector";

    /** Spring Boot 3 and 4. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = {"jakarta.servlet.Filter", SPRING_6})
    static class JakartaWeb {
        @Bean
        JakartaErrorFilter observaJakartaErrorFilter(Observa observa) {
            return new JakartaErrorFilter(observa);
        }
    }

    /** Spring Boot 2.7. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "javax.servlet.Filter")
    @ConditionalOnMissingClass(SPRING_6)
    static class JavaxWeb {
        @Bean
        JavaxErrorFilter observaJavaxErrorFilter(Observa observa) {
            return new JavaxErrorFilter(observa);
        }
    }

    /** Puts an {@link ObservaAppender} on Logback's root logger, and takes it off again. */
    static final class LogbackErrors implements org.springframework.beans.factory.DisposableBean {
        private final ch.qos.logback.classic.Logger root;
        private final ObservaAppender appender;

        LogbackErrors(Observa observa, String level) {
            org.slf4j.ILoggerFactory factory = org.slf4j.LoggerFactory.getILoggerFactory();
            if (!(factory instanceof ch.qos.logback.classic.LoggerContext)) {
                // Another logging library is bound to SLF4J: nothing to attach to.
                this.root = null;
                this.appender = null;
                return;
            }
            ch.qos.logback.classic.LoggerContext context = (ch.qos.logback.classic.LoggerContext) factory;
            this.root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            this.appender = new ObservaAppender(observa);
            appender.setName("OBSERVA");
            appender.setContext(context);
            appender.setLevel(level);
            appender.start();
            root.addAppender(appender);
        }

        @Override
        public void destroy() {
            if (root != null) {
                root.detachAppender(appender);
                appender.stop();
            }
        }
    }
}
