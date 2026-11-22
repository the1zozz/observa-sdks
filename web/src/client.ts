import { browserStorage, browserTransport, isBrowser, uuid } from './platform.js';
import * as push from './push.js';
import type {
  Campaign, ConsentChannels, ConsentOptions, KeyValueStorage, ObservaConfig, ObservaStats, Properties, PushOptions, PushState, TrackedEvent,
  TrackOptions, Transport, TransportResponse,
} from './types.js';

export const SDK_VERSION = '0.7.0';

/** The header that tells your servers who the visitor is (the SDK contract, §9). */
export const VISITOR_HEADER = 'Observa-Visitor';

/**
 * Dotted lowercase names like `product.viewed`. A leading `$` marks reserved profile events, which may be a single
 * word (`$identify`) or dotted (`$profile.set`). Must match ClientEvent.TYPE_PATTERN on the server.
 */
const EVENT_TYPE = /^(\$[a-z][a-z0-9_]*(\.[a-z0-9_]+)*|[a-z][a-z0-9_]*(\.[a-z0-9_]+)+)$/;
const MAX_TYPE_LENGTH = 128;
const PATH = '/ingest/v1/client/events';
const MAX_EVENT_BYTES = 32 * 1024;
/** A request stays under 512 KB (contract §5), with room for the envelope. */
const MAX_REQUEST_BYTES = 512 * 1024 - 1024;
/**
 * Browsers give everything sent while a page closes about 64 KB in all (sendBeacon and keepalive fetch share it), so
 * page-exit beacons stay under that together. What doesn't fit is saved and sent on the next page.
 */
const MAX_BEACON_BYTES = 60 * 1024;
/** At most this much is saved for the next page, so storage stays small. */
const MAX_SAVED_BYTES = 256 * 1024;
const MAX_BACKOFF_MS = 60_000;
const KEYS = { anonymousId: 'observa.aid', userId: 'observa.uid', session: 'observa.session', pending: 'observa.pending' } as const;

/**
 * A visit. Where it came from is taken when it starts: the campaign tags of the landing URL and the referring site.
 * A later link with campaign tags in the same visit replaces the campaign (the last click counts).
 */
interface Session {
  id: string;
  lastActivity: number;
  campaign?: Campaign;
  referrer?: string;
}

const UTM: Record<string, keyof Campaign> = {
  utm_source: 'source', utm_medium: 'medium', utm_campaign: 'name', utm_term: 'term', utm_content: 'content',
};
const MAX_TAG_LENGTH = 100;

/** A queued event and its size once written, in UTF-8 bytes. */
interface Entry {
  event: TrackedEvent;
  bytes: number;
}

type Settings = Required<Omit<ObservaConfig, 'transport' | 'storage' | 'now' | 'page' | 'visitorHosts'>> & { visitorHosts: string[] };

/**
 * Browser event client. Every public method swallows its own errors: tracking must never break the host app.
 * Events wait in a bounded in-memory queue and are sent in batches; failed batches are retried with backoff.
 */
export class ObservaClient {
  private settings: Settings | null = null;
  private transport: Transport = browserTransport;
  private storage: KeyValueStorage | null = null;
  private now: () => number = () => Date.now();
  private pageInfo: () => { href: string; referrer: string } = currentPage;

  private queue: Entry[] = [];
  private inFlight = false;
  /** The request on its way, so a flush can wait for it rather than skip it. */
  private sending: Promise<boolean> | null = null;
  private attempt = 0;
  private retryAt = 0;
  /** After a 413, the next requests carry at most this many events, until one is accepted. */
  private splitTo = 0;
  private closed = false;
  private warned = new Set<string>();
  private stats_: ObservaStats = { queued: 0, sent: 0, rejected: 0, dropped: 0, retried: 0 };

  private timer: ReturnType<typeof setInterval> | null = null;
  private teardown: Array<() => void> = [];
  private lastPath: string | null = null;

  init(config: ObservaConfig): void {
    this.guard('init', () => {
      if (this.settings || this.closed) {
        this.log(this.closed ? 'init after shutdown; ignoring' : 'init called twice; ignoring');
        return;
      }
      if (!config?.key || !config?.endpoint) {
        throw new Error('key and endpoint are required');
      }
      if (config.key.startsWith('sk_')) {
        // A secret key in a web page is a leak, and Observa refuses it from a browser anyway (contract §1).
        this.warnOnce('sk', 'a secret key (sk_…) must never be in a web page. Observa is off; use the source\'s '
          + 'publishable key (pk_…) here, and rotate the secret key if this page was published.');
        return;
      }
      this.settings = {
        key: config.key,
        endpoint: config.endpoint.replace(/\/+$/, ''),
        autoPageViews: config.autoPageViews ?? true,
        autoClicks: config.autoClicks ?? true,
        flushIntervalMs: config.flushIntervalMs ?? 5_000,
        maxBatchSize: Math.min(Math.max(config.maxBatchSize ?? 20, 1), 100),
        maxQueueSize: Math.max(config.maxQueueSize ?? 1_000, 1),
        sessionTimeoutMs: config.sessionTimeoutMs ?? 30 * 60_000,
        debug: config.debug ?? false,
        visitorHosts: (config.visitorHosts ?? []).map((h) => String(h).trim().toLowerCase()).filter(Boolean),
      };
      this.transport = config.transport ?? browserTransport;
      this.storage = config.storage ?? browserStorage();
      this.now = config.now ?? (() => Date.now());
      this.pageInfo = config.page ?? currentPage;

      this.restorePending();
      this.timer = setInterval(() => void this.flush(), this.settings.flushIntervalMs);
      if (isBrowser()) {
        this.listenForPageExit();
        if (this.settings.autoPageViews) this.listenForRouteChanges();
        if (this.settings.autoClicks) this.listenForClicks();
      }
    });
  }

  /**
   * Records a business event: `track('category.selected', { categoryId: 3 })`.
   * `body` is the event's data; `options.attributes` are small tags for filtering, like `{ channel: 'app' }`.
   */
  track(type: string, body: Properties = {}, options: TrackOptions = {}): void {
    this.guard('track', () => this.enqueue(type, body, options.attributes));
  }

  /** Records a page view. Called automatically on route changes unless autoPageViews is false. */
  page(properties: Properties = {}): void {
    this.guard('page', () => {
      const page = isBrowser()
        ? { path: location.pathname, title: document.title, referrer: stripQuery(document.referrer) }
        : {};
      this.enqueue('page.viewed', { ...page, ...properties });
    });
  }

  /**
   * Links later events to a known user, e.g. after login. Sends the reserved `$identify` event, which carries
   * both the user id and the device's anonymous id: the link that lets earlier anonymous activity count for the user.
   */
  identify(userId: string, traits: Properties = {}): void {
    this.guard('identify', () => {
      if (!userId) throw new Error('userId is required');
      this.store()?.set(KEYS.userId, String(userId));
      this.enqueue('$identify', traits);
    });
  }

  /**
   * Records what the person agreed to, per channel: `consent({ email: true, sms: false }, { text, form })`.
   * Pass the exact text they were shown and the form's name: Observa keeps both as evidence, with the time, page,
   * network and device. A channel left out keeps its current consent.
   */
  consent(channels: ConsentChannels, options: ConsentOptions = {}): void {
    this.guard('consent', () => {
      const given = Object.fromEntries(Object.entries(channels ?? {}).filter(([, v]) => typeof v === 'boolean'));
      if (Object.keys(given).length === 0) throw new Error('consent needs at least one channel set to true or false');
      this.enqueue('$consent.update', {
        channels: given,
        ...(options.topic ? { topic: options.topic } : {}),
        ...(options.text ? { text: options.text } : {}),
        ...(options.form ? { form: options.form } : {}),
      });
    });
  }

  /**
   * Web push: asks for permission (call it from a click, browsers require it), subscribes this browser with the
   * workspace's key and records the subscription and push consent in Observa. The site serves Observa's service
   * worker, `observa-sw.js` from this package, at its root. Resolves true when notifications will arrive; never
   * rejects.
   */
  async subscribePush(options: PushOptions = {}): Promise<boolean> {
    try {
      return await push.subscribe(this.pushContext(), options);
    } catch (e) {
      this.log('subscribePush failed', e);
      return false;
    }
  }

  /** Stops notifications in this browser. Resolves true when it was subscribed; never rejects. */
  async unsubscribePush(options: PushOptions = {}): Promise<boolean> {
    try {
      return await push.unsubscribe(this.pushContext(), options);
    } catch (e) {
      this.log('unsubscribePush failed', e);
      return false;
    }
  }

  /** Whether notifications can be offered, were refused, or already arrive in this browser. */
  async pushState(options: PushOptions = {}): Promise<PushState> {
    try {
      return await push.pushState(options);
    } catch (e) {
      this.log('pushState failed', e);
      return 'unsupported';
    }
  }

  /**
   * The visitor header's value for a request to your own servers, `<anonymousId>;<sessionId>`, or null before
   * `init`. Your servers' SDKs read it, so their events and errors land on this visitor and in this visit. It carries
   * only those two random ids: never the user id.
   */
  visitorHeader(): string | null {
    try {
      if (!this.settings) return null;
      return `${this.anonymousId()};${this.touchSession().id}`;
    } catch (e) {
      this.log('visitorHeader failed', e);
      return null;
    }
  }

  /**
   * Headers to add to a request to `url`: `{ 'Observa-Visitor': … }` when `url` is this page's origin (or a relative
   * path) or a host listed in `visitorHosts`, else `{}`, so other companies' sites never get it.
   * `fetch(url, { headers: { ...observa.visitorHeaders(url), … } })`.
   */
  visitorHeaders(url?: string): Record<string, string> {
    try {
      const value = this.visitorHeader();
      if (!value || (url !== undefined && !this.isOwnServer(url))) return {};
      return { [VISITOR_HEADER]: value };
    } catch (e) {
      this.log('visitorHeaders failed', e);
      return {};
    }
  }

  /** Forgets the user and starts a fresh anonymous identity and session, e.g. on logout. */
  reset(): void {
    this.guard('reset', () => {
      const store = this.store();
      store?.remove(KEYS.userId);
      store?.remove(KEYS.anonymousId);
      store?.remove(KEYS.session);
    });
  }

  /**
   * Sends what is queued now, batch after batch. Resolves when the queue is empty, or when Observa can't be reached
   * and the rest waits for its retry; never rejects.
   */
  async flush(): Promise<void> {
    try {
      for (let i = 0; i < 1_000 && (this.queue.length > 0 || this.sending); i++) {
        if (this.sending) {
          // Another flush is sending: wait for its answer, then carry on with what is left.
          if (!(await this.sending)) break;
          continue;
        }
        if (!(await this.sendNextBatch())) break;
      }
    } catch (e) {
      this.log('flush failed', e);
    }
  }

  stats(): ObservaStats {
    return { ...this.stats_, queued: this.queue.length };
  }

  /** Stops timers and listeners and hands anything queued to the beacon. Later calls are ignored. */
  shutdown(): void {
    this.guard('shutdown', () => {
      if (this.timer) clearInterval(this.timer);
      this.timer = null;
      this.beaconAll();
      this.teardown.forEach((undo) => undo());
      this.teardown = [];
      this.closed = true;
    });
  }

  // ---- queue ----

  private enqueue(type: string, body: Properties, attributes?: Properties): void {
    if (this.closed) return;
    if (typeof type !== 'string' || type.length > MAX_TYPE_LENGTH || !EVENT_TYPE.test(type)) {
      this.reject(1, `invalid event type "${type}" (use dotted lowercase, e.g. product.viewed)`);
      return;
    }
    const session = this.touchSession();
    const event: TrackedEvent = {
      id: uuid(),
      type,
      occurredAt: new Date(this.now()).toISOString(),
      platform: 'WEB',
      sessionId: session.id,
      subject: {
        userId: this.store()?.get(KEYS.userId) ?? undefined,
        anonymousId: this.anonymousId(),
      },
      body,
      attributes,
      context: this.context(session),
    };
    let size: number;
    try {
      size = utf8Length(JSON.stringify(event));
    } catch {
      this.reject(1, `event "${type}" has data that cannot be serialised`);
      return;
    }
    if (size > MAX_EVENT_BYTES) {
      this.reject(1, `event "${type}" is larger than ${MAX_EVENT_BYTES} bytes: send ids and the values you need`);
      return;
    }
    this.queue.push({ event, bytes: size });
    this.trimQueue();
    if (this.settings && this.queue.length >= this.settings.maxBatchSize) void this.flush();
  }

  /** One request: true when it got an answer that lets the next batch go now. */
  private sendNextBatch(): Promise<boolean> {
    if (this.sending) return this.sending;
    const settings = this.settings;
    if (!settings || this.inFlight || this.queue.length === 0 || this.now() < this.retryAt) return Promise.resolve(false);
    this.sending = this.sendOne(settings).finally(() => {
      this.sending = null;
    });
    return this.sending;
  }

  private async sendOne(settings: Settings): Promise<boolean> {

    const limit = this.splitTo > 0 ? Math.min(this.splitTo, settings.maxBatchSize) : settings.maxBatchSize;
    const batch = this.take(limit, MAX_REQUEST_BYTES);
    const events = batch.map((e) => e.event);
    this.inFlight = true;
    try {
      let answer: TransportResponse;
      try {
        answer = await this.transport.send({ url: this.url(), body: this.body(events) });
      } catch (e) {
        answer = { status: 0 };
        this.log('transport failed', e);
      }
      const { status, body } = answer;
      if (status >= 200 && status < 300) {
        const rejected = Math.min(batch.length, this.reportRejections(events, body));
        this.stats_.sent += batch.length - rejected;
        this.stats_.rejected += rejected;
        this.attempt = 0;
        this.retryAt = 0;
        this.splitTo = 0;
        return true;
      }
      if (isRetryable(status)) {
        this.requeue(batch);
        this.attempt++;
        this.stats_.retried++;
        const after = status === 429 || status === 503 ? (answer.retryAfter ?? 0) * 1_000 : 0;
        this.retryAt = this.now() + Math.max(backoff(this.attempt), after);
        this.log(`send failed (status ${status}); retry #${this.attempt} in ${this.retryAt - this.now()} ms`);
        return false;
      }
      if (status === 413 && batch.length > 1) {
        // Too large for the server: the same events again, in halves.
        this.requeue(batch);
        this.splitTo = Math.ceil(batch.length / 2);
        this.log(`batch too large; sending ${this.splitTo} events at a time`);
        return true;
      }
      this.reject(batch.length, `Observa refused a batch of ${batch.length} (status ${status})`);
      if (status === 401 || status === 403) {
        this.warnOnce('auth', `Observa refused the key (status ${status}): check it is the source's publishable key `
          + '(pk_…) and that this site\'s address is one of the key\'s allowed origins. Events are not being stored.');
      }
      this.splitTo = 0;
      return true;
    } finally {
      this.inFlight = false;
    }
  }

  /** The oldest events that fit: at most `limit`, and under `maxBytes` once written. */
  private take(limit: number, maxBytes: number): Entry[] {
    let bytes = 64;
    let n = 0;
    while (n < this.queue.length && n < limit) {
      const size = this.queue[n].bytes + 1;
      if (n > 0 && bytes + size > maxBytes) break;
      bytes += size;
      n++;
    }
    return this.queue.splice(0, n);
  }

  /**
   * The server accepts a batch but may refuse single events in it. Returns how many were refused and, in debug
   * mode, says why, so a developer sees e.g. `"Order Created" rejected: type must be a dotted lowercase name`.
   */
  private reportRejections(batch: TrackedEvent[], body?: string): number {
    if (!body) return 0;
    let outcome: { rejected?: number; errors?: Array<{ index: number; reasons?: Array<{ field: string; message: string }> }> };
    try {
      outcome = JSON.parse(body);
    } catch {
      return 0;
    }
    const rejected = Number(outcome?.rejected) || 0;
    if (rejected > 0 && this.settings?.debug) {
      for (const error of outcome.errors ?? []) {
        const reasons = (error.reasons ?? []).map((r) => `${r.field || 'event'} ${r.message}`).join('; ');
        this.log(`rejected "${batch[error.index]?.type ?? '?'}" (#${error.index}): ${reasons}`);
      }
      if ((outcome.errors?.length ?? 0) < rejected) {
        this.log(`…and ${rejected - (outcome.errors?.length ?? 0)} more rejected events`);
      }
    }
    return rejected;
  }

  private requeue(batch: Entry[]): void {
    this.queue.unshift(...batch);
    this.trimQueue();
  }

  private trimQueue(): void {
    const max = this.settings?.maxQueueSize ?? 1_000;
    if (this.queue.length > max) {
      const overflow = this.queue.length - max;
      this.queue.splice(0, overflow);
      this.drop(overflow, 'queue full; dropped oldest events');
    }
  }

  private drop(count: number, reason: string): void {
    this.stats_.dropped += count;
    this.log(`dropped ${count}: ${reason}`);
  }

  private reject(count: number, reason: string): void {
    this.stats_.rejected += count;
    this.log(`rejected ${count}: ${reason}`);
  }

  /**
   * The page is being hidden or closed: what is queued goes with sendBeacon, which the browser delivers after the page
   * is gone. The browser allows about 64 KB for all of it, so the beacons stay under that together; the rest is saved
   * and sent by the next page (same ids, so Observa stores each event once).
   */
  private beaconAll(): void {
    const settings = this.settings;
    if (!settings || this.inFlight) return;
    let room = MAX_BEACON_BYTES;
    while (this.transport.beacon && this.queue.length > 0) {
      const batch = this.take(settings.maxBatchSize, room);
      const bytes = batch.reduce((n, e) => n + e.bytes + 1, 64);
      if (bytes > room) {
        this.queue.unshift(...batch);
        break;
      }
      let accepted = false;
      try {
        accepted = this.transport.beacon({ url: this.url(), body: this.body(batch.map((e) => e.event)) });
      } catch (e) {
        this.log('beacon failed', e);
      }
      if (!accepted) {
        this.queue.unshift(...batch);
        break;
      }
      room -= bytes;
      this.stats_.sent += batch.length;
    }
    this.savePending();
  }

  /** Keeps what the beacons could not carry for the next page, newest first when space runs out. */
  private savePending(): void {
    const store = this.store();
    if (!store || !this.settings) return;
    if (this.queue.length === 0) {
      store.remove(KEYS.pending);
      return;
    }
    const kept: TrackedEvent[] = [];
    let bytes = 0;
    for (let i = this.queue.length - 1; i >= 0 && bytes + this.queue[i].bytes <= MAX_SAVED_BYTES; i--) {
      kept.unshift(this.queue[i].event);
      bytes += this.queue[i].bytes;
    }
    store.set(KEYS.pending, JSON.stringify({ key: this.settings.key, events: kept }));
  }

  /** Events a previous page saved at exit go first, for the same key only. */
  private restorePending(): void {
    const store = this.store();
    const raw = store?.get(KEYS.pending);
    if (!raw) return;
    store?.remove(KEYS.pending);
    let saved: { key?: string; events?: TrackedEvent[] };
    try {
      saved = JSON.parse(raw);
    } catch {
      return;
    }
    if (saved?.key !== this.settings?.key || !Array.isArray(saved.events)) return;
    const restored = saved.events
      .filter((e) => e && typeof e.id === 'string' && typeof e.type === 'string')
      .map((event) => ({ event, bytes: utf8Length(JSON.stringify(event)) }));
    this.queue.unshift(...restored);
    this.trimQueue();
    this.log(`sending ${restored.length} events saved by the previous page`);
  }

  private url(): string {
    return `${this.settings!.endpoint}${PATH}`;
  }

  /** `sentAt` is stamped at each attempt so the server can correct this device's clock skew. */
  private body(events: TrackedEvent[]): string {
    return JSON.stringify({ key: this.settings!.key, sentAt: new Date(this.now()).toISOString(), events });
  }

  // ---- identity ----

  private pushContext(): push.PushContext {
    if (!this.settings) throw new Error('call init first');
    return {
      endpoint: this.settings.endpoint, key: this.settings.key, anonymousId: this.anonymousId(),
      userId: this.store()?.get(KEYS.userId) ?? undefined,
    };
  }

  private store(): KeyValueStorage | null {
    if (!this.storage) this.storage = isBrowser() ? browserStorage() : null;
    return this.storage;
  }

  private anonymousId(): string {
    const store = this.store();
    let id = store?.get(KEYS.anonymousId);
    if (!id) {
      id = uuid();
      store?.set(KEYS.anonymousId, id);
    }
    return id;
  }

  private touchSession(): Session {
    const store = this.store();
    const timeout = this.settings?.sessionTimeoutMs ?? 30 * 60_000;
    const now = this.now();
    let session: Session | null = null;
    try {
      session = JSON.parse(store?.get(KEYS.session) ?? 'null');
    } catch {
      session = null;
    }
    const page = this.readPage();
    const campaign = campaignOf(page.href);
    if (!session || now - session.lastActivity > timeout) {
      session = { id: uuid(), lastActivity: now, campaign, referrer: externalReferrer(page.referrer, page.href) };
    } else if (campaign) {
      session.campaign = campaign;
    }
    session.lastActivity = now;
    store?.set(KEYS.session, JSON.stringify(session));
    return session;
  }

  private readPage(): { href: string; referrer: string } {
    try {
      return this.pageInfo();
    } catch {
      return { href: '', referrer: '' };
    }
  }

  private context(session: Session): Properties {
    const context: Properties = { library: { name: '@observa/web', version: SDK_VERSION } };
    if (session.campaign) context['campaign'] = session.campaign;
    if (session.referrer) context['referrer'] = session.referrer;
    if (isBrowser()) {
      context['page'] = { path: location.pathname, title: document.title };
      context['locale'] = navigator.language;
      context['userAgent'] = navigator.userAgent;
      context['screen'] = { width: screen.width, height: screen.height };
      try {
        context['timezone'] = Intl.DateTimeFormat().resolvedOptions().timeZone;
      } catch {
        /* optional */
      }
    }
    return context;
  }

  // ---- browser hooks ----

  private listenForPageExit(): void {
    const onHidden = () => {
      if (document.visibilityState === 'hidden') {
        this.beaconAll();
      } else {
        // Back on the page: what was saved is still queued here, so the next page must not send it again.
        this.store()?.remove(KEYS.pending);
      }
    };
    const onPageHide = () => this.beaconAll();
    document.addEventListener('visibilitychange', onHidden);
    window.addEventListener('pagehide', onPageHide);
    this.teardown.push(() => {
      document.removeEventListener('visibilitychange', onHidden);
      window.removeEventListener('pagehide', onPageHide);
    });
  }

  /** SPA routers (Angular, React Router, Vue Router) navigate with history.pushState / replaceState. */
  private listenForRouteChanges(): void {
    const onChange = () => {
      // Let the router render and set the title before recording the view.
      setTimeout(() => {
        if (location.pathname !== this.lastPath) {
          this.lastPath = location.pathname;
          this.page();
        }
      }, 0);
    };
    const original = { push: history.pushState, replace: history.replaceState };
    history.pushState = function (this: History, ...args: Parameters<History['pushState']>) {
      const result = original.push.apply(this, args);
      onChange();
      return result;
    };
    history.replaceState = function (this: History, ...args: Parameters<History['replaceState']>) {
      const result = original.replace.apply(this, args);
      onChange();
      return result;
    };
    window.addEventListener('popstate', onChange);
    this.teardown.push(() => {
      history.pushState = original.push;
      history.replaceState = original.replace;
      window.removeEventListener('popstate', onChange);
    });
    onChange();
  }

  /**
   * Captures clicks on links and buttons. An element can name its own event with
   * `data-observa-event="product.reserved"`, add properties with `data-observa-<prop>="value"`,
   * or opt out with `data-observa-ignore`. Input values are never read.
   */
  private listenForClicks(): void {
    const onClick = (e: Event) => {
      try {
        const target = e.target instanceof Element ? e.target : null;
        const el = target?.closest('[data-observa-event],a,button,[role="button"]');
        if (!el || el.closest('[data-observa-ignore]')) return;

        const props: Properties = {};
        for (const attr of Array.from(el.attributes)) {
          if (attr.name.startsWith('data-observa-') && attr.name !== 'data-observa-event') {
            props[camel(attr.name.slice('data-observa-'.length))] = attr.value;
          }
        }
        const named = el.getAttribute('data-observa-event');
        if (named) {
          this.track(named, props);
          return;
        }
        this.track('element.clicked', {
          tag: el.tagName.toLowerCase(),
          text: (el.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 80),
          // Icon-only buttons carry their meaning in aria-label.
          label: el.getAttribute('aria-label')?.slice(0, 80) || undefined,
          id: el.id || undefined,
          href: el instanceof HTMLAnchorElement ? stripQuery(el.href) : undefined,
          ...props,
        });
      } catch (err) {
        this.log('click capture failed', err);
      }
    };
    document.addEventListener('click', onClick, { capture: true });
    this.teardown.push(() => document.removeEventListener('click', onClick, { capture: true }));
  }

  // ---- utilities ----

  private isOwnServer(url: string): boolean {
    const base = this.readPage().href || undefined;
    let target: URL;
    try {
      target = base ? new URL(url, base) : new URL(url);
    } catch {
      return false;
    }
    if (target.protocol !== 'https:' && target.protocol !== 'http:') return false;
    if (base && target.origin === new URL(base).origin) return true;
    const host = target.hostname.toLowerCase();
    return (this.settings?.visitorHosts ?? []).some((pattern) =>
      pattern.startsWith('*.') ? host.endsWith(pattern.slice(1)) : host === pattern,
    );
  }

  private guard(operation: string, fn: () => void): void {
    try {
      fn();
    } catch (e) {
      this.log(`${operation} failed`, e);
    }
  }

  /** A mistake that stops events from being stored: shown once even without debug, so it is never missed. */
  private warnOnce(kind: string, message: string): void {
    if (this.warned.has(kind)) return;
    this.warned.add(kind);
    try {
      if (typeof console !== 'undefined') console.warn(`[observa] ${message}`);
    } catch {
      /* never into the app */
    }
  }

  private log(message: string, detail?: unknown): void {
    if (this.settings?.debug && typeof console !== 'undefined') {
      console.debug(`[observa] ${message}`, detail ?? '');
    }
  }
}

function isRetryable(status: number): boolean {
  return status === 0 || status === 408 || status === 429 || status >= 500;
}

/** 1 s doubling to 60 s, plus up to 20 % random jitter (contract §6). */
export function backoff(attempt: number): number {
  const base = Math.min(MAX_BACKOFF_MS, 1_000 * 2 ** Math.min(20, attempt - 1));
  return base + Math.random() * base * 0.2;
}

/** The size of a string in UTF-8, as it goes on the wire. */
function utf8Length(text: string): number {
  if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(text).length;
  let bytes = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : c >= 0xd800 && c < 0xdc00 ? (i++, 4) : 3;
  }
  return bytes;
}

function currentPage(): { href: string; referrer: string } {
  return isBrowser() ? { href: location.href, referrer: document.referrer } : { href: '', referrer: '' };
}

/**
 * The campaign tags of a URL (`utm_source`, `utm_medium`, `utm_campaign`, `utm_term`, `utm_content`). A Google Ads
 * click id (`gclid`) without tags counts as source google, medium cpc. Only these values are kept from the query.
 */
export function campaignOf(href: string): Campaign | undefined {
  let params: URLSearchParams;
  try {
    params = new URL(href).searchParams;
  } catch {
    return undefined;
  }
  const campaign: Campaign = {};
  for (const [param, field] of Object.entries(UTM)) {
    const value = params.get(param)?.trim();
    if (value) campaign[field] = value.slice(0, MAX_TAG_LENGTH);
  }
  if (!campaign.source && params.has('gclid')) {
    campaign.source = 'google';
    campaign.medium = campaign.medium ?? 'cpc';
  }
  return Object.keys(campaign).length > 0 ? campaign : undefined;
}

/** The referring page without its query, when it is another site (moving inside the app is not a referrer). */
export function externalReferrer(referrer: string, href: string): string | undefined {
  if (!referrer) return undefined;
  try {
    if (href && new URL(referrer).host === new URL(href).host) return undefined;
  } catch {
    return undefined;
  }
  return stripQuery(referrer) || undefined;
}

/** Query strings often carry tokens or personal data, so URLs are recorded without them. */
function stripQuery(url: string): string {
  if (!url) return '';
  try {
    const u = new URL(url);
    return `${u.origin}${u.pathname}`;
  } catch {
    return url.split(/[?#]/)[0];
  }
}

function camel(kebab: string): string {
  return kebab.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase());
}
