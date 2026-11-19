# Java SDK changelog

The Java SDK (`observa-java`) and the Spring Boot starter (`observa-spring-boot-starter`) are released together, with
the same version. Each version is published once, from a tag `java-v<version>`.

## 0.2.0 · not released yet

Errors in Observa, linked to the people they happen to.

- **`observa.error(…)`** and `ErrorReport` send `$error` events. Each carries its kind, message, stack, logger, level,
  service, request id and, inside a web request, its method, path and status. A fingerprint groups the same failure at
  the same place.
- **Safe to leave on:**
  - at most 60 errors a minute (`maxErrorsPerMinute`);
  - the same exception is sent once per thread;
  - the SDK never sends its own errors.
- **Who it happened to:** the person in the call or the MDC, else the builder's `userResolver`, else the service
  itself (`service:<name>`).
- **New module `observa-logback`:** an appender for Logback 1.2 to 1.5. `ObservaLogHandler` does the same for
  `java.util.logging`.
- **Spring Boot starter:**
  - ERROR logs and web requests that fail with a server error are sent, linked to the signed-in user;
  - this works with Spring Security, or through your own `ObservaUserResolver`;
  - settings are `observa.errors.*` and `observa.service`;
  - it works on Boot 2.7 (javax.servlet) and Boot 3 and 4 (jakarta.servlet).
- **`ObservaTesting.capture(settings)`** lets your tests set a service or a user resolver.

## 0.1.0 · 2026-11-18

The first release.

- **One library for Java 8, 11, 17 and 25**, with no other libraries inside. It sends to `POST /ingest/v1/events` with a
  secret key (`sk_…`).
- **The calls:** `track`, `identify`, `setProfile`, `unsetProfile` and `consent`, and `Event.of(…)` for every detail.
- **Your own objects as event data:** a record's components, or a class's getters (Lombok included), become the
  event's properties.
- **It never gets in the way:**
  - calls only queue the event: a background thread batches every second, up to 100 events and under 512 KB;
  - network errors, `408`, `429` and `5xx` are retried with the same event ids, and `Retry-After` is respected;
  - the queue holds at most 10 000 events;
  - nothing ever throws into your code;
  - queued events are sent when the JVM stops.
- **Mistakes say how to fix them,** in one warning per kind. The log says where events are sent, and confirms once
  Observa accepts them.
- **`ObservaTesting.capture()`** keeps events in memory, so your own tests can check what your code sent.
- **Spring Boot starter for Boot 2.7, 3 and 4:**
  - set `observa.key` and `observa.endpoint` (or `OBSERVA_KEY` and `OBSERVA_ENDPOINT`) and inject `Observa`;
  - it is closed with the application, sending what is queued;
  - a missing key never stops the application;
  - your IDE completes the `observa.*` settings.
