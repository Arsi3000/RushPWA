/* SKIN RUSH — service worker.
   Задача: приложение открывается без сети и не залипает на старом HTML.

   Отличия от «учебного» SW, и почему так:
   1) Кэш по одному файлу, а не addAll: один 404 не должен ломать установку целиком.
   2) Имя кэша содержит версию. activate удаляет все чужие кэши, поэтому при выпуске
      новой версии старый HTML гарантированно выметается.
   3) Навигация: сеть и кэш наперегонки с таймаутом. На мобильной сети fetch может
      висеть полминуты — без таймаута приложение не откроется, хотя кэш рядом.
   4) Всё, что не GET, и любые внешние хосты (Firebase, картинки с GitHub Pages) —
      сразу в сеть, кэш не трогаем.
*/
var VERSION = '4.19.0';
var CACHE = 'skinrush-' + VERSION;

/* ВАЖНО про картинку: игра просит её по адресу aug.png?v=5
   (WEAPON_IMG_V в index.html). Кеш сравнивает полный адрес с query-строкой,
   поэтому предзагружать надо ровно с той же меткой — иначе офлайн будет промах.
   Меняете PNG — поднимайте ?v= и здесь, и в HTML, иначе здесь останется старая.
   4.0.0: остальные картинки оружия (glock.png и т.д.) в CORE не перечислены
   намеренно — их имена теперь свободные, а офлайн-кэш доберёт их сам при
   первой загрузке (стратегия «кэш первым, промах — сеть»). */
var CORE = [
  './',
  './index.html',
  './manifest.json',
  './icon-180.png',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-512.png',
  './aug.png?v=5',
  './music/open.mp3'
];

self.addEventListener('install', function(e){
  e.waitUntil(
    caches.open(CACHE).then(function(c){
      /* каждый файл отдельно: чего нет — пропускаем, установку не валим */
      return Promise.all(CORE.map(function(url){
        return c.add(new Request(url, {cache:'reload'})).catch(function(){ return null; });
      }));
    }).then(function(){ return self.skipWaiting(); })
  );
});

self.addEventListener('activate', function(e){
  e.waitUntil(
    caches.keys().then(function(keys){
      return Promise.all(keys.filter(function(k){ return k !== CACHE; })
                             .map(function(k){ return caches.delete(k); }));
    }).then(function(){ return self.clients.claim(); })
  );
  /* 4.12.0: музыка. В install её не кладём — 2.6 МБ задержали бы установку
     на мобильной сети, а приложение должно открываться сразу. Догружаем
     фоном после активации; если не успеет — трек закэшируется при первом
     проигрывании обычной стратегией «кэш первым, промах — сеть». */
  var warm = ['./music/Skin-rush.ogg', './music/Skin-rush.m4a', './video/glassbreak.webm'];
  caches.open(CACHE).then(function(c){
    warm.forEach(function(u){
      c.match(u).then(function(hit){
        if(hit) return null;
        return fetch(u, {cache:'reload'}).then(function(r){
          if(r && r.ok) return c.put(u, r);
        }).catch(function(){ return null; });
      });
    });
  }).catch(function(){});
});

function isExternal(url){
  return url.origin !== self.location.origin;
}

self.addEventListener('fetch', function(e){
  var req = e.request;

  /* только GET и только свои файлы */
  if (req.method !== 'GET') return;
  var url;
  try { url = new URL(req.url); } catch(err){ return; }
  if (isExternal(url)) return;

  /* 4.15.0: видео и звук не перехватываем.
     Safari (и любой WebKit на iPhone) запрашивает медиа кусками — заголовком
     Range. Service worker отдаёт из кэша целый ответ 200, Safari такой ответ
     на Range не принимает и ролик молча не играет. Именно поэтому на iPhone
     не появлялась заставка, хотя в Chrome и Яндекс.Браузере она работала.
     Пусть медиа идёт напрямую в сеть — там GitHub Pages сам отдаст куски. */
  if (req.headers.has('range')) return;
  if (req.destination === 'video' || req.destination === 'audio') return;

  /* навигация: сеть против таймаута, при неудаче — кэш */
  if (req.mode === 'navigate' || req.destination === 'document'){
    e.respondWith(
      new Promise(function(resolve){
        var settled = false;
        var timer = setTimeout(function(){
          if(settled) return;
          settled = true;
          caches.match('./index.html').then(function(hit){ resolve(hit || caches.match('./')); });
        }, 3000);

        fetch(req).then(function(resp){
          if(settled) return;
          settled = true; clearTimeout(timer);
          var copy = resp.clone();
          caches.open(CACHE).then(function(c){ c.put('./index.html', copy); });
          resolve(resp);
        }).catch(function(){
          if(settled) return;
          settled = true; clearTimeout(timer);
          caches.match(req).then(function(hit){
            resolve(hit || caches.match('./index.html'));
          });
        });
      })
    );
    return;
  }

  /* остальное: кэш первым, промах — сеть (и запоминаем удачный ответ) */
  e.respondWith(
    caches.match(req).then(function(hit){
      if(hit) return hit;
      return fetch(req).then(function(resp){
        if(resp && resp.ok && resp.type === 'basic'){
          var copy = resp.clone();
          caches.open(CACHE).then(function(c){ c.put(req, copy); });
        }
        return resp;
      }).catch(function(){ return hit; });
    })
  );
});
