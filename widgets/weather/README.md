# Widget Clima — CodeHub

Pastilla flotante de clima, estilo Samsung/Windows Weather, _pro_ e
inteligente. Vivir en la esquina inferior izquierda de la app y la web.

## Comportamiento

- **Colapsada**: icono + temperatura + ciudad (toca para expandir).
- **Expandida**: temperatura grande, condición, sensación térmica,
  franja de pronóstico por horas (próximas 24 h) y **recomendación
  inteligente** basada en datos reales:
  - 🌂 Lleve paraguas (prob. de lluvia ≥ 60 %)
  - ☀️ Bloqueador solar (UV ≥ 6) / UV extrema (≥ 8)
  - 🧥 Abríguese (≤ 12 °C) · 🥵 Hidrátese (≥ 32 °C)
  - ⛈️ Tormenta · ❄️ Nieve · 💨 Viento (≥ 40 km/h) · 🌫️ Niebla
- **Sin ubicación guardada**: el widget invita a elegir ciudad y desplaza
  a la sección Clima abriendo el mapa.
- Se actualiza solo cada ~10 min con Open-Meteo y usa la misma ubicación
  de `ch_user_*` de la sección Clima (mapa, GPS o IP). Caché en
  `ch_widget_weather`.

## Arquitectura

- `weather-widget.css`: estilos (breakpoints canónicos 380/480/640/720/768,
  tema claro por `prefers-color-scheme`, anti-desbordes).
- `weather-widget.js`: lógica autocontenida; API `window.chWidget =
  { refresh, toggle }`. Sin dependencias externas (Open-Meteo ya está en
  la CSP de CodeHub).
- Ubicación: reutiliza `chReadLocation()`. Escucha eventos `storage`,
  `visibilitychange` y refresco periódico.

## Integración en CodeHub

1. `<link rel="stylesheet" href="widgets/weather/weather-widget.css">`
   **después de `site-tour.css`** y **antes de `viewport-guard.css`**.
2. `<script src="widgets/weather/weather-widget.js" defer>` al final del body.
3. Añadir ambos assets al `PRECACHE` de `sw.js` y bumpear `VERSION`.