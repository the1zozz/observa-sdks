import type { KeyValueStorage, Transport } from './types.js';

/** fetch with text/plain: a CORS "simple" request, so the browser sends no preflight. */
export const browserTransport: Transport = {
  async send({ url, body }) {
    try {
      const response = await fetch(url, {
        method: 'POST',
        body,
        headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
        credentials: 'omit',
      });
      let text: string | undefined;
      try {
        text = await response.text();
      } catch {
        text = undefined;
      }
      return { status: response.status, body: text };
    } catch {
      return { status: 0 };
    }
  },
  beacon({ url, body }) {
    try {
      return typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function'
        && navigator.sendBeacon(url, new Blob([body], { type: 'text/plain;charset=UTF-8' }));
    } catch {
      return false;
    }
  },
};

/** localStorage when available (it throws in some privacy modes), otherwise memory for this page only. */
export function browserStorage(): KeyValueStorage {
  const memory = new Map<string, string>();
  const local = (() => {
    try {
      const probe = '__observa_probe__';
      window.localStorage.setItem(probe, probe);
      window.localStorage.removeItem(probe);
      return window.localStorage;
    } catch {
      return null;
    }
  })();
  return {
    get: (k) => (local ? local.getItem(k) : memory.get(k) ?? null),
    set: (k, v) => {
      try {
        local ? local.setItem(k, v) : memory.set(k, v);
      } catch {
        memory.set(k, v);
      }
    },
    remove: (k) => {
      try {
        local ? local.removeItem(k) : memory.delete(k);
      } catch {
        memory.delete(k);
      }
    },
  };
}

export function memoryStorage(): KeyValueStorage {
  const memory = new Map<string, string>();
  return {
    get: (k) => memory.get(k) ?? null,
    set: (k, v) => void memory.set(k, v),
    remove: (k) => void memory.delete(k),
  };
}

export function uuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  // RFC 4122 v4 fallback for older browsers.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export const isBrowser = (): boolean => typeof window !== 'undefined' && typeof document !== 'undefined';
