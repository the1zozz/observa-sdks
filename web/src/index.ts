import { ObservaClient } from './client.js';

export { ObservaClient, SDK_VERSION, VISITOR_HEADER } from './client.js';
export { memoryStorage } from './platform.js';
export type {
  Campaign, ConsentChannels, ConsentOptions, KeyValueStorage, ObservaConfig, ObservaStats, Platform, Properties, PushOptions, PushState,
  TrackedEvent, TrackOptions, Transport,
  TransportRequest, TransportResponse,
} from './types.js';

/** The shared client for the page. Call `observa.init({ key, endpoint })` once at startup. */
export const observa = new ObservaClient();
