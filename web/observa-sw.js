/*
 * Observa web push service worker. Serve this file at the root of your site, e.g. /observa-sw.js, and call
 * observa.subscribePush() from a click. If the site already has a service worker, add to it instead:
 *   importScripts('/observa-sw.js');
 *
 * It shows Observa's notifications in the person's language and direction, tells Observa a notification was shown
 * (delivered), and opens its link on a click, through Observa so the click is counted.
 */
self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (e) {
    data = { title: event.data ? event.data.text() : '' };
  }
  const shown = data.shown
    ? fetch(data.shown, { method: 'POST', mode: 'no-cors', keepalive: true }).catch(() => undefined)
    : Promise.resolve();
  event.waitUntil(Promise.all([
    self.registration.showNotification(data.title || '', {
      body: data.body || '',
      icon: data.icon,
      image: data.image,
      lang: data.lang,
      dir: data.dir || 'auto',
      tag: data.tag,
      data: { url: data.url },
    }),
    shown,
  ]));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = event.notification.data && event.notification.data.url;
  if (url) {
    event.waitUntil(self.clients.openWindow(url));
  }
});
