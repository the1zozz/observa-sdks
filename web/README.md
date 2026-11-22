# @observa/web: integration guide

The browser SDK sends what users do in your web app (page views, clicks and your own business events) to Observa, where you watch them live, and later build profiles, segments and campaigns from them.

It works in any web app: Angular, React, Vue, Next.js, or a plain HTML page.

**Contents:**
[How it works](#how-it-works) ·
[1. Get a key](#1-get-a-key) ·
[2. Install](#2-install) ·
[3. Initialise](#3-initialise) ·
[4. Identify users](#4-identify-users) ·
[5. Track your own events](#5-track-your-own-events) ·
[Automatic tracking](#automatic-tracking) ·
[Configuration](#configuration) ·
[Environments and keys](#environments-and-keys) ·
[Check it works](#check-it-works) ·
[Troubleshooting](#troubleshooting) ·
[Reference](#reference)

---

## How it works

```
your web app                              Observa
┌────────────────────────┐               ┌──────────────────────────────────────────┐
│ observa.init({key, …}) │               │ ingest: checks the key, the site, the    │
│ page views, clicks,    │  POST batch   │ rate limit and each event; answers 202    │
│ observa.track(…)       │ ────────────► │   → Kafka → ClickHouse (events)           │
│ queue → batch every 5 s│  every 5 s    │                                          │
└────────────────────────┘               │ portal: Sources → your source → Live     │
                                         └──────────────────────────────────────────┘
```

You need two things from Observa:

| | What | Example |
|---|---|---|
| **key** | The publishable key of your app's source, created in the portal | `pk_prod_…` |
| **endpoint** | The Observa address your users' browsers can reach | `http://localhost:8090` (dev), `https://observa.yourcompany.com` |

The key tells Observa which company (workspace) and which app (source) the events belong to. It is safe to put in browser code, because Observa accepts it only from the sites you list for it.

---

## 1. Get a key

1. Sign in to the portal (dev: http://localhost:8090) with your workspace, email and password. You need a role that can manage sources, such as administrator or developer.
2. Go to **Sources → New source**.
3. Fill in the form:
   - **Name:** for example "Web shop". The short name fills itself in.
   - **Environment:** Production, Staging or Development. Use one source per environment (see [Environments and keys](#environments-and-keys)).
   - **Allowed sites:** the exact address your app runs on, one per line: scheme, host and port, with no path. For example `https://shop.example.com`, or `http://localhost:4200` for local development.
4. Click **Create source** and **copy the key now**. It is shown once only: Observa stores just a fingerprint of it. If you lose it, open the source, click **New key**, and revoke the old one.

The page also shows a ready-to-paste snippet with your key and endpoint filled in.

---

## 2. Install

The SDK is not on the public npm registry yet. Install it from its file on the
[GitHub release](https://github.com/the1zozz/observa-sdks/releases):

```sh
npm install https://github.com/the1zozz/observa-sdks/releases/download/0.3.0/observa-web-0.7.0.tgz
```

To build it yourself from this repository: `cd web && npm install && npm pack`, then
`npm install <path>/web/observa-web-0.7.0.tgz` in your app.

It has no dependencies, is about 7 KB gzipped, and ships as an ES module with TypeScript types.

For a page with no build tool, one `<script>` tag is enough: see [Plain HTML](#plain-html-no-build-tool).

---

## 3. Initialise

Call `observa.init` **once**, as early as possible, in the browser. From then on, page views and clicks are recorded automatically.

### Angular (standalone, 17+)

Keep the settings in the environment files, so each build uses its own key:

```ts
// src/environments/environment.ts (development)
export const environment = {
  observa: { key: 'pk_dev_…', endpoint: 'http://localhost:8090' },
};

// src/environments/environment.prod.ts
export const environment = {
  observa: { key: 'pk_prod_…', endpoint: 'https://observa.yourcompany.com' },
};
```

```ts
// src/app/app.config.ts
import { ApplicationConfig, isDevMode, provideAppInitializer } from '@angular/core';
import { observa } from '@observa/web';
import { environment } from '../environments/environment';

export const appConfig: ApplicationConfig = {
  providers: [
    // …your other providers
    provideAppInitializer(() => observa.init({ ...environment.observa, debug: isDevMode() })),
  ],
};
```

With Angular SSR, initialise only in the browser: wrap the call in `if (isPlatformBrowser(inject(PLATFORM_ID))) { … }`.

### React (Vite or Create React App)

```ts
// src/main.tsx
import { observa } from '@observa/web';

observa.init({
  key: import.meta.env.VITE_OBSERVA_KEY,
  endpoint: import.meta.env.VITE_OBSERVA_ENDPOINT,
  debug: import.meta.env.DEV,
});

// …then render your app as usual
```

```sh
# .env.development
VITE_OBSERVA_KEY=pk_dev_…
VITE_OBSERVA_ENDPOINT=http://localhost:8090
```

### Next.js (App Router)

The SDK needs `window`, so initialise it in a client component and render that component once in the root layout:

```tsx
// app/observa.tsx
'use client';
import { useEffect } from 'react';
import { observa } from '@observa/web';

export function Observa() {
  useEffect(() => {
    observa.init({
      key: process.env.NEXT_PUBLIC_OBSERVA_KEY!,
      endpoint: process.env.NEXT_PUBLIC_OBSERVA_ENDPOINT!,
    });
  }, []);
  return null;
}

// app/layout.tsx: add <Observa /> inside <body>
```

### Vue 3

```ts
// src/main.ts
import { createApp } from 'vue';
import { observa } from '@observa/web';
import App from './App.vue';

observa.init({ key: import.meta.env.VITE_OBSERVA_KEY, endpoint: import.meta.env.VITE_OBSERVA_ENDPOINT });
createApp(App).mount('#app');
```

### Plain HTML (no build tool)

One tag in the page's `<head>`. It starts by itself:

```html
<script src="https://cdn.jsdelivr.net/gh/the1zozz/observa-sdks@0.3.0/web/browser/observa.js"
        data-key="pk_prod_…" data-endpoint="https://observa.yourcompany.com"></script>
```

Then, from any script on the page:

```html
<button onclick="observa.track('booking.requested', { room: 'sea-view' })">Request a room</button>
<script>
  // after sign-in
  observa.identify('1042');
</script>
```

- **The version is in the address** (`@0.3.0`), so the file never changes under you. To update, change the number.
- **Settings as attributes:** `data-debug="true"`, `data-auto-page-views="false"`, `data-auto-clicks="false"`, and
  `data-visitor-hosts="api.example.com, *.internal.example.com"`.
- **To start it yourself,** leave out `data-key` and call `observa.init({ key, endpoint, … })`. Everything the npm
  package exports is on `window.Observa`, and the page's client is `window.observa`.
- **Tag managers** (Google Tag Manager and others) take the same tag in a custom HTML tag.
- **Your own copy:** the file is `browser/observa.js` in the npm package and on the GitHub release. Serve it from
  your own site if your security policy allows scripts only from it.

---

## 4. Identify users

Before sign-in, a visitor is anonymous: the SDK gives each browser a random `anonymousId`. When the user signs in, tell Observa who they are, so this device's earlier and later activity count for that user. On sign-out, reset.

```ts
// after a successful sign-in
observa.identify(user.id, { plan: user.plan, role: user.role });

// on sign-out
observa.reset();
```

| Call | What it does |
|---|---|
| `identify(userId, traits?)` | Remembers the user id on this device, adds it to every later event, and sends a `$identify` event carrying both ids, which is the link between the anonymous visitor and the user. `traits` are optional facts about the user. |
| `reset()` | Forgets the user and starts a new anonymous id and session, so the next person on this device is not mixed up with the last. |

Use your **stable internal user id** (a database id), not an email or phone number: those can change, and they are personal data (see [Privacy](#privacy)).

---

### Record consent

Before Observa may send someone marketing on a channel, they must have agreed to it, and you must be able to show
when and to what. Call `consent` where they decide, with the exact text they saw:

```ts
// a checkbox "Send me offers by SMS" was ticked, "by email" was not
observa.consent({ sms: true, email: false }, { text: 'Send me offers by SMS', form: 'checkout' });
```

Observa keeps every decision with its evidence (time, text, form, page, network, device). The newest decision per
channel counts; channels you leave out keep their current consent. Withdrawing works the same way with `false`.

### Link your servers' events to the visitor

Add the visitor header to your app's calls to your own API. Your servers' Observa SDKs then record their events and
errors on the same visitor and visit, and sign-in moves them to the person. The header carries two random ids, never
the user id.

```ts
// fetch
fetch('/api/bookings', { headers: { ...observa.visitorHeaders('/api/bookings'), 'Content-Type': 'application/json' } });

// Angular: an HttpInterceptorFn
export const observaVisitor: HttpInterceptorFn = (req, next) =>
  next(req.clone({ setHeaders: observa.visitorHeaders(req.url) }));
```

If your API is on another origin, list it in `visitorHosts` and allow the `Observa-Visitor` request header in the API's
CORS settings.

## 5. Track your own events

Page views and clicks come for free. The events that matter for your business you send yourself:

```ts
observa.track('product.viewed', { productId: 14, price: 900, currency: 'EGP' });
observa.track('cart.item_added', { productId: 14, quantity: 2 });
observa.track('order.created', { orderId: 'O-1042', total: 1800 }, { attributes: { channel: 'web' } });
```

`track(type, body?, options?)`:

| Argument | What it is | Example |
|---|---|---|
| `type` | The event's name (rules below) | `'order.created'` |
| `body` | The event's data: any JSON object | `{ orderId: 'O-1042', total: 1800 }` |
| `options.attributes` | Small tags for filtering and grouping | `{ channel: 'web', campaign: 'ramadan' }` |

**Naming rules for `type`:**

- lowercase letters, digits and `_`, in at least two parts joined by dots: `object.action`
- valid: `product.viewed`, `user.logged_in`, `checkout.step_completed`
- not valid: `Order Created`, `orderCreated`, `order`, `order-created`
- names starting with `$` are reserved for Observa (`$identify`)

An invalid name is refused in the browser, is counted in `observa.stats().rejected`, and is explained in the console when `debug` is on.

**Good practice:**

- Name the thing and what happened to it, in the past tense: `invoice.paid`, not `pay_invoice`.
- Keep one name per business fact, and put the variations in `body`: `product.viewed` with `{ source: 'search' }`, not `product.viewed_from_search`.
- Send ids and numbers, not whole objects. One event can be at most 32 KB.

### Track a button without writing code

Give the element a `data-observa-event` attribute. Any other `data-observa-*` attribute becomes a field in the event's body:

```html
<button data-observa-event="report.exported" data-observa-format="csv">Export CSV</button>
<!-- sends report.exported with body { format: "csv" } -->
```

---

## Automatic tracking

| Event | When | Body |
|---|---|---|
| `page.viewed` | On load, and on every route change in single-page apps (Angular, React Router, Vue Router, Next.js) | `path`, `title`, `referrer` |
| `element.clicked` | A click on a link, button or `role="button"` element | `tag`, `text` (first 80 characters), `label` (aria-label), `id`, `href` |

Every event also carries a `context`: page path and title, browser language, user agent, screen size, time zone and SDK version.

**Where the visit came from** is recorded once per visit and sent with each of its events (0.4.0):

| In `context` | Taken from | Example |
|---|---|---|
| `campaign` | The `utm_source`, `utm_medium`, `utm_campaign`, `utm_term`, `utm_content` tags of the landing URL. A Google Ads `gclid` counts as `google` / `cpc` | `{ "source": "facebook", "medium": "paid_social", "name": "ramadan_sale" }` |
| `referrer` | The page of another site that linked here, without its query | `https://www.google.com/search` |

A link with new tags during the same visit replaces the campaign (the last click counts). Tag your links, e.g.
`https://shop.example/?utm_source=newsletter&utm_medium=email&utm_campaign=eid`, and Observa can show which source,
medium and campaign brought each visit, and how those visitors convert.

**Keep an area out of click tracking,** for example a payment form:

```html
<div data-observa-ignore>
  …nothing inside here is click-tracked…
</div>
```

**Turn automatic tracking off** with `autoPageViews: false` or `autoClicks: false`. You can still record page views yourself with `observa.page({ section: 'help' })`.

---

## Configuration

Everything you can pass to `observa.init({ … })`:

| Option | Required | Default | What it does |
|---|---|---|---|
| `key` | **yes** | | The source's publishable key from the portal. It decides which workspace and source the events go to. |
| `endpoint` | **yes** | | Observa's base address, with no path: `https://observa.yourcompany.com`. The SDK adds `/ingest/v1/client/events`. |
| `autoPageViews` | no | `true` | Send `page.viewed` on load and on every route change. |
| `autoClicks` | no | `true` | Send `element.clicked` for links and buttons, or the name in `data-observa-event`. |
| `flushIntervalMs` | no | `5000` | How often the queue is sent, in milliseconds. A full batch is sent immediately. |
| `maxBatchSize` | no | `20` | Events per request, from 1 to 100. |
| `maxQueueSize` | no | `1000` | Events held in memory while offline or while Observa is unreachable. Beyond this the oldest are dropped. |
| `sessionTimeoutMs` | no | `1800000` (30 min) | Inactivity after which a new session starts. |
| `debug` | no | `false` | Log what the SDK does to the browser console, including why the server refused an event. Turn it on in development only. |
| `visitorHosts` | no | `[]` | Your API's hosts on other origins (`api.example.com`, `*.example.com`) that `visitorHeaders(url)` adds the visitor header for. The page's own origin always gets it. |

For tests and unusual setups only:

| Option | What it does |
|---|---|
| `transport` | Replace the network layer, for example to send through your own proxy. Must never throw. |
| `storage` | Replace where the anonymous id, user id and session are kept (default: `localStorage`, falling back to memory). `memoryStorage()` is exported for cookie-less setups. |
| `now` | Replace the clock (epoch milliseconds), for tests. |

### The whole API

| Method | Purpose |
|---|---|
| `observa.init(config)` | Start. Call once; a second call is ignored. |
| `observa.track(type, body?, { attributes? }?)` | Record a business event. |
| `observa.page(properties?)` | Record a page view yourself. |
| `observa.identify(userId, traits?)` | Link this device to a signed-in user. |
| `observa.reset()` | Forget the user; new anonymous id and session. |
| `observa.visitorHeaders(url?)` | `{ 'Observa-Visitor': '<anonymousId>;<sessionId>' }` for your own servers, `{}` for other sites. |
| `observa.visitorHeader()` | The header's value, or `null` before `init`. |
| `observa.flush()` | Send one batch now. Returns a promise that never rejects. |
| `observa.stats()` | `{ queued, sent, rejected, dropped, retried }`, for checking and debugging: see [Check it works](#check-it-works). |
| `observa.shutdown()` | Stop timers and listeners, and send what is queued. Rarely needed. |

No method ever throws: tracking must never break your app.

---

## Environments and keys

- **One source per environment.** Create "Web shop (dev)", "Web shop (staging)" and "Web shop (prod)", each with its own key and allowed sites. Test events then never mix with real customer data.
- **Keep keys in environment files**, not in components, as in the examples above.
- **Allowed sites must match exactly:** `https://shop.example.com` and `https://www.shop.example.com` are different sites. List every address the app is served from, including `http://localhost:<port>` on development keys.
- **Rotate a key** without losing events:
  1. On the source, click **New key**.
  2. Deploy your app with the new key.
  3. Revoke the old key.

  Observa stops accepting a revoked key within 30 seconds.
- **Server-side use** (from your backend, not a browser): use a **secret key** (`sk_…`) and a server SDK, such as the [Java SDK](../java/README.md). A publishable key is refused at the server door, and a secret key in a browser is refused too.
- **Limits:** each key may send 50 requests per second (bursts up to 100), and each company 200 per second (bursts up to 400). The SDK batches events, so a normal app stays far below this; over the limit, Observa answers `429` and the SDK retries.

---

## Check it works

1. Start your app with `debug: true` and open the browser's developer tools.
2. **Console:** `[observa]` lines explain anything dropped, refused or retried.
3. **Network tab:** filter by `events`. Every 5 seconds there is a `POST …/ingest/v1/client/events` answered with **202** and a body like `{"accepted":3,"rejected":0,"errors":[]}`.
4. **Portal:** open **Sources → your source**. Your events appear under **Live events** within a few seconds. Click one to see its body.
5. At any time, run `observa.stats()` in the console, for example `{ queued: 0, sent: 42, rejected: 0, dropped: 0, retried: 0 }`. With npm, the SDK is in the page's code, so expose it first: `window.observa = observa`. The script tag does that for you.

| Counter | Counts |
|---|---|
| `queued` | Events waiting to be sent |
| `sent` | Events Observa accepted |
| `rejected` | Events refused and never resent: a bad name, over 32 KB, data that can't be written, or refused by Observa |
| `dropped` | Events lost because the queue was full while Observa could not be reached: the oldest go first |
| `retried` | Times a batch waited to be tried again: no network, 408, 429 or 5xx |

---

## Troubleshooting

| What you see | Why | Fix |
|---|---|---|
| No requests at all | `init` was never called, or it threw because `key` or `endpoint` is missing | Check the init code runs in the browser. With `debug: true`, the console says `init failed`. |
| `[observa] a secret key (sk_…) must never be in a web page` | The page has the server's key | Use the source's publishable key (`pk_…`). Rotate the secret key if the page was published: anyone could have read it. |
| **401** `OBS-ING-4011` | Unknown or revoked key, or a typo | Copy the key again from the portal, or create a new one. The console says so once, even without `debug`. |
| **403** `OBS-ING-4031` | The page's address is not in the key's allowed sites | Add the exact address (scheme, host, port) to a key for this source. The console says so once. |
| **429** `OBS-ING-4291` | Over the rate limit | Normal during short bursts; the SDK retries by itself. If it persists, raise `flushIntervalMs` so batches are fewer, or ask for a higher limit. |
| **202** but `rejected` > 0 | Some events were refused one by one, for example a bad name | With `debug: true`, the console names each event and the reason. |
| `rejected` grows | An invalid event name, an event over 32 KB, or Observa refused the events | Fix the name or size; with `debug: true` the console explains which. |
| `dropped` grows | The queue filled up while Observa could not be reached | Check the network tab for failing requests. Raise `maxQueueSize` only if the app often runs offline. |
| Events show the wrong source | The app uses another app's key | Each app and environment needs its own source and key. |
| Page views missing on route changes | The router changes the page without the browser History API | Set `autoPageViews: false` and call `observa.page()` in your router's navigation hook. |

The request is a plain `text/plain` POST, so browsers send no CORS preflight; you never need to configure CORS for Observa.

---

## Reference

### What is sent (envelope v1)

```json
{
  "key": "pk_prod_…",
  "sentAt": "2026-09-30T07:33:26.100Z",
  "events": [{
    "id": "8b0e…",
    "type": "order.created",
    "occurredAt": "2026-09-30T07:33:21.512Z",
    "platform": "WEB",
    "sessionId": "c41e…",
    "subject": { "userId": "1042", "anonymousId": "bafe…" },
    "body": { "orderId": "O-1042", "total": 1800 },
    "attributes": { "channel": "web" },
    "context": {
      "page": { "path": "/checkout", "title": "Checkout" },
      "locale": "ar-EG", "timezone": "Africa/Cairo",
      "screen": { "width": 1440, "height": 900 },
      "library": { "name": "@observa/web", "version": "0.7.0" }
    }
  }]
}
```

`sentAt` lets Observa correct a device whose clock is wrong. `id` makes retries safe: an event that arrives twice is stored once.

### Guarantees

Every behaviour below is covered by the SDK's tests (the [SDK contract](../CONTRACT.md), §3 to §7).

- **Never breaks your app:** every call catches its own errors. After `shutdown()` calls are ignored.
- **Refuses a secret key:** with an `sk_…` key the SDK stays off and says so in the console.
- **Bounded memory:** at most `maxQueueSize` events; the oldest are dropped and counted.
- **Batching:** sends every `flushIntervalMs`, or as soon as `maxBatchSize` events are queued. Each request stays
  under 512 KB, one request at a time, so events keep their order. `flush()` sends everything queued.
- **Page exit:** when the page is hidden or closed, what is queued goes with `sendBeacon`. Browsers allow about 64 KB
  in all for this, so what doesn't fit is kept in the browser's storage (at most 256 KB). The next page of the same
  site sends it first.
- **Retries** on network errors, 408, 429 and 5xx: 1 s doubling to 60 s, plus up to 20 % random jitter. On 429 and
  503 it waits at least the server's `Retry-After`. A batch Observa finds too large (413) is sent again in halves.
  Other 4xx answers count the batch as rejected, because resending cannot fix them. A refused key (401, 403) is shown
  once in the console.
- **Idempotent:** every event has its own id, kept through retries and page exits, so an event that arrives twice is
  stored once.

### Privacy

- The SDK never reads the values of inputs, text areas or selects.
- Query strings are removed from recorded URLs, because they often carry tokens or personal data.
- **Keep personal data out of `body`, `attributes` and traits**: no emails, phone numbers, national ids, addresses or card numbers. Send ids instead. A person's contact details come from your server, which is trusted (see the [Java SDK](../java/README.md)); in production only a server may set them.

### Server limits

| Limit | Value |
|---|---|
| Events per request | 500 (the SDK sends at most 100) |
| Request size | 512 KB |
| Event size | 32 KB |
| Event name | 128 characters |
| `userId` | 200 characters |
