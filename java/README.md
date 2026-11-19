# Observa Java SDK

Send events from your Java servers to Observa: a booking confirmed, a payment settled, a ticket closed — what happens in
your own systems, next to what people do in your web and mobile apps.

- **One library for Java 8, 11, 17 and 25.** Tested on each.
- **No dependencies.** It cannot clash with your application's libraries.
- **Never in your way.** Calls only queue the event and return; a background thread sends batches and retries. Nothing
  ever throws into your code, and a missing key turns the SDK off instead of stopping your application.

It follows the [Observa SDK contract](../CONTRACT.md). Adding it to a backend that already has an app sending events? See [Adding Observa to an existing backend](INTEGRATING.md).

## 1. Get a secret key

In the Observa portal: **Data → Sources** → your source (or a new one, e.g. `billing`) → **New key** → **Your servers
(secret key)**. Copy the key (`sk_prod_…`); it is shown once.

Keep it in your server's secret settings, never in code, a web page or an app:

```
OBSERVA_KEY=sk_prod_…
OBSERVA_ENDPOINT=https://observa.yourcompany.com
```

## 2. Install

The SDK is published on [JitPack](https://jitpack.io/#the1zozz/observa-sdks): no account or token needed.

Maven: add JitPack once, then the dependency:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.the1zozz.observa-sdks</groupId>
  <artifactId>observa-java</artifactId>
  <version>0.2.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.the1zozz.observa-sdks:observa-java:0.2.0")
}
```

The Java code is in the package `com.integralways.observa.sdk`. Each version's jars are also attached to its
[GitHub release](https://github.com/the1zozz/observa-sdks/releases); `observa-java` needs no other library, so the jar
alone is enough. To work on the SDK itself: `mvn -f java install` puts `0.3.0-SNAPSHOT` in your local Maven repository.

What changed in each version: [CHANGELOG.md](CHANGELOG.md).

**Spring Boot (2.7, 3 or 4)?** Use the [starter](observa-spring-boot-starter/README.md) instead. Add
`observa-spring-boot-starter`, set the two variables above, and inject `Observa` into your beans: no builder code.

## 3. Send events

Make **one** `Observa` for your application and share it (it is thread-safe):

```java
import com.integralways.observa.sdk.*;

Observa observa = Observa.builder().build();   // reads OBSERVA_KEY and OBSERVA_ENDPOINT

// Who the person is. Your server is trusted: email and phone are kept, encrypted, on the person.
observa.identify("42", Props.of("email", "lina@example.com", "name", "Lina", "plan", "gold"));

// What happened, with its data.
observa.track("42", "booking.confirmed", Props.of("bookingId", "B-17", "nights", 3, "total", 450.0));

// Or pass your own object: its fields become the event's data.
observa.track("42", "booking.confirmed", booking);

// A consent decision, with the words the person agreed to.
observa.consent("42", Consent.email(true).text("Send me offers by email").form("signup"));
```

The user id (`"42"`) must be the same one your web and mobile apps pass to `observa.identify(...)`: that is how server
and browser events end up on the same person.

**Event types** are dotted lowercase names: `booking.confirmed`, `payment.failed`, `ticket.resolved`.

### Every detail of an event

```java
observa.track(Event.of("payment.failed")
        .user("42")
        .at(failedAt)                       // when it happened; now if not given
        .property("invoiceId", "INV-9")
        .property("amount", new BigDecimal("120.00"))
        .attribute("region", "riyadh"));    // a small tag to filter by
```

Use `.anonymous(anonymousId)` instead of `.user(...)` when the browser passed your server its visitor id and the person
has not signed in yet.

### Profile

```java
observa.setProfile("42", Props.of("plan", "platinum", "city", "Riyadh"));
observa.unsetProfile("42", "trialEndsAt");
```

### Values you can send

- **Simple values:** strings, numbers (including `BigDecimal`), booleans, `null`, `Instant`, `Date`, `LocalDate` and the
  other `java.time` types, `UUID`, enums and `Optional`.
- **Collections:** lists, arrays and nested maps.
- **Your own objects:** a record sends its components, and a class sends its getters (`getTotal()` → `total`,
  `isPaid()` → `paid`). This includes classes written with Lombok. Nested objects are sent the same way. If a getter
  throws, that property is left out.

Anything else is sent as its `toString()`.

Don't pass database entities: pass the values you want. An entity's getters can load the whole database graph, or fail
outside a transaction.

## 4. Shutting down

The SDK sends what is left when the JVM stops (a shutdown hook). If you close it yourself — at the end of a batch job, or
in a framework's shutdown — call:

```java
observa.close();      // sends what is queued (up to 5 s), then ignores further calls
```

`observa.flush()` sends what is queued now and waits for it (up to 10 s), without closing.

## 5. Configuration

| Builder method | Default | What it does |
|---|---|---|
| `key(…)` | `OBSERVA_KEY` | The source's **secret** key (`sk_…`). A publishable key (`pk_…`) is refused. |
| `endpoint(…)` | `OBSERVA_ENDPOINT` | Observa's address, e.g. `https://observa.yourcompany.com`. The SDK adds `/ingest/v1/events`. |
| `enabled(false)` | on | Sends nothing: for tests and local runs. |
| `debug(true)` | off | Logs each batch and why Observa refused an event, at INFO. Development only. |
| `flushInterval(…)` | 1 s | How often the queue is sent; a full batch goes at once. |
| `maxBatchSize(…)` | 100 | Events per request (at most 500). |
| `maxQueueSize(…)` | 10 000 | Events held while Observa cannot be reached; beyond this the oldest are dropped. |
| `requestTimeout(…)` | 10 s | How long one request may take. |
| `shutdownTimeout(…)` | 5 s | How long `close()` keeps sending. |
| `retryInitialDelay(…)` / `retryMaxDelay(…)` | 1 s / 60 s | The wait before a retry, doubling up to the maximum. |
| `shutdownHook(false)` | on | Do not flush when the JVM stops (for frameworks that close the SDK themselves). |
| `transport(…)` | HTTP | Send through your own code, e.g. a proxy. |

## 6. What it guarantees

- **Batching:** every `flushInterval`, or as soon as `maxBatchSize` events are queued; each request under 512 KB.
- **Retries:** network errors, timeouts, `408`, `429` and `5xx` are retried with the **same event ids**, so Observa stores
  each event once. The wait doubles from 1 s to 60 s, with a little randomness, and respects `Retry-After`.
- **Refusals are not retried:** `400`, `401`, `403` and events Observa refused in a `202` are counted, and the reason is
  logged. A rejected key is logged once, as an error.
- **Bounded memory:** at most `maxQueueSize` events; the oldest are dropped and counted.
- **Checks before sending:** an invalid type, no person or an event over 32 KB is counted as rejected. The first of each
  kind is a warning that says how to fix it. Nothing is thrown.

`observa.stats()` gives the counters (`queued`, `sent`, `rejected`, `dropped`, `retried`) for your monitoring.

## 7. Testing your own code

`ObservaTesting.capture()` gives you an `Observa` that keeps events in memory, so your tests can check what your code
tracked. The events go through the same checks as in production:

```java
ObservaTesting.Capture capture = ObservaTesting.capture();
BookingService service = new BookingService(capture.observa());

service.confirm(booking);

ObservaTesting.CapturedEvent e = capture.single("booking.confirmed");   // fails with what was tracked if there is none
assertEquals("42", e.userId());
assertEquals("B-17", e.property("bookingId"));
assertEquals("Lina", e.property("guest.name"));                         // nested, with dots
```

`capture.events()` lists everything in order, `capture.rejected()` counts what the checks refused, and
`capture.clear()` starts again.

## 8. Errors

The SDK can send your errors to Observa as `$error` events, each linked to the person it happened to. The Errors page
then shows which errors hit how many people, and a segment or a journey can react to them: "people who hit a payment
error this week", or a help email after a failed payment.

**With Spring Boot:** nothing to do. The [starter](observa-spring-boot-starter/README.md#errors) sends ERROR logs and
web requests that fail with a server error.

**With Logback,** without Spring: add the `observa-logback` dependency (same group and version), then in `logback.xml`:

```xml
<appender name="OBSERVA" class="com.integralways.observa.logback.ObservaAppender" />
<root level="INFO">
  <appender-ref ref="OBSERVA" />
</root>
```

Put the signed-in user's id in the MDC where you know it: `MDC.put("userId", user.getId())`.

**With `java.util.logging`:** `Logger.getLogger("").addHandler(new ObservaLogHandler());`

**Yourself,** for an error you catch and handle but still want to see:

```java
try {
    payments.charge(booking);
} catch (PaymentException e) {
    observa.error(booking.getUserId(), e);
    // … your handling …
}

// with every detail
observa.error(ErrorReport.of(e).user("42").requestId(requestId).property("bookingId", "B-17"));
```

Each error carries its type, message (first 1000 characters), stack trace (first 16 KB), logger, level, your service's
name (`service(…)` or `OBSERVA_SERVICE`) and, inside a web request, its method, path and status.

**It is safe to leave on:**
- **At most 60 errors a minute** (`maxErrorsPerMinute(…)`), so an error loop can't flood anything. The rest of that minute's errors are counted as dropped.
- **The same exception is sent once,** even when a log and the web filter both see it.
- **The SDK never sends its own errors.**
- **Logs below ERROR are not sent** unless you ask for them.

**Who it happened to:** the person named in the call or in the MDC. Failing that, the `userResolver(…)` you give the
builder. Failing that, the service itself (`service:<name>`).

## 8b. One person across browser and server

The web SDK can tell your servers who the visitor is: `Observa-Visitor: <anonymousId>;<sessionId>`, two random ids,
never the user id. With it, what your servers record during a visitor's request lands on that visitor and in their
visit. When they sign in, all of it moves to the person.

**With Spring Boot:** nothing to do (see the [starter](observa-spring-boot-starter/README.md#one-person-across-browser-and-server)).

**Elsewhere,** in your web framework's middleware:

```java
Observa.setVisitor(request.getHeader(Visitor.HEADER));   // a malformed value is ignored
try {
    chain.handle(request);
} finally {
    Observa.clearVisitor();
}
```

Then, on that thread:

- `track(null, "booking.started")` goes to the visitor;
- `track("42", …)` stays on user 42, in the visitor's visit;
- errors go to the visitor rather than to the service;
- `identify`, `setProfile` and `consent` never take the visitor: linking a visitor to a user is the browser's job at
  sign-in.

**Passing it on** to your other services:

```java
// an HTTP call
connection.setRequestProperty(Visitor.HEADER, Observa.visitorHeader());

// a message: put it in the message's headers, and in the consumer
Observa.withVisitor(message.header(Visitor.HEADER), () -> handle(message));

// work handed to another thread
executor.submit(Observa.carryVisitor(() -> sendReminder(bookingId)));
```

## 9. Logging

The SDK logs through `java.util.logging`, under the logger `com.integralways.observa`. SLF4J, Logback and Log4j2 can pick
that up with their JUL bridges, and Spring Boot does it for you.

| Level | When |
|---|---|
| `INFO` | At start: `Observa 0.2.0: sending events to https://… with the key sk_prod_…wxyz`. After the first accepted batch: `Observa: connected` |
| `WARNING` | The first mistake of each kind, with the fix, e.g. `"Order Created" is not an event type: use a dotted lowercase name, e.g. "order.created"`. Each kind is shown once; the next ones go to debug level. Also: a batch refused, or events lost at shutdown |
| `SEVERE` | The SDK is off (no key, wrong key or no endpoint), or Observa refused the key |
| `FINE` | Every detail. Logged at `INFO` with `debug(true)` |

## 10. Check it works

1. Start your application: the log says `Observa …: sending events to …`. Send one event: the log says
   `Observa: connected`.
2. In the portal, **Data → Sources → your source**: the event appears under **Live events**, platform **SERVER**.
3. **Customers → People → the person**: it is on their timeline, next to their browser events.

## Troubleshooting

| You see | Why | What to do |
|---|---|---|
| `Observa is off, nothing will be sent: no key` | `OBSERVA_KEY` is not set | Set it, or pass `.key(…)` |
| `… is not an event type` | A type such as `Booking Confirmed` or `signup` | Use dotted lowercase names with at least two parts: `booking.confirmed`, `signup.completed` |
| `… names no person` | The user id was `null` or empty | Pass the signed-in user's id |
| `Observa refused event #… : …` | Observa's checks refused it (e.g. a property the event type does not allow) | Fix what the reason names. The type's rules are in the portal under Data → Event types |
| `…a publishable key (pk_) is for browsers and apps` | The web key was used | Make a secret key: Data → Sources → New key → Your servers |
| `Observa refused the key (status 401)` | The key is wrong, revoked, or for another Observa | Check `OBSERVA_KEY` and `OBSERVA_ENDPOINT` |
| `Observa refused a batch … (status 404)` | The endpoint is wrong | Use Observa's base address, with no path |
| `stats().retried()` keeps growing | Observa cannot be reached | Check the network; events wait in the queue (up to `maxQueueSize`) |
| Events arrive but not on the right person | Different user ids on server and browser | Use the same id everywhere |

## Supported versions

Java 8, 11, 17 and 25, on any operating system. Spring Boot 2.7, 3 and 4 through the
[starter](observa-spring-boot-starter/README.md).
