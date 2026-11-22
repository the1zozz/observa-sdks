# Observa SDK contract

What every Observa SDK does, whatever its language. The web SDK (`web`) and the Java SDK (`java`) follow it;
Python, Ruby and the others are written and reviewed against it. When an SDK cannot do something here, its README says
so. Behaviour that is not in this contract is not promised to customers.

**Version 1 · 2026-11-19** (§9 added).

## 1. Two kinds of SDK

| | Client SDK (browser, mobile) | Server SDK (Java, Python, Ruby, …) |
|---|---|---|
| Key | publishable `pk_<env>_…`, public by nature | secret `sk_<env>_…`, kept in the server's secret settings |
| Endpoint | `POST {endpoint}/ingest/v1/client/events` | `POST {endpoint}/ingest/v1/events` |
| Key goes | in the body: `{"key": …}` (so `sendBeacon` works) | in the header `Authorization: Bearer sk_…`, never in the body |
| Who the person is | the SDK keeps an anonymous id, then the user id after `identify` | the caller names the person on every call (`userId`, or `anonymousId` when it has one from the client) |
| Platform | `WEB`, `IOS`, `ANDROID` | `SERVER` (the server sets it when the event names none) |
| May set email and phone | not in production | yes: a server is trusted |

A server SDK refuses to start with a `pk_` key, and a client SDK with an `sk_` key: the server would refuse them anyway
(`403 OBS-ING-4032`), and a secret key in an app is a leak.

## 2. The envelope (v1)

One request carries a batch:

```json
{ "sentAt": "2026-11-17T09:30:00.120Z",
  "events": [
    { "id": "6f1c2a3e-…",                  // UUID, made by the SDK, once per event
      "type": "booking.confirmed",          // dotted lowercase; $ for Observa's own types
      "occurredAt": "2026-11-17T09:29:58.004Z",
      "platform": "SERVER",                 // optional for server SDKs
      "sessionId": "…",                     // client SDKs
      "subject": { "userId": "42", "anonymousId": "a-…" },   // at least one
      "body": { … },                        // the business data
      "attributes": { … },                  // small tags for filtering (optional)
      "context": { "library": { "name": "observa-java", "version": "0.1.0" }, … } } ] }
```

- `id` is a UUID made when the event is recorded and **kept through every retry**: the server stores an event once per
  source and id, so a retry after a timeout never doubles it.
- `occurredAt` is when it happened, in UTC with milliseconds; `sentAt` is when the batch left. The server corrects device
  clocks with the difference.
- `context.library` names the SDK and its version, always.
- Observa's own types: `$identify` (body: traits, e.g. `email`, `phone`, `name`), `$profile.set` (body: properties),
  `$profile.unset` (body `{"properties": [names]}`), `$consent.update` (body
  `{"channels": {"email": true}, "topic", "text", "form"}`), and `$error` (§8).

## 3. The public API

Names follow the language's conventions; the meaning does not change.

| Call | Does |
|---|---|
| `track(type, body?)` (client) / `track(userId, type, body?)` (server) | Records a business event |
| `identify(userId, traits?)` | Client: from now on this device is that person, and the anonymous visitor is joined to them. Server: records `$identify` |
| `setProfile(…)`, `unsetProfile(…)` | Changes profile properties |
| `consent(…)` | Records a consent decision with its evidence (the text the person agreed to) |
| `reset()` (client) | Sign-out: forget the user, start a new anonymous visitor |
| `flush()` | Sends what is queued now; returns when done or after a timeout |
| `close()` / shutdown | Flushes, then stops; later calls are ignored |
| `stats()` | Counters: queued, sent, rejected, dropped, retried |

Validation the SDK does before queuing (the same as the server's, so mistakes show at once, in debug logs): the type
matches `^(\$[a-z][a-z0-9_]*(\.[a-z0-9_]+)*|[a-z][a-z0-9_]*(\.[a-z0-9_]+)+)$` and is at most 128 characters; a person is
named; an event is at most 32 KB once written. An invalid event is dropped and counted as rejected, never thrown.

## 4. Never hurt the host application

- **Never throw** into the caller, from any call, ever. Invalid input, a full queue, a network failure, a bug in the SDK:
  log at debug level, count it, carry on.
- **Never block** the caller on the network. Calls only queue; a background worker sends. `flush()` and `close()` are
  the only calls that wait, and they take a timeout.
- **Bounded memory.** The queue holds at most `maxQueueSize` events (default 10 000 on servers, 1 000 in browsers).
  When full, the **oldest** are dropped and counted (`dropped`).
- **No surprise dependencies.** A server SDK uses only its language's standard library where it can, so it cannot clash
  with the application's own libraries.

## 5. Batching

- Send when `maxBatchSize` events are queued (default 100, at most 500) or every `flushInterval` (default 1 s on
  servers, 5 s in browsers), whichever comes first.
- Keep a request under **512 KB**: if adding an event would pass it, send what is there and start a new batch.
- One request at a time per SDK instance, so events of a source keep their order.
- Browsers: when the page is hidden or closed, send what is left with `sendBeacon`.

## 6. Answers and retries

| Answer | Meaning | The SDK |
|---|---|---|
| `202` | Accepted; the body says `accepted`, `rejected` and why (`errors[]`) | Counts sent and rejected; logs each rejection's reasons at debug level. **Does not resend** rejected events |
| `400` | The batch itself is malformed | Drops the batch, counts it rejected, logs why |
| `401` | Unknown or revoked key | Drops the batch, logs an error **once** (not per batch); keeps queuing in case the key is fixed by a restart |
| `403` | Wrong kind of key, or origin not allowed | Same as 401 |
| `413` | Too large | Splits the batch in two and sends the halves; a single event too large is dropped as rejected |
| `408`, `429`, `5xx`, network error, timeout | Try again later | Retries **the same batch, same ids**, with exponential backoff from 1 s doubling to 60 s, plus up to 20 % random jitter; on `429` and `503` waits at least `Retry-After` seconds when the server gives it |

There is no limit on the number of retries: the batch waits while newer events queue behind it, and the queue's bound
(§4) decides what is lost if Observa stays unreachable. Each wait is counted in `retried`.

## 7. Shutdown

- `close()` stops taking events, sends what is queued, and gives up after `shutdownTimeout` (default 5 s), counting
  what was left as `dropped`.
- Server SDKs flush on process exit by themselves where the language allows it (a JVM shutdown hook, `atexit`), and the
  framework integrations (Spring, Django, Rails) close them with the application.

## 8. Errors (`$error`)

The Java SDK does this from 0.2.0. An SDK that offers error capture sends each error as an event of type `$error`:

```json
{ "type": "$error",
  "body": { "kind": "java.lang.IllegalStateException",      // the exception or error class
            "message": "Card declined",                      // first 1000 characters
            "stack": "…",                                    // first 16 KB
            "logger": "com.acme.payments.PaymentService",    // server SDKs
            "level": "ERROR",
            "service": "booking-api",                        // the application's name
            "requestId": "…", "handled": true,
            "fingerprint": "3f9a…",                          // the same for the same failure at the same place
            "http": { "method": "POST", "path": "/bookings", "status": 500 } } }   // when it happened in a request
```

`fingerprint` groups errors: it is computed from the kind and the top frames of the stack, without line numbers, so
the same failure keeps its group across releases. An error that names no person, such as one at startup, is sent with
`anonymousId` set to `service:<service>`.

It is linked to the person the same way as other events (the user id from the log's context, or the visitor the client
SDK passed to the server, §9). Error capture is on by default for uncaught errors and for `ERROR` logs; it never sends
logs below `ERROR` unless asked, it never captures its own errors, and it sends at most `maxErrorsPerMinute` (default 60)
so an error loop cannot flood the queue.

## 9. One person across browser and server

The web SDK does this from 0.7.0, and the Java SDK and its Spring Boot starter from 0.3.0.

A client SDK tells the application's own servers who the visitor is with one request header:

```
Observa-Visitor: <anonymousId>;<sessionId>
```

Only those two random ids: **never the user id**, a name or contact details. A server never trusts the header about
who someone is, since anyone can send any header.

**Client SDKs:**

- They give the value (`visitorHeader()`), and the headers for a request (`visitorHeaders(url)`).
- They add it only to the page's own origin and to hosts the app lists as its own (`visitorHosts`), never to other
  sites.
- After `reset()`, the value names the new visitor.

**Server SDKs, five rules:**

1. **Read** the header from incoming requests and messages. They may also read the W3C `baggage` entry
   `observa.visitor` (the `;` raw or written `%3B`). A value is accepted only when well formed: one or two ids, each
   1 to 128 letters, digits, `-` or `_`. Anything else is ignored, never an error. A setting lets a service ignore it
   on paths only outsiders call (webhooks).
2. **Keep** it for the work being done: a thread-local, a context variable, async local storage, `context.Context`.
3. **Attach** it to `track` and `$error` events made in that work. Who an event goes to is decided in this order,
   and the first answer wins:
   1. the user id the code passes;
   2. the user the server's own sign-in knows (a user resolver);
   3. the header's visitor, as `anonymousId`;
   4. for errors only, `service:<name>`.

   The header's session is added as `sessionId` when the event names none, so a signed-in person's server events are
   also inside their visit.
4. **Never attach** it to `$identify`, `$profile.set`, `$profile.unset` or `$consent.update`. Linking a visitor to a user
   is the client's `identify` at sign-in, never a header's.
5. **Forward** it on outgoing HTTP calls and messages to the system's own services. A setting may limit the hosts.

When the visitor signs in, Observa links the anonymous id to the user, so what every server recorded under it moves to
the person.

**When no client SDK starts the visit** (a server-rendered site, an app without an Observa SDK), the entry server makes
the visitor itself:

6. **Make** it, when configured and no header came in, from an id the server already has for the device and the visit
   (its own cookie, a header the app sends). The ids are one-way hashes of those values: `v` and `s` followed by the
   first 32 hex digits of SHA-256 of `observa.visitor\n<value>` and `observa.session\n<value>`. So the same value
   gives the same visitor in every language, and the value itself never leaves the server. Rules 2, 3 and 5 then apply
   unchanged.
7. **Link** it only by an explicit `identify(userId, anonymousId, traits)` at sign-in, where the code names the
   visitor's id. When that id is the work's own visitor, the event takes its session, so the sign-in is inside the visit.
   Rule 4 still holds: a visitor is never taken implicitly. An SDK never sets a cookie of its own.

## 10. Configuration

| Setting | Server default | Client default |
|---|---|---|
| `key` | required, `sk_…` | required, `pk_…` |
| `endpoint` | required, e.g. `https://observa.example.com` | required |
| `flushInterval` | 1 s | 5 s |
| `maxBatchSize` | 100 (≤ 500) | 20 (≤ 100) |
| `maxQueueSize` | 10 000 | 1 000 |
| `requestTimeout` | 10 s | the browser's |
| `shutdownTimeout` | 5 s | — |
| `debug` | off | off |
| `enabled` | on | on — off sends nothing, for tests and local runs |

Server SDKs also read `OBSERVA_KEY` and `OBSERVA_ENDPOINT` from the environment when they are not given.

## 11. What every SDK is tested for

Against a fake server, in the SDK's own tests:

1. A tracked event arrives with a UUID, the type, `occurredAt`, the person, `context.library`.
2. Many events are batched up to `maxBatchSize`, and under 512 KB.
3. `503`, `429` with `Retry-After`, a timeout and a dropped connection are retried with the **same ids**, after the right wait.
4. `400`, `401`, `403` are not retried; a 401 is logged once.
5. `413` splits the batch.
6. A `202` with rejections counts them and does not resend them.
7. A full queue drops the oldest and counts them.
8. An invalid type, a missing person and an over-sized event are rejected without throwing.
9. Nothing ever throws into the caller, also when the server is down and when the SDK is closed.
10. `flush()` sends everything and returns; `close()` flushes and then ignores further calls.
11. The wrong kind of key is refused at start.

And end to end, against a running Observa: an event sent by the SDK appears on the person in Customers → People.

Server SDKs are tested on every language version they claim to support (Java: 8, 11, 17 and 25).
