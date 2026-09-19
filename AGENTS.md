# CodeHub — Convenciones de pantallas y responsividad (OBLIGATORIO)

Reglas que SIEMPRE deben respetarse al editar cualquier componente de CodeHub
para no romper la visualización en distintos dispositivos.

## Breakpoints canónicos (usar solo estos, nunca inventar anchos)

| Breakpoint | Aplica a |
|---|---|
| `max-width: 380px` | Teléfonos compactos — mínimo soportado |
| `max-width: 480px` | Teléfonos grandes en vertical |
| `max-width: 550px` | Phablets / teléfonos grandes |
| `max-width: 640px` | Teléfonos XXL / tablets pequeñas |
| `max-width: 720px` | Tablets compactas / landscape de teléfonos |
| `max-width: 768px` | Tablets verticales — breakpoint principal mobile/desktop |
| `max-width: 860px` | Tablets grandes / desktop compacto |
| `max-width: 1080px` | Desktop medio |

El rango móvil soportado es **380px – 720px**. Toda edición debe probarse
en 380, 480, 640, 720 y 768 px como mínimo, además de escritorio.

## Reglas de oro al editar CSS

1. **`css/viewport-guard.css` es la última capa CSS** (se carga al final de la
   cadena en `index.html`). Red de seguridad anti-desbordes. Toda corrección
   de un componente se añade allí o dentro de su `@media` canónico.
2. **Nunca inventar breakpoints intermedios** arbitrarios (p.ej. 517px, 703px).
   Reusar la tabla de arriba.
3. **Todo medio** (`img`, `video`, `canvas`, `iframe`, `svg`) con `max-width: 100%`.
4. **Hijos de grid/flex** con `min-width: 0` cuando contengan texto largo,
   tablas, `pre` o `code` para evitar desbordes.
5. **Probar en móvil y escritorio** cada cambio, en claro y oscuro.
6. **Cascada**: `index.css` → `components.css` → `index-responsive.css` →
   `site-tour.css` → `viewport-guard.css`. Para override, usar un archivo que
   cargue después o `!important` solo cuando sea necesario.

## Notas de mantenimiento

- `index.html` carga los CSS en el orden de la regla 6. Mantener ese orden.
- `sw.js` precachea los CSS principales (`index.css`, `components.css`,
  `index-responsive.css`, `site-tour.css`, `viewport-guard.css`). Si se agrega
  un CSS nuevo, agregarlo al `PRECACHE` y bumpear `VERSION` en `sw.js`.

## Anuncios (OBLIGATORIO seguir Google, nunca reglas propias)

Toda edición relacionada con publicidad (AdSense web / AdMob app) debe seguir
`docs/ADS-COMPLIANCE.md`. Reglas mínimas que SIEMPRE aplican:

1. **No reintroducir** el loader clásico `adsbygoogle.js?client=…` en páginas;
   usar `gtag('config', 'ca-pub-3780093322926832')`.
2. **No quitar** el Consent Mode v2 (default `denied` + `wait_for_update`) ni el
   guard `CodeHubNative`/`__apkNative` que impide AdSense dentro del WebView/APK.
3. La app monetiza **solo con AdMob (GMA SDK)** bajo el gate de UMP
   (`ConsentManager.java`); nunca inyectar AdSense en el WebView.
4. Mantener `ads.txt` y `app-ads.txt` servidos por el host real.
