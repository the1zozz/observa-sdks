import type { PushOptions, PushState } from './types.js';

const PATH = '/ingest/v1/client/push';
const DEFAULT_WORKER = '/observa-sw.js';

/** Who is subscribing, and where Observa is. */
export interface PushContext {
  endpoint: string;
  key: string;
  userId?: string;
  anonymousId: string;
}

/** Whether this browser can take web push at all. */
export function pushSupported(): boolean {
  return typeof window !== 'undefined' && typeof navigator !== 'undefined' && 'serviceWorker' in navigator
    && 'PushManager' in window && typeof Notification !== 'undefined';
}

/** A base64url key (VAPID public key) as the bytes `applicationServerKey` takes. */
export function urlBase64ToBytes(value: string): Uint8Array {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (value.length % 4)) % 4);
  const raw = atob(padded);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

export async function pushState(options: PushOptions = {}): Promise<PushState> {
  if (!pushSupported()) return 'unsupported';
  if (Notification.permission === 'denied') return 'denied';
  const registration = await navigator.serviceWorker.getRegistration(options.serviceWorker ?? DEFAULT_WORKER);
  const subscription = await registration?.pushManager.getSubscription();
  if (subscription) return 'subscribed';
  return Notification.permission === 'granted' ? 'granted' : 'prompt';
}

/**
 * Asks the browser for permission (call it from a click), subscribes with the workspace's key, and tells Observa,
 * which records the subscription and push consent. Resolves true when the browser is subscribed.
 */
export async function subscribe(context: PushContext, options: PushOptions = {}): Promise<boolean> {
  if (!pushSupported()) return false;
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return false;

  const answer = await fetch(`${context.endpoint}${PATH}/key?key=${encodeURIComponent(context.key)}`);
  if (!answer.ok) throw new Error(`no web push connection in Observa (${answer.status})`);
  const { publicKey } = (await answer.json()) as { publicKey: string };
  const serverKey = urlBase64ToBytes(publicKey);

  const registration = await navigator.serviceWorker.register(options.serviceWorker ?? DEFAULT_WORKER);
  await navigator.serviceWorker.ready;
  let subscription = await registration.pushManager.getSubscription();
  // A subscription made with another key (the connection was replaced) cannot receive Observa's messages.
  if (subscription && !sameKey(subscription.options.applicationServerKey, serverKey)) {
    await subscription.unsubscribe();
    subscription = null;
  }
  subscription ??= await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: serverKey as BufferSource });
  return send(context, 'subscribe', subscription.toJSON());
}

/** Stops notifications in this browser, and tells Observa. Resolves true when there was a subscription. */
export async function unsubscribe(context: PushContext, options: PushOptions = {}): Promise<boolean> {
  if (!pushSupported()) return false;
  const registration = await navigator.serviceWorker.getRegistration(options.serviceWorker ?? DEFAULT_WORKER);
  const subscription = await registration?.pushManager.getSubscription();
  if (!subscription) return false;
  const json = subscription.toJSON();
  await subscription.unsubscribe();
  await send(context, 'unsubscribe', { endpoint: json.endpoint });
  return true;
}

/** `text/plain`, like the event batches: no CORS preflight. */
async function send(context: PushContext, action: string, subscription: unknown): Promise<boolean> {
  const response = await fetch(`${context.endpoint}${PATH}`, {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain' },
    body: JSON.stringify({
      key: context.key, action, subscription, anonymousId: context.anonymousId,
      ...(context.userId ? { userId: context.userId } : {}),
    }),
  });
  return response.ok;
}

function sameKey(current: ArrayBuffer | null, expected: Uint8Array): boolean {
  if (!current) return false;
  const bytes = new Uint8Array(current);
  return bytes.length === expected.length && bytes.every((b, i) => b === expected[i]);
}
