# CodeHub — Carpeta de Publicidad

**Publisher ID:** `ca-pub-3780093322926832`

---

## Estado actual
- ✅ `ads.txt` en raíz — correcto para verificación de Google
- ⏳ Cuenta AdSense en revisión — los anuncios aparecerán automáticamente cuando Google apruebe
- ✅ Código AdSense implementado en `index.html` y `pages/novedades.html`

---

## Unidades de anuncio implementadas

### index.html
| Posición | Formato | Ubicación en código |
|----------|---------|---------------------|
| Hero (bajo el fold) | Auto responsive | ~línea 563 |
| Pre-footer | Auto responsive | ~línea 1226 |

### pages/novedades.html
| Posición | Formato | Ubicación en código |
|----------|---------|---------------------|
| Ad-gate (pre-descarga) | Auto responsive | `#adgate-ins` |

---

## Próximo paso: Agregar slots reales

Cuando Google apruebe la cuenta y crees unidades de anuncio en el panel AdSense,
reemplaza `data-ad-format="auto"` por tu slot ID real:

```html
<!-- ANTES (auto-ads sin slot) -->
<ins class="adsbygoogle"
     data-ad-client="ca-pub-3780093322926832"
     data-ad-format="auto"
     data-full-width-responsive="true"></ins>

<!-- DESPUÉS (con slot ID real) -->
<ins class="adsbygoogle"
     data-ad-client="ca-pub-3780093322926832"
     data-ad-slot="1234567890"
     data-ad-format="auto"
     data-full-width-responsive="true"></ins>
```

---

## ads.txt (raíz del proyecto)
```
google.com, pub-3780093322926832, DIRECT, f08c47fec0942fa0
```
Este archivo ya existe en la raíz. Google lo verifica en:
`https://wilson360-labs.vercel.app/ads.txt`

---

## Notas técnicas
- `data-ad-slot="auto"` es **inválido** — fue corregido a `data-ad-format="auto"`
- El script de AdSense está en el `<head>` de ambas páginas — correcto
- La meta `google-adsense-account` está presente — correcto
- El CSP en index.html ya incluye todos los dominios de AdSense — correcto
