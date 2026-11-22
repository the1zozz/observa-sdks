/**
 * The script-tag build, for pages without a bundler:
 *
 * ```html
 * <script src="https://cdn.jsdelivr.net/gh/the1zozz/observa-sdks@0.3.0/web/browser/observa.js"
 *         data-key="pk_…" data-endpoint="https://observa.example.com"></script>
 * ```
 *
 * With `data-key` and `data-endpoint` it starts by itself; without them, call `Observa.observa.init({ … })`.
 * Everything the npm package exports is on `window.Observa`, and the page's client is also `window.observa`.
 */
import { observa } from './index.js';

export * from './index.js';

declare global {
  interface Window {
    observa?: typeof observa;
  }
}

try {
  window.observa = observa;
  const script = document.currentScript;
  const key = script?.getAttribute('data-key');
  const endpoint = script?.getAttribute('data-endpoint');
  if (key && endpoint) {
    const flag = (name: string) => {
      const value = script?.getAttribute(name);
      return value === null || value === undefined ? undefined : value !== 'false';
    };
    const hosts = script?.getAttribute('data-visitor-hosts');
    observa.init({
      key,
      endpoint,
      autoPageViews: flag('data-auto-page-views'),
      autoClicks: flag('data-auto-clicks'),
      debug: flag('data-debug'),
      visitorHosts: hosts ? hosts.split(',').map((h) => h.trim()).filter(Boolean) : undefined,
    });
  }
} catch {
  /* never into the page */
}
