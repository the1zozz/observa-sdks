# Web SDK changelog

`@observa/web` is released with the Java SDK, from the same tag: its package (`observa-web-<version>.tgz`) and its
script-tag file (`observa.js`) are attached to the GitHub release, and jsDelivr serves the script at the tag. The web
SDK keeps its own version number.

## 0.7.0 · 22 November 2026 (release 0.3.0)

**One person across browser and server** (the SDK contract, §9):

- `observa.visitorHeaders(url)` gives `{ 'Observa-Visitor': '<anonymousId>;<sessionId>' }` for the page's own origin
  and the hosts in the new `visitorHosts` setting, and `{}` for any other site. `visitorHeader()` gives the value.
- Your servers' SDKs read it, so their events and errors land on this visitor and in this visit. It never carries the
  user id.

**A script tag for pages without a build tool:**

```html
<script src="https://cdn.jsdelivr.net/gh/the1zozz/observa-sdks@0.3.0/web/browser/observa.js"
        data-key="pk_…" data-endpoint="https://observa.example.com"></script>
```

It starts by itself from `data-key` and `data-endpoint`, and puts the client on `window.observa`.

**Every behaviour of the SDK contract, checked and tested** (OBS-360):

- **A secret key (`sk_…`) is refused:** the SDK stays off and says so in the console.
- **Requests stay under 512 KB,** whatever `maxBatchSize` is.
- **Page exit:** browsers allow about 64 KB in all for what a closing page sends. The beacons now stay under that
  together, and what doesn't fit is saved (at most 256 KB) and sent by the next page. Before, a large queue at page
  exit was lost.
- **`flush()` sends everything queued,** waiting for a send already on its way, not just one batch.
- **Answers as the contract says:**
  - 413 sends the batch again in halves;
  - 429 and 503 wait at least `Retry-After`;
  - backoff is 1 s doubling to 60 s, plus up to 20 % jitter;
  - a refused key (401, 403) is shown once in the console, even without `debug`.
- **Counters** (behaviour change):
  - `stats()` has a new `retried` counter;
  - invalid events, events over 32 KB (now measured in UTF-8 bytes) and batches Observa refuses count as `rejected`;
  - `dropped` now means only a full queue.
- **After `shutdown()`,** calls are ignored.

## 0.6.0 · 18 November 2026 (release 0.1.0)

The first public release: page views, clicks, `track`, `identify`, consent with its evidence, web push, campaign
tags and referrers, batching, retries and the beacon on page exit.
