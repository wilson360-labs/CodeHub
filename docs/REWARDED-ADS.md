# Anuncios recompensados verificados por servidor

> Guía + checklist del sistema de **Rewarded Ads** de CodeHub.
> Regla de oro: **la recompensa solo se acredita desde el backend**, nunca
> por una promesa del cliente. Anti-fraude por diseño.

---

## 1. Qué es (y qué no)

**Sí** es un sistema de recompensas:

1. El usuario ve un anuncio (video recompensado o completar una oferta).
2. La red de anuncios **legitima la acción por un postback server-to-server**
   con un secreto compartido.
3. El backend de CodeHub acredita **una sola vez** (idempotencia + tope diario).

**No** es un botón que da créditos cuando el cliente dice "vi el anuncio".
Google/Unity penalizan eso y es trivial de falsear.

### Alcance

| Capa | Dónde vive | Quién valida |
|---|---|---|
| App Android (APK) | `apk/android` → Unity Ads | Unity SDK (Unity no da SSV de rewarded en la práctica) |
| Web (navegador) | Monetag + offerwalls | **Backend Rewards** (postback S2S con secreto) |
| App web/APK generado | `js/rewarded-ad.js` | Orquesta nativo + web |

---

## 2. Arquitectura

```
      ┌──────────────────────┐
      │  Usuario (web/apk)   │  ve el anuncio
      └──────────┬───────────┘
                 │ 1) POST /api/rewards/token   (sesión Bearer)
                 ▼
        ┌──────────────────┐
        │   BACKEND        │  emite un NONCE single-use
        |  /api/rewards    |  (guardado en Mongo, TTL 15 min)
        └────────┬─────────┘
                 │ 2) el nonce viaja a la red como `ymid`
                 ▼
        ┌──────────────────┐
        │  Red de anuncios |   el usuario completa el anuncio
        |  (Monetag, etc.) |
        └────────┬─────────┘
                 │ 3) GET/POST /api/rewards/postback?...
                 │    …&secret=…&ymid=<nonce>&reward_event_type=valued
                 ▼
        ┌─────────────────┐
        │  BACKEND        │  consume el nonce (single-use),
        |  acredita 1 vez |  verifica `valued`, tope diario,
        └────────┬────────┘  ledger con `ref` único → +créditos
                 │
                 │ 4) el front hace polling de /balance
                 ▼
        window.CodeHubRewardedAd.watch()  →  { earned, amount, credits }
```


**Por qué un nonce**: cada visualización tiene un token secreto, ligado al
usuario y de un solo uso. Si un atacante no ve el anuncio, no recibe el
postback; y el postback no se puede reutilizar.

---

## 3. Redes soportadas

| Red | Formato | Verificación | Cuándo usarla |
|---|---|---|---|
| **Monetag** | Rewarded (video) | Postback S2S con `secret` + `radi=valued`/`ymid` | Web (recomendada) |
| **Offerwalls** (AdGem, ayeT-Studios, BitLabs, AdGate) | Ofertas/apps | Postback S2S firmado / JSON | Valor mayor, web |
| **Unity Ads** | Rewarded/Banner/Interstitial | Callback del SDK (app) | APK nativo |
| **Google AdSense** | Display | — | Web (sin rewarded) |

> ⚠️ No reintroducir el loader clásico `adsbygoogle.js?client=…`; ver
> `docs/ADS-COMPLIANCE.md`. AdSense es display, no premia.

---

## 4. Backend

Módulo: `backend/modules/rewards/`.

| Archivo | Rol |
|---|---|
| `security.js` | Lógica pura (nonces, HMAC, `normalizePostback`) — testeable |
| `config.js` | Env: secretos, montos, tope, `user_id` sources |
| `models.js` | Mongoose `RewardNonce`, `RewardLedger`, `RewardBalance` |
| `routes.js` | Endpoints (token, postback, balance, history, spend, admin) |
| `index.js` | Barrel |

Montado en `backend/server.js`:

```js
app.use('/api/rewards', requireAuth, rewardsRouter);
```

### Endpoints

| Método | Ruta | Auth | Qué hace |
|---|---|---|---|
| `GET` | `/api/rewards/config` | ninguna | ¿Recompensas activas?, proveedores |
| `POST` | `/api/rewards/token` | sesión | Emite nonce single-use |
| `GET/POST` | `/api/rewards/postback[/:source]` | **secreto** | Acredita (lo llama la red) |
| `GET` | `/api/rewards/balance` | sesión | Créditos + ganado hoy |
| `GET` | `/api/rewards/history` | sesión | Últimos movimientos |
| `POST` | `/api/rewards/spend` | sesión | Gasta créditos |
| `GET` | `/api/rewards/admin/stats` | `x-admin-key` | Métricas |

### Variables de entorno

```bash
# Secretos del postback. Con UNO basta para todas las redes;
# el JSON permite uno por red (recomendado).
REWARD_POSTBACK_SECRET_MONETAG=""        # alias: MONETAG_POSTBACK_SECRET
REWARD_POSTBACK_SECRETS='{"monetag":"<secreto>","offerwall":"<secreto>"}'

# Firma de nonces (defensa en profundidad). Si falta, se genera efímero.
REWARD_HMAC_SECRET=""

# Créditos por unidad. Sin `default`, cada anuncio vale lo que diga la red.
REWARD_AMOUNTS='{"default":1,"video":1,"offer":5}'

REWARD_DAILY_CAP="50"
REWARD_AD_UNIT="default"

# Offerwalls que acreditan por user_id (en vez de nonce). Vacío = solo nonce.
REWARD_USER_ID_SOURCES="adgem,bitlabs,ayet"
```

### Contrato de postback

El backend **normaliza** los campos de la red (`security.normalizePostback`):

- **Solo si `reward_event_type` (o `status`) es `valued`/`1`/`completed`** se
  acredita.
- `ymid` / `custom_data` / `user_id` → identidad (nonce o usuario).
- `transaction_id` / `ref` → idempotencia (índice único en Mongo).
  Si falta, se usa `zona:nonce`.

```
GET /api/rewards/postback/monetag
  ?secret=<SECRET>
  &ymid=<nonce>
  &event_type=impression&type=...
  &reward_event_type=valued
  &zone_id=123&…
```

---

## 5. Front-end

`js/rewarded-ad.js` expone `window.CodeHubRewardedAd`
(compatible con la versión previa):

```js
CodeHubRewardedAd.show(function (earned) { /* legacy */ });

// Verificado por servidor:
const r = await CodeHubRewardedAd.watch({ adUnit: 'video' });
if (r.earned) { /* r.credits */ }
```

Flujo de `watch()`:
1. En el APK (`window.CodeHubNative`) muestra el anuncio de **Unity Ads**.
2. En web (con sesión) pide un `token` y muestra el **rewarded** con el
   proveedor configurado, pasando el nonce.
3. Espera (polling) a que el backend acredite por postback.

---

## 6. Checklist de puesta en marcha

### Backend (Render)

- [ ] Definir `REWARD_POSTBACK_SECRETS` (o un único `REWARD_POSTBACK_SECRET`).
- [ ] Definir `REWARD_AMOUNTS` y `REWARD_DAILY_CAP`.
- [ ] Desplegar y probar `GET /api/rewards/config` → `{ enabled: true }`.
- [ ] `MONGODB_URI` activo (los modelos lo usan).

### Monetag (web)

- [ ] Cargar el SDK/snippet en la página del rewarded.
- [ ] Configurar el **postback** apuntando a:
      `https://codehub-98s6.onrender.com/api/rewards/postback`
      con `secret=<REWARD_POSTBACK_SECRET>`.
- [ ] Verificar que el postback llega y `/balance` sube.

### Unity Ads (APK)

- [ ] `app/src/main/java/com/codehub/app/UnityAdsManager.java`:
      `GAME_ID` + placements (`BP_*`).
- [ ] SDK en `build.gradle` (`com.unity3d.ads:unity-ads`).
- [ ] Consentimiento (UMP) previo: `init` solo con `canRequestAds()`.
- [ ] Consent Mode / app-ads.txt servidos por el host.

### Cumplimiento

- [ ] Sin valor monetario canjeable (política Google).
- [ ] No incentivar clics (solo ver el anuncio).
- [ ] No inyectar AdSense en el WebView (`CodeHubNative`).
- [ ] Postback siempre **por servidor**.

---

## 7. Pruebas

```bash
cd backend && npm test        # incluye tests/rewards.test.js
```

Los tests cubren la lógica pura (normalización, HMAC, estados) sin red ni
Mongo. El postback real se prueba con la red en modo test.

---

## 8. Hoja de ruta

1. Backend + tests ✅
2. Docs ✅ (este archivo)
3. Orquestador web `js/rewarded-ad.js` (multi-red + verificación)
4. Monetag (crear zona + postback) → activar recompensas web
5. Offerwalls de alto valor (opcional)
6. Unity Ads en el APK (`UnityAdsManager`) ✅ integrado

Ver `docs/ADS-COMPLIANCE.md` para las reglas obligatorias de anuncios.
