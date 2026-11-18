import { describe, expect, it } from 'vitest';
import { ObservaClient, memoryStorage } from '../src/index.js';
import { pushSupported, urlBase64ToBytes } from '../src/push.js';

describe('web push', () => {
  it('reads a VAPID public key (base64url, no padding) as the 65 bytes of a P-256 point', () => {
    const key = 'BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U';
    const bytes = urlBase64ToBytes(key);
    expect(bytes.length).toBe(65);
    expect(bytes[0]).toBe(4);
  });

  it('never throws: without a browser it reports unsupported and does not subscribe', async () => {
    const client = new ObservaClient();
    expect(await client.subscribePush()).toBe(false);
    client.init({ key: 'pk_test', endpoint: 'http://localhost:8090', storage: memoryStorage(), autoPageViews: false });
    expect(pushSupported()).toBe(false);
    expect(await client.pushState()).toBe('unsupported');
    expect(await client.subscribePush()).toBe(false);
    expect(await client.unsubscribePush()).toBe(false);
    client.shutdown();
  });
});
