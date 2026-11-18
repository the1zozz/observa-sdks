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
  <version>0.1.0</version>
</dependency>
```

Gradle: `maven("https://jitpack.io")` in `repositories`, then
`implementation("com.github.the1zozz.observa-sdks:observa-spring-boot-starter:0.1.0")`.

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
