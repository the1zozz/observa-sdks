import { afterEach, describe, expect, it, vi } from 'vitest';

/** The script-tag build (browser/observa.js) starts itself from its tag's data attributes. */
describe('script tag', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  function page(attributes: Record<string, string>) {
    const script = { getAttribute: (name: string) => attributes[name] ?? null };
    const window = new EventTarget() as unknown as Record<string, unknown>;
    vi.stubGlobal('window', window);
    vi.stubGlobal('document', Object.assign(new EventTarget(), { currentScript: script, visibilityState: 'visible', title: '', referrer: '' }));
    vi.stubGlobal('location', { pathname: '/', href: 'https://site.test/' });
    vi.stubGlobal('screen', { width: 1, height: 1 });
    vi.stubGlobal('navigator', { language: 'en', userAgent: 'test' });
    return window;
  }

  it('starts with data-key and data-endpoint, and puts the client on window.observa', async () => {
    const window = page({ 'data-key': 'pk_test', 'data-endpoint': 'https://observa.test', 'data-auto-page-views': 'false',
      'data-auto-clicks': 'false', 'data-visitor-hosts': 'api.site.test, *.internal.site.test' });
    const sdk = await import('../src/browser.js');
    expect(window['observa']).toBe(sdk.observa);
    expect(sdk.observa.visitorHeaders('https://api.site.test/x')).toHaveProperty('Observa-Visitor');
    expect(sdk.observa.visitorHeaders('https://other.test/x')).toEqual({});
    sdk.observa.shutdown();
  });

  it('waits for init when the tag has no key', async () => {
    page({});
    const sdk = await import('../src/browser.js');
    expect(sdk.observa.visitorHeader()).toBeNull();
    expect(typeof sdk.ObservaClient).toBe('function');
  });
});
