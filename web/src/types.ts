export type Properties = Record<string, unknown>;

export interface ObservaConfig {
  /** Publishable key issued for this app, e.g. `pk_dev_mostore_…`. */
  key: string;
  /** Observa base URL, e.g. `http://localhost:8090`. */
  endpoint: string;
  /** Send `page.viewed` on load and on every SPA route change. Default true. */
  autoPageViews?: boolean;
  /** Send `element.clicked` for links and buttons, or the name in `data-observa-event`. Default true. */
  autoClicks?: boolean;
  /** How often the queue is sent. Default 5000 ms. */
  flushIntervalMs?: number;
  /** Events per request (server maximum is 100). Default 20. */
  maxBatchSize?: number;
  /** Events held in memory; the oldest are dropped beyond this. Default 1000. */
  maxQueueSize?: number;
  /** Inactivity after which a new session starts. Default 30 minutes. */
  sessionTimeoutMs?: number;
  /** Log SDK diagnostics to the console. Default false. */
  debug?: boolean;
  /**
   * Hosts of your own API on other origins that `visitorHeaders(url)` adds the visitor header for, e.g.
   * `['api.example.com', '*.example.com']`. The page's own origin always gets it; other sites never do.
   */
  visitorHosts?: string[];

  /** Advanced: replace the network layer (tests, custom proxies). */
  transport?: Transport;
  /** Advanced: replace persistent storage (tests, cookie-less setups). */
  storage?: KeyValueStorage;
  /** Advanced: clock in epoch ms (tests). */
  now?: () => number;
  /** Advanced: the page's address and referrer, read when a session starts (tests). Default: the browser's. */
  page?: () => { href: string; referrer: string };
}

/** Per channel: true when the person agrees to messages on it, false when they withdraw. */
export interface ConsentChannels {
  email?: boolean;
  sms?: boolean;
  whatsapp?: boolean;
  push?: boolean;
}

export interface ConsentOptions {
  /** The exact text the person agreed to, e.g. "Send me offers by SMS". Kept as evidence. */
  text?: string;
  /** Which form or screen, e.g. "checkout" or "preferences". */
  form?: string;
  /** A topic such as "offers" or "news"; without it the consent is for the channel as a whole. */
  topic?: string;
}

/** Web push in this browser. */
export interface PushOptions {
  /** Where the site serves Observa's service worker. Default `/observa-sw.js` (at the root, so it covers every page). */
  serviceWorker?: string;
}

/**
 * `unsupported`: no web push here (or iOS Safari outside a home-screen app); `denied`: the person blocked
 * notifications; `prompt`: not asked yet; `granted`: allowed but not subscribed; `subscribed`: notifications arrive.
 */
export type PushState = 'unsupported' | 'denied' | 'prompt' | 'granted' | 'subscribed';

/** Campaign tags of the link a visit came from (`utm_*` in the landing URL). */
export interface Campaign {
  source?: string;
  medium?: string;
  name?: string;
  term?: string;
  content?: string;
}

export type Platform = 'WEB' | 'IOS' | 'ANDROID' | 'SERVER';

/** Envelope v1: one event as sent to Observa. */
export interface TrackedEvent {
  /** Generated here; makes retries idempotent. */
  id: string;
  /** Dotted lowercase name, e.g. `product.viewed`. */
  type: string;
  occurredAt: string;
  platform: Platform;
  sessionId: string;
  subject: { userId?: string; anonymousId: string };
  /** The business data. */
  body: Properties;
  /** Small tags for filtering. */
  attributes?: Properties;
  /** Where the SDK ran: page, locale, screen, library; and where the visit came from: campaign, referrer. */
  context: Properties;
}

export interface TrackOptions {
  attributes?: Properties;
}

export interface TransportRequest {
  url: string;
  body: string;
}

export interface TransportResponse {
  /** HTTP status, or 0 when the network failed. */
  status: number;
  /** Response body, when there is one. */
  body?: string;
}

/** Must not throw. */
export interface Transport {
  send(request: TransportRequest): Promise<TransportResponse>;
  /** Fire-and-forget send used while the page is being hidden or closed. Returns false if not queued. */
  beacon?(request: TransportRequest): boolean;
}

export interface KeyValueStorage {
  get(key: string): string | null;
  set(key: string, value: string): void;
  remove(key: string): void;
}

export interface ObservaStats {
  /** Waiting in memory. */
  queued: number;
  /** Accepted by the server (including duplicates of events it already had). */
  sent: number;
  /** Refused by the server one by one; `debug: true` logs why. */
  rejected: number;
  /** Never delivered: invalid before sending, queue overflow, or the whole request refused. */
  dropped: number;
}
