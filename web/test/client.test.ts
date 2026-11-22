import { afterEach, describe, expect, it, vi } from 'vitest';
import { ObservaClient, memoryStorage } from '../src/index.js';
import { campaignOf, externalReferrer } from '../src/client.js';
import type { ObservaConfig, TrackedEvent, Transport, TransportRequest, TransportResponse } from '../src/index.js';

/** Records requests and answers with scripted statuses and bodies (default: 202, everything accepted). */
class FakeTransport implements Transport {
  requests: TransportRequest[] = [];
  statuses: number[] = [];
  bodies: string[] = [];
  throws = false;

  async send(request: TransportRequest): Promise<TransportResponse> {
    if (this.throws) throw new Error('network exploded');
    this.requests.push(request);
    const count = JSON.parse(request.body).events.length;
    return {
      status: this.statuses.shift() ?? 202,
      body: this.bodies.shift() ?? JSON.stringify({ accepted: count, duplicates: 0, rejected: 0, errors: [] }),
    };
  }

  events(): TrackedEvent[] {
    return this.requests.flatMap((r) => JSON.parse(r.body).events as TrackedEvent[]);
  }

  types(): string[] {
    return this.events().map((e) => e.type);
  }
}

let clients: ObservaClient[] = [];

function setup(overrides: Partial<ObservaConfig> = {}) {
  const transport = new FakeTransport();
  const clock = { t: Date.parse('2026-09-27T10:00:00Z') };
  const client = new ObservaClient();
  client.init({
    key: 'pk_test',
    endpoint: 'http://observa.test/',
    flushIntervalMs: 3_600_000,
    maxBatchSize: 10,
    transport,
    storage: memoryStorage(),
    now: () => clock.t,
    ...overrides,
  });
  clients.push(client);
  return { client, transport, clock };
}

afterEach(() => {
  clients.forEach((c) => c.shutdown());
  clients = [];
});

describe('ObservaClient', () => {
  it('sends envelope v1 batches with the key and sentAt to the ingest client endpoint', async () => {
    const { client, transport, clock } = setup();

    client.track('category.selected', { categoryId: 3 });
    client.track('products.page_changed', { page: 2 });
    clock.t += 1_500;
    await client.flush();

    expect(transport.requests).toHaveLength(1);
    expect(transport.requests[0].url).toBe('http://observa.test/ingest/v1/client/events');
    const body = JSON.parse(transport.requests[0].body);
    expect(body.key).toBe('pk_test');
    expect(body.sentAt).toBe('2026-09-27T10:00:01.500Z');
    expect(body.events.map((e: TrackedEvent) => e.type)).toEqual(['category.selected', 'products.page_changed']);
    expect(body.events[0]).toMatchObject({
      platform: 'WEB',
      occurredAt: '2026-09-27T10:00:00.000Z',
      body: { categoryId: 3 },
      subject: { anonymousId: expect.any(String) },
    });
    expect(client.stats()).toEqual({ queued: 0, sent: 2, rejected: 0, dropped: 0, retried: 0 });
  });

  it('puts attributes next to the body when given', async () => {
    const { client, transport } = setup();

    client.track('order.created', { orderId: 'O-1' }, { attributes: { channel: 'app' } });
    await client.flush();

    expect(transport.events()[0]).toMatchObject({ body: { orderId: 'O-1' }, attributes: { channel: 'app' } });
  });

  it('keeps one anonymous id and one session across events', async () => {
    const { client, transport } = setup();

    client.track('test.a');
    client.track('test.b');
    await client.flush();

    const [a, b] = transport.events();
    expect(a.subject.anonymousId).toBe(b.subject.anonymousId);
    expect(a.sessionId).toBe(b.sessionId);
    expect(a.id).not.toBe(b.id);
  });

  it('starts a new session after the inactivity timeout', async () => {
    const { client, transport, clock } = setup({ sessionTimeoutMs: 60_000 });

    client.track('test.a');
    clock.t += 30_000;
    client.track('test.b');
    clock.t += 61_000;
    client.track('test.c');
    await client.flush();

    const [a, b, c] = transport.events();
    expect(b.sessionId).toBe(a.sessionId);
    expect(c.sessionId).not.toBe(a.sessionId);
  });

  it('flushes on its own once a batch is full', async () => {
    const { client, transport } = setup({ maxBatchSize: 3 });

    client.track('test.a');
    client.track('test.b');
    expect(transport.requests).toHaveLength(0);
    client.track('test.c');
    await Promise.resolve();

    expect(transport.requests).toHaveLength(1);
  });

  it('keeps events and retries after a server error, in order, with a fresh sentAt', async () => {
    const { client, transport, clock } = setup();
    transport.statuses = [503, 202];

    client.track('test.first');
    client.track('test.second');
    await client.flush();
    expect(client.stats()).toMatchObject({ queued: 2, sent: 0 });

    await client.flush(); // still inside the backoff window: nothing sent
    expect(transport.requests).toHaveLength(1);

    clock.t += 60_000;
    await client.flush();
    expect(transport.requests).toHaveLength(2);
    expect(transport.types().slice(2)).toEqual(['test.first', 'test.second']);
    const [firstTry, retry] = transport.requests.map((r) => JSON.parse(r.body).sentAt);
    expect(retry).not.toBe(firstTry);
    expect(client.stats()).toMatchObject({ queued: 0, sent: 2, dropped: 0 });
  });

  it('drops a batch the server rejects instead of retrying forever, counted as rejected', async () => {
    const { client, transport } = setup();
    transport.statuses = [400];

    client.track('test.a');
    await client.flush();
    await client.flush();

    expect(transport.requests).toHaveLength(1);
    expect(client.stats()).toMatchObject({ queued: 0, rejected: 1, dropped: 0 });
  });

  it('bounds the queue by dropping the oldest events and counting them', async () => {
    const { client, transport } = setup({ maxQueueSize: 3, maxBatchSize: 100 });

    ['test.e1', 'test.e2', 'test.e3', 'test.e4', 'test.e5'].forEach((t) => client.track(t));
    await client.flush();

    expect(transport.types()).toEqual(['test.e3', 'test.e4', 'test.e5']);
    expect(client.stats().dropped).toBe(2);
  });

  it('drops invalid events on the client so one bad event cannot fail a whole batch', async () => {
    const { client, transport } = setup();
    const circular: Record<string, unknown> = {};
    circular['self'] = circular;

    client.track('Product Viewed');
    client.track('nodot');
    client.track('test.circular', circular);
    client.track('test.good');
    await client.flush();

    expect(transport.types()).toEqual(['test.good']);
    expect(client.stats()).toMatchObject({ rejected: 3, dropped: 0 });
  });

  it('sends $identify with both ids, attaches the user id afterwards, and forgets it after reset', async () => {
    const { client, transport } = setup();

    client.track('test.before');
    client.identify('user-42', { plan: 'pro' });
    client.track('test.after');
    client.reset();
    client.track('test.logged_out');
    await client.flush();

    const [before, identified, after, loggedOut] = transport.events();
    expect(before.subject.userId).toBeUndefined();
    expect(identified).toMatchObject({
      type: '$identify',
      subject: { userId: 'user-42', anonymousId: before.subject.anonymousId },
      body: { plan: 'pro' },
    });
    expect(after.subject.userId).toBe('user-42');
    expect(loggedOut.subject.userId).toBeUndefined();
    expect(loggedOut.subject.anonymousId).not.toBe(before.subject.anonymousId);
  });

  it('counts events the server refused one by one, without retrying them', async () => {
    const { client, transport } = setup();
    transport.bodies = [JSON.stringify({
      accepted: 2, duplicates: 0, rejected: 1,
      errors: [{ index: 1, id: 'x', reasons: [{ field: 'body.amount', message: 'must be a number' }] }],
    })];

    client.track('test.a');
    client.track('test.b');
    client.track('test.c');
    await client.flush();
    await client.flush();

    expect(transport.requests).toHaveLength(1);
    expect(client.stats()).toEqual({ queued: 0, sent: 2, rejected: 1, dropped: 0, retried: 0 });
  });

  it('explains each rejection in the console when debug is on', async () => {
    const debug = vi.spyOn(console, 'debug').mockImplementation(() => {});
    const { client, transport } = setup({ debug: true });
    transport.bodies = [JSON.stringify({
      accepted: 0, duplicates: 0, rejected: 1,
      errors: [{ index: 0, id: 'x', reasons: [{ field: 'body.amount', message: 'must be a number' }] }],
    })];

    client.track('order.created', { amount: 'twelve' });
    await client.flush();

    expect(debug).toHaveBeenCalledWith('[observa] rejected "order.created" (#0): body.amount must be a number', '');
    debug.mockRestore();
  });

  it('never throws into the host app, even when the transport throws', async () => {
    const { client, transport } = setup();
    transport.throws = true;

    expect(() => client.track('test.a')).not.toThrow();
    await expect(client.flush()).resolves.toBeUndefined();
    expect(() => new ObservaClient().init({} as ObservaConfig)).not.toThrow();
  });

  it('remembers where the visit came from and sends it with every event of the visit', async () => {
    const page = { href: 'https://shop.test/p/1?utm_source=facebook&utm_medium=paid_social&utm_campaign=ramadan&token=x',
      referrer: 'https://m.facebook.com/story?id=9' };
    const { client, transport, clock } = setup({ sessionTimeoutMs: 60_000, page: () => page });

    client.track('product.viewed');
    page.href = 'https://shop.test/cart';
    page.referrer = 'https://shop.test/p/1';
    client.track('cart.item_added');
    page.href = 'https://shop.test/p/2?utm_source=newsletter&utm_medium=email';
    client.track('product.viewed');
    clock.t += 120_000;
    page.href = 'https://shop.test/';
    page.referrer = '';
    client.track('page.viewed');
    await client.flush();

    const [first, second, third, nextVisit] = transport.events();
    const facebook = { source: 'facebook', medium: 'paid_social', name: 'ramadan' };
    expect(first.context).toMatchObject({ campaign: facebook, referrer: 'https://m.facebook.com/story' });
    expect(second.context).toMatchObject({ campaign: facebook, referrer: 'https://m.facebook.com/story' });
    expect(third.context['campaign']).toEqual({ source: 'newsletter', medium: 'email' });
    expect(nextVisit.context['campaign']).toBeUndefined();
    expect(nextVisit.context['referrer']).toBeUndefined();
  });

  it('reads campaign tags and referrers', () => {
    expect(campaignOf('https://a.test/?gclid=abc')).toEqual({ source: 'google', medium: 'cpc' });
    expect(campaignOf('https://a.test/?utm_term=shoes&utm_content=banner')).toEqual({ term: 'shoes', content: 'banner' });
    expect(campaignOf('https://a.test/?q=1')).toBeUndefined();
    expect(campaignOf('not a url')).toBeUndefined();
    expect(externalReferrer('https://www.google.com/search?q=private', 'https://a.test/')).toBe('https://www.google.com/search');
    expect(externalReferrer('https://a.test/other', 'https://a.test/')).toBeUndefined();
  });

  it('records consent per channel with the text and form as evidence', async () => {
    const { client, transport } = setup();

    client.consent({ email: false, sms: true }, { text: 'Send me offers by SMS', form: 'preferences' });
    client.consent({} as never);
    await client.flush();

    expect(transport.events()).toHaveLength(1);
    expect(transport.events()[0]).toMatchObject({
      type: '$consent.update',
      body: { channels: { email: false, sms: true }, text: 'Send me offers by SMS', form: 'preferences' },
    });
  });

  describe('the visitor header (contract §9)', () => {
    const page = () => ({ href: 'https://shop.example.com/bookings', referrer: '' });

    it('is the visitor and the session of the events, never the user', async () => {
      const { client, transport } = setup({ page });
      client.identify('user_xyz');   // not hex, so a random UUID can never contain it by chance
      client.track('booking.started');
      const header = client.visitorHeader();
      await client.flush();
      const event = transport.events()[1];
      expect(header).toBe(`${event.subject.anonymousId};${event.sessionId}`);
      expect(header).not.toContain('user_xyz');
      expect(header).toMatch(/^[A-Za-z0-9_-]{1,128};[A-Za-z0-9_-]{1,128}$/);
    });

    it('goes to the page\'s own origin and the listed hosts only', () => {
      const { client } = setup({ page, visitorHosts: ['api.example.com', '*.internal.example.com'] });
      const value = client.visitorHeader();
      expect(client.visitorHeaders()).toEqual({ 'Observa-Visitor': value });
      expect(client.visitorHeaders('/api/bookings')).toEqual({ 'Observa-Visitor': value });
      expect(client.visitorHeaders('https://shop.example.com/api')).toEqual({ 'Observa-Visitor': value });
      expect(client.visitorHeaders('https://api.example.com/v1')).toEqual({ 'Observa-Visitor': value });
      expect(client.visitorHeaders('https://billing.internal.example.com/x')).toEqual({ 'Observa-Visitor': value });
      expect(client.visitorHeaders('https://payments.other.com/charge')).toEqual({});
      expect(client.visitorHeaders('https://evil-api.example.com.attacker.io/')).toEqual({});
      expect(client.visitorHeaders('http://shop.example.com/api')).toEqual({}); // another origin: http
      expect(client.visitorHeaders('javascript:alert(1)')).toEqual({});
    });

    it('is a new visitor after reset, and nothing before init', () => {
      expect(new ObservaClient().visitorHeader()).toBeNull();
      expect(new ObservaClient().visitorHeaders('/api')).toEqual({});
      const { client } = setup({ page });
      const before = client.visitorHeader()!;
      client.reset();
      const after = client.visitorHeader()!;
      expect(after.split(';')[0]).not.toBe(before.split(';')[0]);
      expect(after.split(';')[1]).not.toBe(before.split(';')[1]);
    });
  });
  describe('the contract, behaviour by behaviour (OBS-360)', () => {
    /** An event body of about `kb` KB once written. */
    const big = (kb: number) => ({ text: 'x'.repeat(kb * 1024) });

    it('refuses a secret key: nothing is sent, and the page is told once', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const { client, transport } = setup({ key: 'sk_live_0123' });
      client.track('test.a');
      await client.flush();
      expect(transport.requests).toHaveLength(0);
      expect(warn).toHaveBeenCalledTimes(1);
      expect(String(warn.mock.calls[0][0])).toContain('publishable key (pk_');
      warn.mockRestore();
    });

    it('keeps every request under 512 KB, in order, whatever the batch size', async () => {
      const { client, transport } = setup({ maxBatchSize: 100 });
      for (let i = 0; i < 40; i++) client.track(`test.e${i}`, big(30));
      await client.flush();
      expect(transport.requests.length).toBeGreaterThan(2);
      for (const r of transport.requests) expect(new TextEncoder().encode(r.body).length).toBeLessThan(512 * 1024);
      expect(transport.types()).toEqual(Array.from({ length: 40 }, (_, i) => `test.e${i}`));
      expect(client.stats()).toMatchObject({ queued: 0, sent: 40 });
    });

    it('counts an event over 32 KB as rejected, in UTF-8 bytes, not characters', async () => {
      const { client, transport } = setup();
      client.track('test.arabic', { text: 'ع'.repeat(17_000) });  // 17 000 characters, 34 000 bytes
      client.track('test.ok', { text: 'ع'.repeat(15_000) });
      await client.flush();
      expect(transport.types()).toEqual(['test.ok']);
      expect(client.stats().rejected).toBe(1);
    });

    it('splits a batch the server finds too large (413), and rejects a single event it still refuses', async () => {
      const { client, transport } = setup();
      transport.statuses = [413, 202, 202];
      ['test.a', 'test.b', 'test.c', 'test.d'].forEach((t) => client.track(t));
      await client.flush();
      const sizes = transport.requests.map((r) => JSON.parse(r.body).events.length);
      expect(sizes).toEqual([4, 2, 2]);
      expect(transport.types().slice(4)).toEqual(['test.a', 'test.b', 'test.c', 'test.d']);
      expect(client.stats()).toMatchObject({ sent: 4, rejected: 0 });

      transport.statuses = [413];
      client.track('test.huge');
      await client.flush();
      expect(client.stats()).toMatchObject({ queued: 0, rejected: 1 });
    });

    it('logs a refused key once, keeps queuing, and counts the events as rejected', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const { client, transport } = setup();
      transport.statuses = [401, 403];
      client.track('test.a');
      await client.flush();
      client.track('test.b');
      await client.flush();
      client.track('test.c');
      await client.flush();
      expect(warn).toHaveBeenCalledTimes(1);
      expect(String(warn.mock.calls[0][0])).toContain('allowed origins');
      expect(transport.requests).toHaveLength(3);
      expect(client.stats()).toMatchObject({ sent: 1, rejected: 2, queued: 0 });
      warn.mockRestore();
    });

    it('retries the same ids with backoff from 1 s doubling, plus at most 20 % jitter, and counts each wait', async () => {
      const { client, transport, clock } = setup();
      transport.statuses = [0, 503, 500, 202];
      client.track('test.a');
      const waits: number[] = [];
      for (let i = 0; i < 3; i++) {
        await client.flush();
        const start = clock.t;
        let waited = 0;
        while (transport.requests.length === i + 1) {     // step the clock until the retry goes
          clock.t += 50;
          waited += 50;
          await client.flush();
        }
        waits.push(waited);
        clock.t = start + waited;
      }
      expect(waits[0]).toBeGreaterThanOrEqual(1_000);
      expect(waits[0]).toBeLessThanOrEqual(1_250);
      expect(waits[1]).toBeGreaterThanOrEqual(2_000);
      expect(waits[1]).toBeLessThanOrEqual(2_450);
      expect(waits[2]).toBeGreaterThanOrEqual(4_000);
      expect(waits[2]).toBeLessThanOrEqual(4_850);
      const ids = transport.requests.map((r) => JSON.parse(r.body).events[0].id);
      expect(new Set(ids).size).toBe(1);
      expect(client.stats()).toMatchObject({ sent: 1, retried: 3, queued: 0 });
    });

    it('waits at least Retry-After on 429 and 503, and never more than 60 s between tries otherwise', async () => {
      const { client, clock } = setup();
      const transport: Transport & { calls: number } = {
        calls: 0,
        async send(r) {
          this.calls++;
          return this.calls === 1 ? { status: 429, retryAfter: 30 } : { status: 202, body: '{"rejected":0}' };
        },
      };
      const c = new ObservaClient();
      c.init({ key: 'pk_t', endpoint: 'http://o.test', transport, storage: memoryStorage(), now: () => clock.t,
        flushIntervalMs: 3_600_000 });
      clients.push(c);
      c.track('test.a');
      await c.flush();
      clock.t += 29_000;
      await c.flush();
      expect(transport.calls).toBe(1);
      clock.t += 1_100;
      await c.flush();
      expect(transport.calls).toBe(2);
      for (let attempt = 1; attempt < 40; attempt++) {
        const { backoff } = await import('../src/client.js');
        expect(backoff(attempt)).toBeLessThanOrEqual(72_000);
      }
      client.shutdown();
    });

    it('sends everything queued on flush, batch after batch', async () => {
      const { client, transport } = setup({ maxBatchSize: 3 });
      for (let i = 0; i < 2; i++) client.track(`test.e${i}`);
      client.track('test.e2');                       // a full batch goes by itself
      for (let i = 3; i < 8; i++) client.track(`test.e${i}`);
      await client.flush();
      expect(transport.types()).toEqual(Array.from({ length: 8 }, (_, i) => `test.e${i}`));
      expect(client.stats().queued).toBe(0);
    });

    it('ignores every call after shutdown, without throwing', async () => {
      const { client, transport } = setup();
      client.track('test.before');
      client.shutdown();
      expect(() => {
        client.track('test.after');
        client.identify('42');
        client.page();
        client.consent({ email: true });
        client.init({ key: 'pk_x', endpoint: 'http://o.test' });
      }).not.toThrow();
      await client.flush();
      expect(transport.types()).not.toContain('test.after');
      expect(client.stats().queued).toBe(0);
    });

    it('never throws, whatever the input, the storage or the network do', async () => {
      const broken = { get: () => { throw new Error('storage'); }, set: () => { throw new Error('storage'); },
        remove: () => { throw new Error('storage'); } };
      const { client } = setup({ storage: broken, page: () => { throw new Error('page'); } });
      const calls = [
        () => client.track(undefined as never),
        () => client.track('test.a', undefined as never),
        () => client.track('test.a', 5 as never),
        () => client.identify(undefined as never),
        () => client.identify('42', null as never),
        () => client.consent(null as never),
        () => client.page(null as never),
        () => client.reset(),
        () => client.visitorHeader(),
        () => client.visitorHeaders('::not a url::'),
        () => client.stats(),
        () => new ObservaClient().track('test.before_init'),
        () => new ObservaClient().init(null as never),
        () => new ObservaClient().shutdown(),
      ];
      for (const call of calls) expect(call).not.toThrow();
      await expect(client.flush()).resolves.toBeUndefined();
      await expect(new ObservaClient().flush()).resolves.toBeUndefined();
      await expect(client.subscribePush()).resolves.toBe(false);
    });

    describe('page exit', () => {
      /** A minimal browser: enough for the client to listen for the page being hidden or closed. */
      function fakeBrowser() {
        const document = Object.assign(new EventTarget(), { visibilityState: 'visible', title: 'Bookings', referrer: '' });
        const window = new EventTarget();
        vi.stubGlobal('window', window);
        vi.stubGlobal('document', document);
        vi.stubGlobal('location', { pathname: '/bookings', href: 'https://shop.test/bookings' });
        vi.stubGlobal('screen', { width: 1440, height: 900 });
        return { document, window };
      }

      afterEach(() => vi.unstubAllGlobals());

      function beaconSetup(accept: (body: string) => boolean) {
        const beacons: string[] = [];
        const transport = new FakeTransport();
        (transport as Transport).beacon = ({ body }) => {
          if (!accept(body)) return false;
          beacons.push(body);
          return true;
        };
        const { client } = setup({ transport, autoPageViews: false, autoClicks: false, maxBatchSize: 100 });
        return { client, transport, beacons };
      }

      it('hands what is queued to sendBeacon when the page is hidden, under 64 KB in all, and saves the rest', () => {
        const { document } = fakeBrowser();
        const { client, beacons } = beaconSetup(() => true);
        client.track('test.small');
        for (let i = 0; i < 12; i++) client.track(`test.e${i}`, big(10));
        document.visibilityState = 'hidden';
        document.dispatchEvent(new Event('visibilitychange'));
        const total = beacons.reduce((n, b) => n + new TextEncoder().encode(b).length, 0);
        expect(total).toBeLessThanOrEqual(64 * 1024);
        const sent = beacons.flatMap((b) => JSON.parse(b).events.map((e: TrackedEvent) => e.type));
        expect(sent.slice(0, 2)).toEqual(['test.small', 'test.e0']);
        expect(JSON.parse(beacons[0]).key).toBe('pk_test');
        expect(client.stats()).toMatchObject({ sent: sent.length, queued: 13 - sent.length });
      });

      it('also on pagehide; what the browser refuses is saved and sent by the next page, once', async () => {
        const { window } = fakeBrowser();
        const storage = memoryStorage();
        let quota = 64 * 1024;                       // the browser's room for everything sent while the page closes
        const beacons: string[] = [];
        const first = new FakeTransport();
        (first as Transport).beacon = ({ body }) => {
          const size = new TextEncoder().encode(body).length;
          if (size > quota) return false;
          quota -= size;
          beacons.push(body);
          return true;
        };
        const { client } = setup({ transport: first, storage, autoPageViews: false, autoClicks: false, maxBatchSize: 100 });
        for (let i = 0; i < 12; i++) client.track(`test.e${i}`, big(10));
        window.dispatchEvent(new Event('pagehide'));
        const beaconed = beacons.flatMap((b) => JSON.parse(b).events.map((e: TrackedEvent) => e.type));
        expect(beaconed.length).toBeGreaterThan(0);
        expect(beaconed.length).toBeLessThan(12);
        client.shutdown();

        const next = setup({ storage, autoPageViews: false, autoClicks: false, maxBatchSize: 100 });
        await next.client.flush();
        expect([...beaconed, ...next.transport.types()]).toEqual(Array.from({ length: 12 }, (_, i) => `test.e${i}`));
        expect(storage.get('observa.pending')).toBeNull();

        const third = setup({ storage, autoPageViews: false, autoClicks: false });
        await third.client.flush();
        expect(third.transport.requests).toHaveLength(0);
      });

      it('forgets what it saved when the person comes back to the page, and never sends it to another key', async () => {
        const { document } = fakeBrowser();
        const storage = memoryStorage();
        const transport = new FakeTransport();
        (transport as Transport).beacon = () => false;
        const { client } = setup({ transport, storage, autoPageViews: false, autoClicks: false });
        client.track('test.a');
        document.visibilityState = 'hidden';
        document.dispatchEvent(new Event('visibilitychange'));
        expect(storage.get('observa.pending')).not.toBeNull();
        document.visibilityState = 'visible';
        document.dispatchEvent(new Event('visibilitychange'));
        expect(storage.get('observa.pending')).toBeNull();
        await client.flush();
        expect(transport.types()).toEqual(['test.a']);

        client.track('test.b');
        document.visibilityState = 'hidden';
        document.dispatchEvent(new Event('visibilitychange'));
        const other = setup({ key: 'pk_other', storage, autoPageViews: false, autoClicks: false });
        await other.client.flush();
        expect(other.transport.requests).toHaveLength(0);
      });

      it('does nothing while the page is only hidden then shown again with nothing queued', () => {
        const { document } = fakeBrowser();
        const { beacons } = beaconSetup(() => true);
        document.visibilityState = 'hidden';
        document.dispatchEvent(new Event('visibilitychange'));
        expect(beacons).toHaveLength(0);
      });
    });
  });
});
