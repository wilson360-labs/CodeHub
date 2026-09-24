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

1. **`css/viewport-guard.css` es la penúltima capa CSS** (red de seguridad
   anti-desbordes; se carga al final de la cadena en `index.html`, justo antes
   de `css/prefs.css`). Toda corrección de un componente se añade allí o dentro
   de su `@media` canónico.
2. **`css/prefs.css` es SIEMPRE la última capa CSS** (una sola línea, después de
   `viewport-guard.css`): contiene la personalización del usuario
   (acento, esquinas, densidad, contraste, kill-switches). No añadirle
   animaciones nuevas; solo reglas estáticas por atributos de `<html>`
   (`data-accent`, `data-radius`, `data-density`, `data-contrast`,
   `data-bg`, `data-wf/wh/ws/wk`).
3. **Nunca inventar breakpoints intermedios** arbitrarios (p.ej. 517px, 703px).
   Reusar la tabla de arriba.
4. **Todo medio** (`img`, `video`, `canvas`, `iframe`, `svg`) con `max-width: 100%`.
5. **Hijos de grid/flex** con `min-width: 0` cuando contengan texto largo,
   tablas, `pre` o `code` para evitar desbordes.
6. **Probar en móvil y escritorio** cada cambio, en claro y oscuro.
7. **Cascada**: `index.css` → `components.css` → `index-responsive.css` →
   `site-tour.css` → `viewport-guard.css` → `prefs.css`. Para override, usar un
   archivo que cargue después o `!important` solo cuando sea necesario.
   Las preferencias de usuario (prefs.css) pueden usar `!important` porque son
   overrides deliberados del usuario.

## Notas de mantenimiento

- `index.html` carga los CSS en el orden de la regla 7. Mantener ese orden.
- `sw.js` precachea los CSS principales (`index.css`, `components.css`,
  `index-responsive.css`, `site-tour.css`, `viewport-guard.css`, `prefs.css`).
  Si se agrega un CSS nuevo, agregarlo al `PRECACHE` y bumpear `VERSION` en `sw.js`.
- `js/prefs.js` se carga en `<head>` (antes del render) para aplicar los
  atributos de personalización sin flash; mantenerlo como única capa de
  personalización en JS.

## Anuncios (OBLIGATORIO seguir Google, nunca reglas propias)

Toda edición relacionada con publicidad (AdSense web / AdMob app) debe seguir
`docs/ADS-COMPLIANCE.md`. Reglas mínimas que SIEMPRE aplican:

1. **No reintroducir** el loader clásico `adsbygoogle.js?client=…` en páginas;
   usar `gtag('config', 'ca-pub-3780093322926832')`.
2. **No quitar** el Consent Mode v2 (default `denied` + `wait_for_update`) ni el
   guard `CodeHubNative`/`__apkNative` que impide AdSense dentro del WebView/APK.
3. La app monetiza con **Appodeal 4.4.0 (Mediation Only)** bajo el gate de UMP
   (`ConsentManager.java` → `AppodealManager.init`): AdMob (adaptador), AppLovin,
   Unity Ads, Vungle/Liftoff y BidMachine. NUNCA inyectar AdSense en el WebView.
4. Mantener `ads.txt` y `app-ads.txt` servidos por el host real (añadir el
   seller de Appodeal en el `app-ads.txt` de la app).
