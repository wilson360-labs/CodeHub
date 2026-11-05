# Eliminar PairIP con el bot de Telegram (uso personal)

Wilson.E 2026 · **Uso personal: NO distribuir** (toca código de terceros).

PairIP ("pairipcore", `libpairpcore.so` + `com.pairip.*`) es el SDK de Google que
algunos APKs usan para exigir instalación desde Play ("Get this app from Play"),
validar la firma y detectar depuradores/Frida.

## Cómo se usa desde el bot

El bot hace el trabajo completo: analiza y elimina PairIP automáticamente.

```
Manda el APK como documento        · sin comando previo (detecta .apk solo)
/pairip <url>                      · por link directo (GitHub release, tope 120 MB)
/pairip <appId>                    · analiza el APK de una app del catálogo
/pairip app.apk                    · el APK con pie de foto "/pairip app.apk"
```

* APK ≤ 20 MB: llega como documento (tope de la Bot API de Telegram).
* APK > 20 MB: usa la URL (ej. release de GitHub) — el servidor lo baja.

Al detectar PairIP, el bot devuelve el APK re-empaquetado sin PairIP listo
para descargar. Los endpoints son `POST /api/admin/pairip/analyze` y
`POST /api/admin/pairip/strip` (sesión admin, guardas anti-SSRF, redirects
validados).

## Qué elimina exactamente el bot

1. `lib/<abi>/libpairpcore.so` — el SDK nativo de Google.
2. La firma JAR v1 (`META-INF/*.RSA|*.DSA|*.EC|*.MF|*.SF`): cualquier byte
   distinto invalida la firma y Android la rechazaría.

**Paso final obligatorio (en tu PC):** el APK sale sin firma → hay que
re-firmarlo con una clave propia antes de instalar:

```bash
zipalign -p 4 app-stripped.apk app-aligned.apk
apksigner sign --ks tu-key.jks app-aligned.apk
apksigner verify app-aligned.apk
```

Las clases `com.pairip.*` (Java/Dex) quedan en el APK, pero sin el `.so`
nativo no pueden ejecutar los checks de Play/firma/antidebug. Limitación
honesta: si la app codificó métodos en la VM de PairIP y no arranca sin el
`.so`, el paquete resultante puede crashear al abrir — en ese caso no hay
eliminación 100% limpia sin reescribir la VM.

## Liberaciones

- Implementación propia (sin copiar código de terceros).
- Uso personal: el paquete resultante no se distribuye.
