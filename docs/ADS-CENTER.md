# Centro de anuncios de CodeHub (administrado por el dev)

Este documento ES el centro de anuncios de CodeHub. **No hay pantalla de
anuncios dentro de la app**: el control vive en dos sitios, ambos del dev:

1. **Código** → política y perillas (`apk/android/app/src/main/java/com/codehub/app/NativeStoreAdGateway.kt`).
2. **Panel de Unity** → creatividades, *end cards*, *skip button* y *capping*.

El usuario nunca tiene que configurar nada y nunca queda obligado: puede
omitir el video de apoyo, omitir el interstitial con un botón y descargar sin
ver nada.

---

## 1. Formatos y placements

| Formato | Placement | Dónde aparece | Quién lo dispara |
|---|---|---|---|
| Rewarded (video) | `BP_Rewarded_Android` | Tienda, antes de descargar | El usuario (opt-in) |
| Interstitial | `BP_Interstitial_Android` | Tienda, al terminar una instalación | Código, con ventana de omitir |
| Banner 320x50 | `BP_Banner_Android` | Franja inferior reservada | SDK (`MainActivity`) |

Game ID: `800397583`. Los tres placements deben existir **exactos** en el panel.

## 2. Reglas que nunca se rompen

- El anuncio **nunca** está en el botón "Descargar": el CTA abre la descarga directo.
- El rewarded es **opt-in**: cierre ✕, botón "Descargar ahora, sin anuncio" y
  timeout de 20 s. Si el usuario lo cierra, la descarga arranca igual.
- La descarga **nunca** queda bloqueada por un anuncio: si no hay *fill*, si el
  SDK falla, si el usuario sale a la Play Store desde el CTA o si el SDK no
  responde, la descarga sigue (`proceedWhenVisible` espera a que la pantalla
  vuelva a estar visible y luego arranca sola).
- **Nunca se obliga a abrir la Google Play Store.** El CTA "Instalar" es del
  anunciante y lo controla Unity: por eso el placement de rewarded debe tener
  el *end card* desactivado (§3).
- Todo anuncio pasa antes por UMP (`ConsentManager.canRequestAds()`); sin
  consentimiento no hay *fill* ni anuncio.
- El interstitial nunca aparece sobre el botón de descarga ni al abrir la
  pantalla: solo en pausa natural (instalación silenciosa completada).

## 3. Panel de Unity (config del dev)

### `BP_Rewarded_Android`
- Tipo: **Rewarded Video sin *end card*** (sin botón "Instalar" del anunciante).
  Con *end card* el usuario ve el CTA y la X de cierre no aparece hasta el
  final: eso era lo que lo empujaba a la Google Play Store.
- **Skip button activado** (el usuario puede saltar antes) con retraso corto.
- *Category exclusions*: solo contenido apto; nada G/PG en apps con público infantil.
- Revisar que la creatividad no tenga CTA propio hacia tiendas.

### `BP_Interstitial_Android`
- *Frequency capping* del panel (además del cooldown en código).
- Unity no da botón de saltar en interstitial: por eso CodeHub avisa
  `SKIP_WINDOW_SECONDS` antes y ofrece **Omitir**.

### `BP_Banner_Android`
- 320x50, posición inferior, franja reservada (nunca tapa contenido).
- Sin auto-refresh agresivo.

## 4. Perillas en código (`NativeStoreAdGateway`)

| Constante | Valor | Qué hace |
|---|---|---|
| `INTERSTITIAL_COOLDOWN_MS` | 3 min | Pausa mínima entre dos interstitials |
| `INTERSTITIAL_SESSION_CAP` | 4 | Máximo de interstitials por sesión |
| `INTERSTITIAL_EVERY_DOWNLOADS` | 1 | Cada cuántas descargas se ofrece |
| `SKIP_WINDOW_SECONDS` | 4 | Segundos de aviso con botón "Omitir" |
| `REWARDED_TIMEOUT_MS` | 20 s | Si el SDK no responde, la descarga sigue |

Cambiar estas constantes = cambiar la política. No hay flags de usuario: el
usuario solo tiene la opción explícita de **"No mostrar esta oferta de nuevo"**
(pref `ch_ads/store_rewarded_skip`).

## 5. Depuración

- `adb logcat -s CodeHubUnityAds StoreAdGateway`
- Desde el WebView: `window.chAdsStatus(console.log)`, `?ads_debug=1` (badge),
  `?ads_test=1` (modo test de Unity; se aplica al reiniciar la app).

## 6. Checklist antes de publicar

- [ ] End card desactivado en `BP_Rewarded_Android` (nada de CTA a Play Store).
- [ ] Skip button del rewarded activo.
- [ ] Cooldown y tope del panel coherentes con §4.
- [ ] `ads.txt` y `app-ads.txt` servidos por el host real.
- [ ] Sin AdSense dentro del WebView/APK (guard `CodeHubNative` intacto).
