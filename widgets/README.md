# CodeHub Widgets

Galería de widgets reutilizables de CodeHub. Cada widget vive en su propia
carpeta con su CSS y JS autocontenidos, y se carga solo si está presente en
`index.html`.

## Catálogo

| Widget | Descripción |
|---|---|
| [weather/](weather/README.md) | Pastilla flotante de clima (Google/Windows style): temp + ciudad, al expandir muestra pronóstico por horas y recomendaciones inteligentes. |

## Cómo añadir un widget nuevo

1. Crea `widgets/<nombre>/` con `widget.css`, `widget.js` y un `README.md`.
2. En `index.html` añade el `<link rel="stylesheet">` **después de
   `site-tour.css`** y **antes de `viewport-guard.css`** (que SIEMPRE es la
   última capa). El `<script>` va al final del `<body>` con `defer`.
3. Añade los assets al `PRECACHE` de `sw.js` y bumpea `VERSION`.

## Reglas de oro

- Probar en los breakpoints canónicos: 380, 480, 640, 720 y 768 px, claro y
  oscuro.
- Cualquier medio (`img`, `svg`) con `max-width: 100%`.
- Los datos sensibles (API keys) NUNCA se commitearn: se leen vía
  `RC.ready()` → `/api/config` → env del backend.