'use strict';
/*
 * Caja Libre - service worker de la página móvil.
 * Solo hace dos cosas: mostrar las notificaciones que llegan por Web Push y abrir la página en la
 * sección correcta al tocarlas. No guarda páginas ni datos (la página siempre se pide al servidor)
 * y no intercepta peticiones. Solo se registra cuando la página se abrió por HTTPS.
 */

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', ev => ev.waitUntil(self.clients.claim()));

/** El servidor manda JSON cifrado: {titulo, cuerpo, etiqueta, url} */
self.addEventListener('push', ev => {
  let d = {};
  try { d = ev.data ? ev.data.json() : {}; } catch (e) { d = { titulo: 'Caja Libre', cuerpo: ev.data ? ev.data.text() : '' }; }
  const titulo = (d.titulo || 'Caja Libre').slice(0, 120);
  const url = typeof d.url === 'string' && d.url.startsWith('/') ? d.url : '/';
  ev.waitUntil(self.registration.showNotification(titulo, {
    body: (d.cuerpo || '').slice(0, 400),
    icon: '/icono-256.png',
    badge: '/icono-192.png',
    tag: d.etiqueta ? String(d.etiqueta).slice(0, 80) : undefined,
    renotify: false,
    data: { url },
    lang: 'es',
  }));
});

/** Al tocar: si la página ya está abierta se enfoca y se le pide ir a la sección; si no, se abre */
self.addEventListener('notificationclick', ev => {
  ev.notification.close();
  const url = (ev.notification.data && ev.notification.data.url) || '/';
  ev.waitUntil((async () => {
    const abiertas = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const c of abiertas) {
      if (new URL(c.url).origin === self.location.origin) {
        try { await c.focus(); } catch (e) { /* algunos navegadores no permiten enfocar */ }
        c.postMessage({ tipo: 'abrir', url });
        return;
      }
    }
    await self.clients.openWindow(url);
  })());
});

/** El navegador renovó la suscripción: la página la volverá a registrar en el servidor al abrirse */
self.addEventListener('pushsubscriptionchange', ev => {
  ev.waitUntil((async () => {
    const abiertas = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const c of abiertas) c.postMessage({ tipo: 'suscripcion-cambiada' });
  })());
});
