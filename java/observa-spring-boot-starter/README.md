# Observa Spring Boot starter

The [Observa Java SDK](../README.md) for Spring Boot: add one dependency, set two properties, inject `Observa`.

Works with **Spring Boot 2.7, 3 and 4**, on Java 8, 11, 17 and 25. It is tested on Boot 2.7.18, 3.5.16 and 4.1.2.

## 1. Add the dependency

It is published on [JitPack](https://jitpack.io/#the1zozz/observa-sdks), with no account or token needed.

Maven:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.the1zozz.observa-sdks</groupId>
  <artifactId>observa-spring-boot-starter</artifactId>
  <version>0.3.0</version>
</dependency>
```

Gradle: `maven("https://jitpack.io")` in `repositories`, then
`implementation("com.github.the1zozz.observa-sdks:observa-spring-boot-starter:0.3.0")`.

It brings the Java SDK with it. It does not bring Spring Boot: your application's own version is used.

## 2. Set the key and the address

Get the secret key from the portal: **Data → Sources** → your source → **New key** → **Your servers (secret key)**.

Set it as environment variables (Spring reads them by itself):

```
OBSERVA_KEY=sk_prod_…
OBSERVA_ENDPOINT=https://observa.yourcompany.com
```

or in `application.yml`, with the key still taken from the environment:

```yaml
observa:
  key: ${OBSERVA_KEY}
  endpoint: https://observa.yourcompany.com
```

## 3. Inject it

```java
@Service
public class BookingService {

    private final Observa observa;

    public BookingService(Observa observa) {
        this.observa = observa;
    }

    public void confirm(Booking booking) {
        // … your code …
        observa.track(booking.getUserId(), "booking.confirmed",
                Props.of("bookingId", booking.getId(), "nights", booking.getNights()));
    }
}
```

That's all. Every call in the [Java SDK guide](../README.md#3-send-events) works the same way: `identify`, `setProfile`,
`consent`, `Event.of(…)`.

## What the starter does for you

- **One `Observa` for the application**, shared by every bean (it is thread-safe).
- **Closed with the application.** When Spring shuts down, it sends what is still queued (up to `observa.shutdown-timeout`).
- **Never stops your application.** With no key, a wrong key or a publishable `pk_` key, it logs why and sends nothing.
  The bean is still there to inject, and your code runs as usual.
- **Your own bean wins.** Define an `Observa` `@Bean` yourself and the starter steps aside.
- **IDE hints.** Your IDE completes and explains the `observa.*` properties.

## Errors

The starter sends your application's errors to Observa, linked to the signed-in user:

- **ERROR logs,** with their exceptions, through Logback, Spring Boot's default logging;
- **web requests that fail with a server error:** an exception that escapes, or a 5xx your `@ExceptionHandler` makes from one. Client errors (4xx) are not errors.

Each one carries the request's method, path and status, `spring.application.name` as its service, and the
`X-Request-Id` header if there is one.

**Who it happened to:** with Spring Security, the authenticated user's name. If your apps identify people by another
id (the database id, say), tell the starter how to find it:

```java
@Bean
ObservaUserResolver observaUser(UserRepository users) {
    return () -> {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null
                : users.findByUsername(auth.getName()).map(u -> String.valueOf(u.getId())).orElse(null);
    };
}
```

Use the same id your web app passes to `observa.identify(…)`, so errors land on the same person as their clicks.

| Property | Default | What it does |
|---|---|---|
| `observa.errors.enabled` | `true` | `false`: no errors are sent |
| `observa.errors.max-per-minute` | `60` | At most this many a minute, so an error loop can't flood anything |
| `observa.errors.level` | `ERROR` | `WARN` sends warnings too |
| `observa.service` | `spring.application.name` | The application's name on its errors |

## One person across browser and server

When your web app adds the visitor header to its API calls (`observa.visitorHeaders(url)` in the web SDK), the starter
reads it on every request. Nothing to write:

- **Events and errors in the request carry the visitor and the visit:**
  - an error with nobody signed in lands on the visitor, not on the service;
  - a signed-in user keeps their own id;
  - `identify`, profile and consent events never take the visitor.
- **Calls to your other services forward it,** through your `RestTemplate`, `RestClient.Builder` and
  `WebClient.Builder` beans, and through your OpenFeign clients (Spring Cloud OpenFeign applies the starter's
  `FeignForwarding` interceptor to every client). A client you build in code with `new` gets it by adding the
  `ObservaVisitorInterceptor` bean to it, or the `FeignForwarding` bean for `Feign.builder()`.
  With a load balancer, `forward-hosts` matches the service's name (`http://payments/…` → `payments`).
- **One request across services:** every request gets a trace id, the incoming W3C `traceparent`'s or a new one.
  Its events and errors carry it, with the application's name, and the calls above forward it as `traceparent`.
  In Observa, an event's or an error's request then shows everything that happened in it, in every service, even
  when nobody was signed in and no visitor was known.
- **Systems that use OpenTelemetry** can send it as `observa.visitor` in the `baggage` header instead.
- **CORS:** if your web app calls the API from another origin, allow the `Observa-Visitor` request header in your CORS
  settings.

| Property | Default | What it does |
|---|---|---|
| `observa.visitor.enabled` | `true` | `false`: the header is neither read nor forwarded |
| `observa.visitor.ignore-paths` | none | Paths only outsiders call, such as `/webhooks/**`: the header is ignored there |
| `observa.visitor.forward-hosts` | every host | Only these hosts get the header, e.g. `billing,*.internal.example.com` |
| `observa.visitor.anonymous-from` | none | Without the web SDK: where the server finds the visitor, `cookie:<name>` or `header:<name>` |
| `observa.visitor.session-from` | none | Without the web SDK: where it finds the visit, e.g. `cookie:JSESSIONID` |
| `observa.visitor.trace` | `true` | `false`: requests get no trace id and calls forward no `traceparent` |

### Without the web SDK

When a visit doesn't start in the web SDK (a server-rendered site, an app without an Observa SDK, a kiosk), the
entry service makes the visitor itself, from an id it already has:

```properties
observa.visitor.anonymous-from=cookie:device_id      # or header:X-Device-Id, sent by your app
observa.visitor.session-from=cookie:JSESSIONID       # optional: the visit
```

- **Only when no `Observa-Visitor` header came in.** A visit that did start in the web SDK keeps the web SDK's ids.
- **The value is hashed, never sent.** `device_id=d-7f3a` becomes a visitor such as `v1706d30f…`, and the same value
  always gives the same visitor. A session cookie never leaves your server.
- **Everything else is the same:** events and errors in the request go to that visitor and visit, and calls to your
  other services forward it. Those services need no setting.
- **Link it at sign-in.** Only `identify` with the visitor's id links it to the user:

  ```java
  Visitor visitor = Observa.currentVisitor();
  observa.identify(user.getId(), visitor == null ? null : visitor.anonymousId(), Props.of("plan", user.getPlan()));
  ```

  The sign-in then joins the visit, and everything the visitor did moves to the person.
- **The starter never sets a cookie.** It reads one your application already has. Setting a long-lived device cookie
  is your application's decision, under your consent rules.
- A wrong value, such as `anonymous-from=device_id`, stops the application at start with the reason.

## Settings

| Property | Default | What it does |
|---|---|---|
| `observa.key` | `OBSERVA_KEY` | The source's **secret** key (`sk_…`) |
| `observa.endpoint` | `OBSERVA_ENDPOINT` | Observa's address, with no path |
| `observa.enabled` | `true` | `false` sends nothing; `Observa` can still be injected and called |
| `observa.debug` | `false` | Logs each batch and why an event was refused, at INFO |
| `observa.flush-interval` | `1s` | How often the queue is sent |
| `observa.max-batch-size` | `100` | Events per request (at most 500) |
| `observa.max-queue-size` | `10000` | Events held while Observa cannot be reached |
| `observa.request-timeout` | `10s` | How long one request may take |
| `observa.shutdown-timeout` | `5s` | How long it keeps sending when the application stops |

Anything else, such as your own transport through a proxy, goes in an `ObservaBuilderCustomizer`:

```java
@Bean
ObservaBuilderCustomizer viaProxy() {
    return builder -> builder.transport(new MyProxyTransport());
}
```

## In your tests

To send nothing, turn it off in your test properties (`src/test/resources/application.properties`):

```properties
observa.enabled=false
```

To check what your code tracked, give the test an in-memory Observa
([details](../README.md#7-testing-your-own-code)):

```java
@SpringBootTest
class BookingServiceTest {

    static final ObservaTesting.Capture capture = ObservaTesting.capture();

    @TestConfiguration
    static class Config {
        @Bean
        Observa observa() {
            return capture.observa();   // the starter steps aside
        }
    }

    @Autowired BookingService service;

    @Test
    void confirmingTracksIt() {
        service.confirm(booking);
        assertEquals("B-17", capture.single("booking.confirmed").property("bookingId"));
    }
}
```

## Logs

The SDK logs through `java.util.logging`, under the logger `com.integralways.observa`. Spring Boot routes that into
your own logging. To see each batch while you develop, set `observa.debug=true`.
