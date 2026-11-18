# Observa SDKs

Send events to Observa from your web apps and your servers: what people
do, and what happens in your systems, on one timeline per person.

| SDK | For | Install | Guide |
|---|---|---|---|
| **Web** `@observa/web` | Any browser app: Angular, React, Vue, Next.js, plain HTML | `npm install https://github.com/the1zozz/observa-sdks/releases/download/0.1.0/observa-web-0.6.0.tgz` | [web/README.md](web/README.md) |
| **Java** `observa-java` | Any Java 8, 11, 17 or 25 application | `com.github.the1zozz.observa-sdks:observa-java:0.1.0` from [JitPack](https://jitpack.io/#the1zozz/observa-sdks) | [java/README.md](java/README.md) |
| **Spring Boot starter** | Spring Boot 2.7, 3 and 4 | `com.github.the1zozz.observa-sdks:observa-spring-boot-starter:0.1.0` from JitPack | [java/observa-spring-boot-starter](java/observa-spring-boot-starter/README.md) |

**Start here:** the [developer guide](DEVELOPER-GUIDE.md) covers keys, the rules both sides follow, the browser and the
server step by step, and how to check it works.

## A first event from Java in two minutes

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
  <version>0.1.0</version>
</dependency>
```

```java
Observa observa = Observa.builder().build();   // reads OBSERVA_KEY and OBSERVA_ENDPOINT
observa.track("42", "booking.confirmed", Props.of("bookingId", "B-17", "nights", 3));
```

Runnable examples: [java/examples](java/examples).

## What every SDK promises

They never throw into your code or make it wait on the network. They batch events in the background, retry safely
(an event is stored once, however often it is sent), and keep memory bounded. The full rules are in the
[SDK contract](CONTRACT.md), which every Observa SDK follows.

## Repository

| Folder | What |
|---|---|
| [`java/`](java) | The Java SDK, the Spring Boot starter, their tests (Java 8, 11, 17, 25; Spring Boot 2.7, 3.5, 4.1) and examples |
| [`web/`](web) | The browser SDK, its tests and its web push service worker |
| [`CONTRACT.md`](CONTRACT.md) | What every Observa SDK does, whatever its language |
| [`DEVELOPER-GUIDE.md`](DEVELOPER-GUIDE.md) | How to add Observa to an app and its backend |

Releases: a tag (e.g. `0.1.0`) is built by JitPack on request; the jars and the web SDK's package are attached to the
[GitHub release](https://github.com/the1zozz/observa-sdks/releases). Changes: [java/CHANGELOG.md](java/CHANGELOG.md).
