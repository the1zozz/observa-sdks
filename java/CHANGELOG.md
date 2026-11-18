# Java SDK changelog

The Java SDK (`observa-java`) and the Spring Boot starter (`observa-spring-boot-starter`) are released together, with
the same version. Each version is published once, from a tag `java-v<version>`.

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
