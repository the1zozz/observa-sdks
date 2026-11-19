# Observa developer guide: web and Java SDKs

How to send events to Observa from your web app and from your Java servers. Read sections 1 and 2 first, then the part
for your side: [the browser](#3-the-browser-observaweb) or [the server](#4-the-server-java-sdk).

| | Web SDK `@observa/web` | Java SDK `observa-java` (+ Spring Boot starter) |
|---|---|---|
| Runs in | Any browser app: Angular, React, Vue, Next.js, plain HTML | Any Java 8, 11, 17 or 25 application; Spring Boot 2.7, 3 and 4 |
| Sends | What people do: pages, clicks, your business events | What happens in your systems: confirmations, payments, changes, failures |
| Key | Publishable `pk_…` (safe in a web page) | Secret `sk_…` (kept in the server's secret settings) |
| Full reference | [web/README.md](web/README.md) | [java/README.md](java/README.md), [starter](java/observa-spring-boot-starter/README.md) |

**Contents:**
[1. Before you start](#1-before-you-start) ·
[2. The rules both sides follow](#2-the-rules-both-sides-follow) ·
[3. The browser](#3-the-browser-observaweb) ·
[4. The server](#4-the-server-java-sdk) ·
[5. Who sends what](#5-who-sends-what) ·
[6. Check it works](#6-check-it-works) ·
[7. Troubleshooting](#7-troubleshooting) ·
[8. What the SDKs promise](#8-what-the-sdks-promise)

---

## 1. Before you start

### Sources and keys

In the portal, a **source** is one app or service in one environment: "Booking web (prod)", "Booking API (dev)". Each
source has keys:

- **Publishable key** (`pk_<env>_…`) for browsers and apps. It is public by nature: Observa accepts it only from the
  sites listed on it.
- **Secret key** (`sk_<env>_…`) for your servers. Treat it like a database password: environment variables or a
  secrets manager, never in code, a web page or a mobile app.

To create them: **Data → Sources → New source** (or open one) **→ New key**, then under "Where will this key be used?" choose
**A website or app (publishable key)** or **Your servers (secret key)**. Copy the key at once: it is shown only once. If you lose it, create a new one and revoke the old.

A web app and its backend can share one source, with one key of each kind.

### One source per environment

Use separate sources for development, staging and production, so test events never mix with real ones. Keys say
their environment: `pk_dev_…`, `sk_prod_…`.

### The endpoint

The endpoint is Observa's base address, with no path, e.g. `https://observa.yourcompany.com`. Locally it is
`http://localhost:8090`. Each SDK adds its own path.

---

## 2. The rules both sides follow

### Use the same user id everywhere

After sign-in, the browser calls `observa.identify(userId)`. Your server passes the same `userId` on every call. That
is what puts browser and server events on **one person, in one timeline**.

Use your **stable internal id**: the user's primary key in your database, as a string. Don't use an email or phone
number: they change, and they are personal data.

### Name events `object.action`, in the past tense

- Lowercase letters, digits and `_`, with at least two parts joined by dots: `booking.confirmed`, `invoice.paid`,
  `ticket.resolved`, `account.sign_in_failed`.
- Not valid: `Booking Confirmed`, `bookingConfirmed`, `booking`, `booking-confirmed`.
- Names starting with `$` belong to Observa (`$identify`, `$consent.update`).
- Use one name per business fact, and put the variations in the data: `booking.confirmed` with
  `{ "channel": "phone" }`, not `booking.confirmed_by_phone`.

Both SDKs check names before sending, and tell you how to fix a wrong one.

### Send ids and values, not whole objects

An event's data is a small JSON object of at most 32 KB. Send the ids and the values that matter (`bookingId`,
`nights`, `total`), not a whole database row. Never send passwords, tokens or keys.

### Consent comes with its evidence

Before Observa may send someone messages on a channel, record their decision **where they make it**, with the exact
words they agreed to. Both SDKs have a `consent` call for this (examples below).

---

## 3. The browser: `@observa/web`

### Install

Install it from its file on the [GitHub release](https://github.com/the1zozz/observa-sdks/releases):

```sh
npm install https://github.com/the1zozz/observa-sdks/releases/download/0.1.0/observa-web-0.6.0.tgz
```

Your `package.json` then says `"@observa/web": "https://…/observa-web-0.6.0.tgz"`. Imports are
`import { observa } from '@observa/web'`.

It has no dependencies, is about 6 KB gzipped, and ships as an ES module with TypeScript types.

### Start it once, as early as possible

```ts
import { observa } from '@observa/web';

observa.init({ key: 'pk_dev_…', endpoint: 'http://localhost:8090', debug: true });
```

From now on, page views (including route changes in single-page apps) and clicks on links and buttons are recorded by
themselves. Keep the key in your environment files, one per build. In Angular, run it in `provideAppInitializer`; in
Next.js, in a client component. [web/README.md §3](web/README.md#3-initialise) has the code for each framework.

### Who the person is

```ts
// after a successful sign-in
observa.identify(String(user.id), { plan: user.plan, role: user.role });

// on sign-out: forget the person, start a new anonymous visitor
observa.reset();
```

Before sign-in, the visitor is anonymous. `identify` joins that anonymous history to the person.

### Your own events

```ts
observa.track('booking.confirmed', { bookingId: 'B-17', nights: 3, total: 450 });
observa.track('search.performed', { term: 'sea view', results: 12 }, { attributes: { channel: 'web' } });
```

A button can be tracked with no code: `<button data-observa-event="report.exported" data-observa-format="csv">`. To keep an
area out of click tracking (a payment form, say), add `data-observa-ignore` to it.

### Consent and web push

```ts
observa.consent({ email: true, sms: false }, { text: 'Send me booking updates by email', form: 'signup' });

await observa.subscribePush();     // from a click: asks for permission and subscribes this browser
```

### Privacy in the browser

The SDK never reads what people type into inputs, and it removes query strings from URLs. In production a browser may
not set email or phone: anyone could type someone else's. Your server sends those (section 4).

---

## 4. The server: Java SDK

### Install

Both are published on [JitPack](https://jitpack.io/#the1zozz/observa-sdks), with no account or token needed. Add the
JitPack repository once:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>
```

(Gradle: `maven("https://jitpack.io")` in `repositories`.) Then add one dependency:

| You have | Add |
|---|---|
| Spring Boot 2.7, 3 or 4 | `com.github.the1zozz.observa-sdks:observa-spring-boot-starter:0.1.0` |
| Any other Java 8+ application | `com.github.the1zozz.observa-sdks:observa-java:0.1.0` |

Neither brings any other library into your application. The Java code is in the packages `com.integralways.observa.*`.

### Configure: two environment variables

```
OBSERVA_KEY=sk_prod_…
OBSERVA_ENDPOINT=https://observa.yourcompany.com
```

Without a key the SDK is off: your application starts and runs as before. With the wrong kind of key (`pk_…`) it says
so in the log and stays off.

### Spring Boot: inject it

```java
@Service
public class BookingService {
    private final Observa observa;

    public BookingService(Observa observa) {
        this.observa = observa;
    }

    public void confirm(Booking booking) {
        // … your code …
        observa.track(booking.getUserId(), "booking.confirmed", booking);   // the object's fields become the data
    }
}
```

The starter makes one `Observa` for the application and closes it when the application stops, sending what is
still queued. Settings are `observa.*` in `application.properties`, and your IDE completes them.

### Plain Java

```java
Observa observa = Observa.builder().build();        // one per application; reads OBSERVA_KEY and OBSERVA_ENDPOINT
```

### The calls

```java
// who the person is: your server is trusted, so email and phone are kept, encrypted, on the person
observa.identify("42", Props.of("name", "Lina", "email", "lina@example.com", "plan", "gold"));

// what happened: with a map, or with your own object or record
observa.track("42", "invoice.paid", Props.of("invoiceId", "INV-9", "amount", new BigDecimal("120.00")));
observa.track("42", "booking.confirmed", booking);

// every detail: another time, a visitor id from the browser, filter tags
observa.track(Event.of("payment.failed").user("42").at(failedAt).property("reason", "card_declined")
        .attribute("region", "riyadh"));

// profile and consent
observa.setProfile("42", Props.of("plan", "platinum"));
observa.consent("42", Consent.email(true).text("Send me booking updates by email").form("signup"));
```

No call ever throws or waits for the network. A background thread sends batches every second and retries when Observa
can't be reached.

### Send after the transaction commits

A change that is rolled back must not be reported. Send from an after-commit hook, and keep all of the backend's
events in one class that your services call. [java/INTEGRATING.md](java/INTEGRATING.md) shows both, from a real
integration.

### Errors, linked to people

With the Spring Boot starter, ERROR logs and requests that fail with a server error are sent to Observa as `$error`
events, on the person who was signed in. Elsewhere, add the Logback appender, or call `observa.error(userId, e)`
yourself. If your user id is not the authenticated name, see [the starter's Errors section](java/observa-spring-boot-starter/README.md#errors).

### Test your own code

```java
ObservaTesting.Capture capture = ObservaTesting.capture();
BookingService service = new BookingService(capture.observa());

service.confirm(booking);

assertEquals("B-17", capture.single("booking.confirmed").property("bookingId"));
```

The capture keeps events in memory, after the same checks as in production. To turn Observa off in tests instead, set
`observa.enabled=false`.

### Try it

[java/examples](java/examples) has a plain Java quickstart and a small Spring Boot API. Each one runs with your key in
under a minute.

---

## 5. Who sends what

The browser and the server see different things. Each one sends what **only it knows**, so nothing is counted twice:

| The browser sends | The server sends |
|---|---|
| Pages, clicks, searches, what was looked at | What actually happened: `booking.confirmed`, `invoice.paid`, `ticket.resolved` |
| The visit: campaign, referrer, device | Who the person is, from your database: email, phone, name (`identify`) |
| Steps people take on screen: a form opened, a step completed | Why something failed: `account.sign_in_failed` with the reason |
| Consent ticked on a form | Before and after: `plan.changed` with `oldPlan` and `newPlan` |
| | Things with no screen: scheduled jobs, webhooks from partners, imports |

When both could send an event, **the server's version is the truth**: it knows the operation committed. Don't send
the same event from both.

---

## 6. Check it works

1. **Turn on debug:** `debug: true` in the browser, `observa.debug=true` (or `.debug(true)`) on the server.
2. **Watch the log:**
   - Browser: `[observa]` lines in the console, and a `POST …/ingest/v1/client/events` answered with `202` every
     5 seconds.
   - Server: at start, `Observa 0.1.0: sending events to … with the key sk_prod_…wxyz`; after the first batch,
     `Observa: connected`.
3. **In the portal:** **Data → Sources → your source → Live events** shows each event within seconds, with
   `WEB` or `SERVER`.
4. **On the person:** **Customers → People →** the user id. Browser and server events are on one timeline.

---

## 7. Troubleshooting

| You see | Why | What to do |
|---|---|---|
| `401` / `Observa refused the key` | Wrong, revoked or other environment's key | Copy the key again, or create a new one |
| `403` `OBS-ING-4031` (browser) | The page's address is not on the key's allowed sites | Add the exact `scheme://host:port` to the key |
| `403` `OBS-ING-4032` | The wrong kind of key: a `pk_` on the server or an `sk_` in the browser | Use a secret key on servers and a publishable key in browsers |
| `… is not an event type` | A name such as `Booking Confirmed` | Use `booking.confirmed`: the message suggests the fix |
| `… names no person` (server) | The user id was `null` or empty | Pass the signed-in user's id |
| Events arrive but not on the same person | The browser and the server use different ids | Use the database id, as a string, in both |
| `Observa is off` (server) | No `OBSERVA_KEY`, or a `pk_` key | Set the secret key |
| `429` | Over the rate limit for a short burst | Nothing: both SDKs wait and retry |

---

## 8. What the SDKs promise

Both follow the same [SDK contract](CONTRACT.md):

- **They never break your application.** No call throws. A wrong key or a missing setting turns the SDK off with one
  clear log line.
- **Your code never waits on Observa.** Calls only queue an event; sending happens in the background.
- **Nothing is stored twice.** Every event has its own id, kept through retries.
- **Nothing is lost during short outages.** Network errors, `429` and `5xx` are retried with growing waits. The queue
  holds 10 000 events on servers and 1 000 in browsers. Beyond that, the oldest are dropped and counted.
- **Memory stays bounded.** Events over 32 KB are refused, and a full queue drops its oldest events.
- **Shutdown sends what is left:** `sendBeacon` when a page closes; a shutdown hook, or Spring's shutdown, on servers.
