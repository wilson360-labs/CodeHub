/**
 * ╔═══════════════════════════════════════════════════════════════╗
 * ║  CodeHub Blogger AutoPoster v1.0 — Wilson.E 2026             ║
 * ║  Genera posts con IA (Groq) y publica en Blogger API v3      ║
 * ║                                                               ║
 * ║  Variables de entorno requeridas (.env):                      ║
 * ║    GROQ_API_KEY          → tu clave Groq                      ║
 * ║    BLOGGER_BLOG_ID       → 4932034987684289893               ║
 * ║    BLOGGER_ACCESS_TOKEN  → OAuth2 token (ver README)          ║
 * ║                                                               ║
 * ║  Uso:                                                         ║
 * ║    node autoposter.js            → publica 1 post ahora      ║
 * ║    node autoposter.js --dry-run  → genera sin publicar        ║
 * ║    node autoposter.js --topic "EMI IA" → tema específico      ║
 * ║    node autoposter.js --schedule → activa modo programado     ║
 * ╚═══════════════════════════════════════════════════════════════╝
 */

require('dotenv').config();

// ─────────────────────────────────────────────────────────────────────
// CONFIGURACIÓN
// ─────────────────────────────────────────────────────────────────────

const CONFIG = {
  blogId:       process.env.BLOGGER_BLOG_ID || '4932034987684289893',
  blogUrl:      'https://codehub-labs.blogspot.com',
  siteUrl:      'https://wilson360-labs.vercel.app',
  authorName:   'Wilson.E',
  authorEmail:  'wilson.e360labs@gmail.com',

  // Frecuencia en modo --schedule (ms)
  // Por defecto: cada 3 días
  scheduleMs: 3 * 24 * 60 * 60 * 1000,

  // Cuántos posts generar por ejecución (1 = estable para AdSense)
  postsPerRun: 1,

  // Groq
  groqApiUrl: 'https://api.groq.com/openai/v1/chat/completions',
  groqModel:  'llama-3.3-70b-versatile',

  // Blogger API
  bloggerApiBase: 'https://www.googleapis.com/blogger/v3',
};

// ─────────────────────────────────────────────────────────────────────
// TEMAS — Cada sección de CodeHub explicada como artículo de blog
// ─────────────────────────────────────────────────────────────────────

const TOPICS = [
  // ── SECCIONES DEL SITIO ──────────────────────────────────────────
  {
    slug:   'emi-copilot-ia',
    title:  'EMI COPILOT: el asistente IA de CodeHub que responde sobre código, clima y más',
    prompt: `Escribe un artículo de blog en español (800-1000 palabras) sobre EMI COPILOT, el asistente de inteligencia artificial integrado en CodeHub (wilson360-labs.vercel.app). 
    
    Explica:
    - Qué es EMI COPILOT y por qué se creó
    - Cómo funciona por dentro: múltiples proveedores IA (Groq, Gemini, OpenRouter, Mistral, Cohere) con fallback automático
    - Qué puede hacer: debug de código, generación de imágenes, búsqueda de imágenes, respuestas sobre CodeHub, consejos de freelance
    - Cómo abrirlo en el sitio (botón flotante en esquina inferior)
    - Las acciones rápidas: Imagen, Debug, README, Code Review
    - Límite de 30 mensajes por sesión
    - Incluye ejemplos concretos de prompts útiles para desarrolladores
    - Cierra con CTA: "Prueba EMI COPILOT gratis en wilson360-labs.vercel.app"
    
    Formato HTML limpio (no markdown). Usa <h2>, <p>, <ul><li>, <code> para código inline, <pre><code> para bloques de código. Sin <html>, <head>, <body>. Tono técnico pero accesible.`,
    tags:   ['EMI COPILOT', 'inteligencia artificial', 'chatbot', 'CodeHub', 'asistente IA', 'Guatemala'],
  },
  {
    slug:   'herramientas-web-codehub',
    title:  '23 herramientas web gratuitas en CodeHub: guía completa de /tools',
    prompt: `Escribe un artículo de blog en español (900-1100 palabras) sobre las herramientas web de CodeHub disponibles en /tools.

    Menciona y explica brevemente cada herramienta:
    - Generador de contraseñas seguras (usa crypto.getRandomValues())
    - Generador de QR codes (3 tamaños, 4 colores, descarga PNG)
    - Hash SHA-256 y SHA-512 (Web Crypto API)
    - Base64 encoder/decoder
    - UUID v4 generator
    - Regex Tester
    - Pomodoro Timer (con notificaciones del navegador)
    - Conversor de unidades (longitud, peso, temperatura, volumen)
    - Conversor de monedas (en tiempo real)
    - Calculadora de IMC
    - Simulador de préstamos
    - Test de velocidad de escritura
    - Selector de colores (hex, rgb, hsl)
    - Generador de gradientes CSS
    - Minificador de código HTML/CSS/JS
    
    Explica la filosofía: todas funcionan 100% locales en el navegador, sin servidor, sin guardar datos.
    Incluye casos de uso prácticos para desarrolladores web de Guatemala y Latinoamérica.
    CTA final: "Usa todas las herramientas gratis en wilson360-labs.vercel.app/tools"
    
    Formato HTML limpio. Usa <h2>, <h3>, <p>, <ul><li>, <code>. Sin wrapper HTML.`,
    tags:   ['herramientas web', 'tools', 'desarrollador web', 'CodeHub', 'gratis', 'Guatemala'],
  },
  {
    slug:   'splash-screen-canvas-js',
    title:  'Cómo construí el splash screen de CodeHub con Canvas API y JavaScript puro',
    prompt: `Escribe un tutorial técnico en español (900-1100 palabras) explicando cómo está construido el splash screen animado de CodeHub.

    Cubre:
    - Por qué existe un splash screen: tiempo de carga de recursos externos (fonts, imágenes, CDN)
    - La arquitectura: canvas de partículas + barra de progreso + logo con animación orbital
    - El sistema de partículas en Canvas (código real comentado)
    - La lógica de la barra de progreso con easing cúbico (avanza libre hasta 70%, salta al 100% cuando el logo carga)
    - Los anillos orbitales con conic-gradient y CSS mask
    - El sistema de cierre: condición doble (logoReady && minTimeDone)
    - Fallback a los 5s si el logo no carga
    - Performance: por qué usar requestAnimationFrame en lugar de setInterval
    - La transición de salida con fade out
    
    Incluye snippets de código JavaScript reales (no inventados) con comentarios en español.
    Tono tutorial, como si explicaras a un desarrollador junior.
    CTA: "Mira el efecto en vivo en wilson360-labs.vercel.app"
    
    Formato HTML. <h2>, <p>, <pre><code class="language-javascript">, <ul><li>. Sin wrapper HTML.`,
    tags:   ['JavaScript', 'Canvas API', 'splash screen', 'animación web', 'tutorial', 'CodeHub'],
  },
  {
    slug:   'red-neuronal-canvas',
    title:  'Cómo hacer una red neuronal animada en el fondo de tu web con Canvas y Vanilla JS',
    prompt: `Escribe un tutorial técnico en español (900-1100 palabras) explicando cómo está implementada la red neuronal animada del fondo de CodeHub.

    Explica paso a paso:
    - Concepto: nodos con posición y velocidad aleatoria, líneas entre nodos cercanos
    - La clase Node: propiedades (x, y, radius, speedX, speedY), métodos draw() y update()
    - El loop de animación con requestAnimationFrame
    - El cálculo de distancia entre nodos (Math.sqrt de diferencias)
    - La opacidad de las líneas proporcional a la distancia (efecto de red)
    - La interacción con el mouse (atracción suave a 100px)
    - Optimización: por qué 60 nodos y no 200
    - Responsive: resize del canvas en window.resize
    - Integración con el tema de color: rgba(255, 69, 0, ...) naranjas de CodeHub
    
    Incluye el código completo de la clase Node y el loop animate() con comentarios.
    Explica cómo adaptar los colores a cualquier diseño.
    CTA: "Ve la red neuronal en vivo en wilson360-labs.vercel.app"
    
    Formato HTML. Usa <pre><code class="language-javascript"> para bloques. Sin wrapper HTML.`,
    tags:   ['JavaScript', 'Canvas', 'animación', 'red neuronal visual', 'fondo web', 'tutorial'],
  },
  {
    slug:   'sistema-clima-openmeteo',
    title:  'Integrar clima en tiempo real en tu web sin API key: Open-Meteo + geolocalización',
    prompt: `Escribe un tutorial técnico en español (900-1100 palabras) sobre el sistema de clima integrado en CodeHub.

    Explica:
    - Por qué se integró el clima: agregar valor real al usuario más allá del portfolio
    - Open-Meteo API: completamente gratis, sin API key, datos WMO
    - Detección automática de ubicación por IP con ipapi.co (fallback: ip-api.com)
    - Geolocalización precisa del navegador con navigator.geolocation
    - Geocodificación inversa con Nominatim (OpenStreetMap) para obtener nombre de ciudad
    - La tabla de códigos WMO para convertir weather_code a emojis y descripciones en español
    - Búsqueda manual de ciudad con geocoding-api.open-meteo.com
    - Manejo de errores: cascada de 3 fallbacks para nunca mostrar error al usuario
    - Los datos mostrados: temp, sensación, humedad, viento, precipitación
    - Integración con OpenWeatherMap cuando el usuario tiene su propia API key
    
    Incluye código real de las funciones principales.
    Explica por qué estas APIs gratuitas son ideales para proyectos de portfolio.
    CTA: "Mira el clima en vivo en wilson360-labs.vercel.app"
    
    Formato HTML limpio. Sin wrapper HTML.`,
    tags:   ['API', 'clima', 'Open-Meteo', 'geolocalización', 'JavaScript', 'tutorial', 'gratis'],
  },
  {
    slug:   'pwa-service-worker',
    title:  'Cómo convertí mi portfolio en una PWA con Service Worker, manifest y push notifications',
    prompt: `Escribe un artículo técnico en español (900-1100 palabras) sobre la implementación PWA en CodeHub.

    Cubre:
    - Qué es una PWA y por qué importa para un portfolio de desarrollador
    - El manifest.json: iconos, theme_color, display standalone, start_url
    - El Service Worker (sw.js): estrategia cache-first para assets, network-first para API calls
    - El prompt de instalación: interceptar beforeinstallprompt, mostrar el banner propio en vez del default
    - El sistema de actualización: banner de "Nueva versión disponible" sin recargar automáticamente
    - Por qué NO recargar automáticamente (el bug del bootloop que se solucionó con userTriggeredUpdate)
    - Push notifications con VAPID keys: suscripción, almacenamiento en backend (Render/Railway)
    - Las notificaciones de clima: alertas automáticas si lluvia, calor extremo o viento fuerte
    - El diálogo de permisos estilo iOS para pedir notificaciones de forma elegante
    
    Tono tutorial. Incluye snippets de código reales.
    CTA: "Instala CodeHub como app desde wilson360-labs.vercel.app"
    
    Formato HTML. Sin wrapper HTML.`,
    tags:   ['PWA', 'Service Worker', 'push notifications', 'JavaScript', 'portfolio', 'tutorial'],
  },
  {
    slug:   'backend-node-render',
    title:  'Cómo desplegar un backend Node.js + Express gratis en Render con MongoDB y Supabase',
    prompt: `Escribe un tutorial técnico en español (1000-1200 palabras) sobre el backend de CodeHub desplegado en Render.

    Cubre:
    - Stack: Node.js 18+, Express, Mongoose (MongoDB Atlas), Supabase Storage
    - Por qué Render en lugar de Railway o Vercel serverless
    - El render.yaml: configuración declarativa del servicio
    - Variables de entorno críticas: GROQ_API_KEY, SUPABASE_URL, SUPABASE_KEY, MONGODB_URI
    - El engine fix en package.json que resolvió el error de deployment (>=18 vs 18.x)
    - Los endpoints principales: /api/chat, /api/visit, /api/stats/live, /api/generate-image
    - Rate limiting con express-rate-limit para evitar abuso
    - CORS configurado para Vercel + localhost
    - Supabase para almacenamiento de APKs y eventos de visita
    - MongoDB para estadísticas diarias y chat logs
    - Los múltiples proveedores IA con fallback automático (Groq → Gemini → OpenRouter → Mistral → Cohere)
    - WebSockets con 'ws' para notificaciones en tiempo real
    
    Explica la arquitectura de forma que alguien pueda replicarla.
    CTA: "El backend de CodeHub es open source en github.com/wilson360-labs"
    
    Formato HTML. Sin wrapper HTML.`,
    tags:   ['Node.js', 'Express', 'Render', 'backend', 'despliegue', 'tutorial', 'MongoDB', 'Supabase'],
  },
  {
    slug:   'easter-eggs-ux',
    title:  '7 Easter eggs que escondí en mi portfolio web y cómo mejorar el engagement con UX secreto',
    prompt: `Escribe un artículo entretenido y técnico en español (800-1000 palabras) sobre los easter eggs ocultos en CodeHub.

    Describe cada easter egg:
    1. El logo (7 clicks activa el "Modo Ultra" con partículas, lluvia de código y mensaje especial)
    2. El Código Konami (↑↑↓↓←→←→BA activa confetti de colores)
    3. Escribir "dev" en el teclado (activa el modo desarrollador en consola)
    4. Ctrl+K (command palette con búsqueda rápida de secciones)
    5. Ctrl+Shift+S (muestra estadísticas de la sesión: scrolls, clicks, tiempo)
    6. El Menú Experimental (lado izquierdo): Matrix Mode, explosión de partículas, Screen Shake, Glitch Effect, Rainbow Scroll, comandos de voz
    7. El mensaje en la consola del navegador (guía para developers curiosos)
    
    Explica por qué los easter eggs mejoran el engagement y la experiencia de usuario.
    Habla sobre el impacto: usuarios que descubren easter eggs son más propensos a compartir el sitio.
    Incluye el código real de la detección del Konami Code.
    CTA: "¿Cuántos puedes encontrar en wilson360-labs.vercel.app?"
    
    Formato HTML. Tono divertido pero con insights técnicos reales. Sin wrapper HTML.`,
    tags:   ['UX', 'easter eggs', 'JavaScript', 'engagement', 'portfolio', 'experiencia usuario'],
  },
  {
    slug:   'multiidioma-i18n',
    title:  'Sistema de multiidioma (ES/EN) en Vanilla JS sin librerías: así lo hice en CodeHub',
    prompt: `Escribe un tutorial técnico en español (800-1000 palabras) sobre el sistema de internacionalización (i18n) de CodeHub.

    Explica:
    - El objeto i18n con diccionarios ES y EN (las claves específicas usadas: available, years, role, bio, from, viewCV, contact)
    - El uso de data-i18n en elementos HTML para marcar texto traducible
    - La función applyLang(lang): itera los elementos, busca la clave, actualiza innerHTML
    - Persistencia del idioma con localStorage ('ch_lang')
    - Por qué innerHTML en lugar de textContent (permite negritas con <strong> en el bio)
    - El botón de idioma en la configuración del sitio
    - Limitaciones de este enfoque vs. librerías como i18next
    - Cuándo tiene sentido implementar i18n propio vs. librería
    - El metadato html lang que se actualiza dinámicamente
    
    Incluye el código completo del objeto i18n y la función applyLang.
    Explica cómo extender el sistema para agregar un tercer idioma.
    CTA: "Prueba cambiar el idioma en wilson360-labs.vercel.app → ⚙️ Configuración"
    
    Formato HTML. Sin wrapper HTML.`,
    tags:   ['JavaScript', 'i18n', 'multiidioma', 'internacionalización', 'tutorial', 'Vanilla JS'],
  },
  {
    slug:   'contact-form-emailjs-turnstile',
    title:  'Formulario de contacto con EmailJS y Cloudflare Turnstile: sin backend, sin spam',
    prompt: `Escribe un tutorial técnico en español (900-1100 palabras) sobre el formulario de contacto de CodeHub.

    Cubre:
    - Por qué EmailJS: envío de emails desde el frontend sin servidor propio
    - Configuración de EmailJS: cuenta, servicio Gmail, plantilla, Public Key
    - Las 3 variables: EMAILJS_PUBLIC_KEY, EMAILJS_SERVICE_ID, EMAILJS_TEMPLATE_ID
    - Cloudflare Turnstile: por qué en lugar de reCAPTCHA (más privado, sin fricción)
    - Integración de Turnstile: data-sitekey, tema oscuro, idioma español
    - Validación del formulario: nombre requerido, email con regex, mensaje mínimo 10 chars
    - El contador de caracteres con color warning (amarillo >380, rojo >480)
    - Adjuntar el token de Turnstile como campo oculto antes del envío
    - Manejo de errores: feedback visual con clases CSS success/error
    - El select de asunto: opciones de Proyecto Web, Python, Consultoría, Colaboración
    
    Incluye el código real de la función sendEmail() y validateForm().
    Explica cómo configurar la plantilla de EmailJS para recibir todos los campos.
    CTA: "Usa el formulario en wilson360-labs.vercel.app → #contacto"
    
    Formato HTML. Sin wrapper HTML.`,
    tags:   ['EmailJS', 'Cloudflare Turnstile', 'formulario contacto', 'JavaScript', 'tutorial', 'sin backend'],
  },
  {
    slug:   'liquid-letters-animation',
    title:  'Cómo crear el efecto de letras líquidas (water ripple) con Canvas y Vanilla JS',
    prompt: `Escribe un tutorial técnico en español (900-1100 palabras) sobre el efecto de letras líquidas ("Liquid Letters") de los textos "Wilson.E" y "CodeHub" en el portfolio.

    Explica paso a paso:
    - El concepto: texto dividido en spans individuales, cada letra oscila como agua
    - Splitting del texto: reemplazar TextNodes con spans .liquid-letter via DOM
    - La onda continua: superposición de 2 senoides con fases aleatorias por letra (simula irregularidad del agua real)
    - Las propiedades aleatorias por letra: offset, amp1, amp2, freq1, freq2, scaleA
    - El loop con requestAnimationFrame y la variable waving para pausar en hover
    - El efecto ripple en hover: la letra hovered se eleva, las adyacentes progresivamente menos
    - El color ripple: paleta naranja para "CodeHub", violeta para "Wilson.E"
    - El textShadow glow en la letra activa
    - Detección de mobile: en móviles solo la onda, sin ripple hover (performance)
    - Cómo aplicar el data-liq-size="large/small" para escalar la amplitud
    
    Incluye el código real de las funciones setup() y del wave loop.
    Explica el truco del mouseenter/mouseleave para pausar y reanudar.
    CTA: "Ve las letras en movimiento en wilson360-labs.vercel.app"
    
    Formato HTML. Sin wrapper HTML.`,
    tags:   ['CSS', 'JavaScript', 'animación', 'efecto visual', 'canvas', 'tutorial', 'UX'],
  },
  {
    slug:   'seo-adsense-portfolio',
    title:  'Cómo preparé mi portfolio para Google AdSense: SEO técnico, sitemap y política de privacidad',
    prompt: `Escribe un artículo técnico en español (900-1100 palabras) sobre el proceso de preparar CodeHub para cumplir con los requisitos de Google AdSense.

    Cubre:
    - El error "Contenido de bajo valor": qué significa y por qué lo recibí
    - La importancia de la Política de Privacidad y Términos de Uso (enlaces en el footer)
    - El sitemap.xml: estructura, URLs de todas las páginas, lastmod, changefreq
    - El robots.txt: qué bloquear (admin-hub, analytics) y qué permitir (todo lo demás)
    - SEO técnico en index.html: meta description, keywords, author, robots
    - Open Graph (og:title, og:description, og:image) para redes sociales
    - Twitter Cards para compartir en Twitter/X
    - Schema.org JSON-LD: Person type con jobTitle, url, email, address, sameAs
    - El ads.txt: google.com, pub-ID, DIRECT
    - La meta tag google-adsense-account
    - Por qué el preconnect a pagead2.googlesyndication.com mejora la velocidad de anuncios
    - Remover protecciones agresivas (right-click block, beforeunload) que afectan UX
    
    Tono reflexivo y práctico: comparte el proceso real de aplicar y las correcciones hechas.
    CTA: "Lee la política de privacidad en wilson360-labs.vercel.app/privacy"
    
    Formato HTML. Sin wrapper HTML.`,
    tags:   ['AdSense', 'SEO', 'portfolio', 'Google', 'monetización', 'sitemap', 'tutorial'],
  },
];

// ─────────────────────────────────────────────────────────────────────
// ESTADO LOCAL — Evita publicar el mismo topic dos veces
// ─────────────────────────────────────────────────────────────────────

const fs   = require('fs');
const path = require('path');
const STATE_FILE = path.join(__dirname, '.autoposter-state.json');

function loadState() {
  try {
    return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
  } catch {
    return { published: [], lastRun: null, totalPosts: 0 };
  }
}

function saveState(state) {
  fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
}

function getNextTopic(state) {
  // Retorna el topic que NO haya sido publicado aún
  const unpublished = TOPICS.filter(t => !state.published.includes(t.slug));
  if (!unpublished.length) {
    // Todos publicados → reiniciar ciclo
    console.log('📚 Todos los topics publicados. Reiniciando ciclo...');
    state.published = [];
    return TOPICS[0];
  }
  return unpublished[0];
}

// ─────────────────────────────────────────────────────────────────────
// GENERACIÓN CON GROQ
// ─────────────────────────────────────────────────────────────────────

async function generatePostWithGroq(topic) {
  const key = process.env.GROQ_API_KEY;
  if (!key) throw new Error('GROQ_API_KEY no configurada en .env');

  console.log(`🤖 Generando contenido con Groq para: "${topic.title}"...`);

  const systemPrompt = `Eres un escritor técnico experto en desarrollo web. 
Escribes contenido detallado, técnico y útil en español para desarrolladores hispanohablantes.
El contenido es para el blog de CodeHub (codehub-labs.blogspot.com), el portfolio de Wilson.E, 
desarrollador full stack de Guatemala (wilson360-labs.vercel.app).

REGLAS ESTRICTAS:
- Responde SOLO con HTML limpio listo para pegar en Blogger
- NO incluyas <!DOCTYPE>, <html>, <head>, <body>
- NO incluyas markdown, NO uses triple backtick
- Usa <pre><code class="language-javascript"> para código
- Cada <h2> debe tener contenido sustancial abajo
- Mínimo 800 palabras de contenido real
- El tono es profesional pero accesible: como un dev senior enseñando a un dev junior
- Menciona wilson360-labs.vercel.app como URL del sitio cuando sea natural
- NO generes contenido falso ni inventes APIs que no existan`;

  const res = await fetch(CONFIG.groqApiUrl, {
    method:  'POST',
    headers: {
      'Content-Type':  'application/json',
      'Authorization': `Bearer ${key}`,
    },
    body: JSON.stringify({
      model:       CONFIG.groqModel,
      max_tokens:  4096,
      temperature: 0.7,
      messages: [
        { role: 'system',  content: systemPrompt },
        { role: 'user',    content: topic.prompt },
      ],
    }),
  });

  if (!res.ok) {
    const err = await res.text();
    throw new Error(`Groq API error ${res.status}: ${err}`);
  }

  const data   = await res.json();
  const content = data.choices?.[0]?.message?.content?.trim();
  if (!content) throw new Error('Groq devolvió respuesta vacía');

  // Limpiar markdown residual si Groq lo inyecta
  return content
    .replace(/^```html\s*/i, '')
    .replace(/```$/,         '')
    .trim();
}

// ─────────────────────────────────────────────────────────────────────
// CONSTRUCCIÓN DEL POST COMPLETO
// ─────────────────────────────────────────────────────────────────────

function buildFullPost(topic, bodyHtml) {
  const today = new Date().toLocaleDateString('es-GT', {
    year: 'numeric', month: 'long', day: 'numeric'
  });

  // Header visual y structured data para el post
  const header = `
<div style="background:linear-gradient(135deg,#0d0d1e,#1a0a2e);border:1px solid rgba(16,185,129,.2);border-radius:12px;padding:1.5rem 2rem;margin-bottom:2rem;">
  <div style="font-family:'Courier New',monospace;font-size:.75rem;color:#10b981;margin-bottom:.5rem;letter-spacing:.08em;">// codehub-labs.blogspot.com · ${today}</div>
  <h1 style="font-size:1.6rem;font-weight:700;color:#f0f0fa;margin:0 0 .75rem;line-height:1.3;">${topic.title}</h1>
  <div style="display:flex;flex-wrap:wrap;gap:.5rem;">
    ${topic.tags.map(t => `<span style="background:rgba(16,185,129,.12);border:1px solid rgba(16,185,129,.3);color:#ff8c5a;font-size:.72rem;padding:.2rem .65rem;border-radius:999px;">${t}</span>`).join('')}
  </div>
</div>
`;

  // Footer con CTA y firma
  const footer = `
<hr style="border:none;border-top:1px solid rgba(16,185,129,.2);margin:2.5rem 0;">
<div style="background:linear-gradient(135deg,#0d0d1e,#1a0a2e);border:1px solid rgba(16,185,129,.2);border-radius:12px;padding:1.5rem 2rem;text-align:center;">
  <div style="font-size:1.1rem;font-weight:700;color:#f0f0fa;margin-bottom:.5rem;">Wilson.E — Desarrollador Web Full Stack</div>
  <div style="color:rgba(240,240,250,.6);font-size:.9rem;margin-bottom:1rem;">Guatemala 🇬🇹 · Disponible para proyectos freelance</div>
  <div style="display:flex;justify-content:center;gap:1rem;flex-wrap:wrap;">
    <a href="${CONFIG.siteUrl}" style="background:linear-gradient(135deg,#10b981,#34d399);color:#fff;text-decoration:none;padding:.5rem 1.2rem;border-radius:8px;font-size:.85rem;font-weight:700;">🚀 Ver CodeHub</a>
    <a href="${CONFIG.siteUrl}/tools" style="background:rgba(16,185,129,.12);border:1px solid rgba(16,185,129,.3);color:#ff8c5a;text-decoration:none;padding:.5rem 1.2rem;border-radius:8px;font-size:.85rem;">🛠 Herramientas</a>
    <a href="${CONFIG.blogUrl}" style="background:rgba(16,185,129,.12);border:1px solid rgba(16,185,129,.3);color:#ff8c5a;text-decoration:none;padding:.5rem 1.2rem;border-radius:8px;font-size:.85rem;">📝 Ver Blog</a>
  </div>
  <div style="margin-top:1rem;font-family:'Courier New',monospace;font-size:.7rem;color:rgba(240,240,250,.3);">
    wilson.e360labs@gmail.com · wilson360-labs.vercel.app
  </div>
</div>
`;

  return header + '\n' + bodyHtml + '\n' + footer;
}

// ─────────────────────────────────────────────────────────────────────
// PUBLICACIÓN EN BLOGGER API v3
// ─────────────────────────────────────────────────────────────────────

async function publishToBlogger(topic, htmlContent) {
  const token = process.env.BLOGGER_ACCESS_TOKEN;
  if (!token) throw new Error('BLOGGER_ACCESS_TOKEN no configurado en .env');

  console.log('📤 Publicando en Blogger...');

  const postData = {
    kind:    'blogger#post',
    title:   topic.title,
    content: htmlContent,
    labels:  topic.tags,
  };

  const res = await fetch(
    `${CONFIG.bloggerApiBase}/blogs/${CONFIG.blogId}/posts`,
    {
      method:  'POST',
      headers: {
        'Content-Type':  'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify(postData),
    }
  );

  if (!res.ok) {
    const err = await res.text();
    // Token expirado
    if (res.status === 401) {
      throw new Error(
        'TOKEN EXPIRADO (401). Renueva con:\n' +
        '  node refresh-token.js\n' +
        'o ve al README para instrucciones OAuth2.'
      );
    }
    throw new Error(`Blogger API error ${res.status}: ${err}`);
  }

  const post = await res.json();
  return {
    id:    post.id,
    url:   post.url,
    title: post.title,
  };
}

// ─────────────────────────────────────────────────────────────────────
// GUARDAR POST EN DISCO (dry-run y backup)
// ─────────────────────────────────────────────────────────────────────

function savePostLocally(topic, htmlContent) {
  const dir = path.join(__dirname, 'generated-posts');
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });

  const timestamp = new Date().toISOString().slice(0, 10);
  const filename  = `${timestamp}-${topic.slug}.html`;
  const filepath  = path.join(dir, filename);

  const fullHtml = `<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${topic.title}</title>
  <style>
    body { font-family: 'Segoe UI', sans-serif; max-width: 860px; margin: 2rem auto; padding: 0 1.5rem; background: #050510; color: #e8e8f0; line-height: 1.75; }
    h2 { color: #34d399; margin: 2rem 0 .75rem; }
    a  { color: #10b981; }
    code { background: rgba(255,255,255,.08); padding: .1rem .4rem; border-radius: 4px; font-family: 'Courier New', monospace; font-size: .9em; }
    pre  { background: #0d0d1e; border: 1px solid rgba(16,185,129,.2); border-radius: 8px; padding: 1.2rem; overflow-x: auto; }
    pre code { background: none; padding: 0; }
  </style>
</head>
<body>
${htmlContent}
</body>
</html>`;

  fs.writeFileSync(filepath, fullHtml);
  return filepath;
}

// ─────────────────────────────────────────────────────────────────────
// FLUJO PRINCIPAL
// ─────────────────────────────────────────────────────────────────────

async function run(options = {}) {
  const { dryRun = false, forceTopic = null } = options;
  const state = loadState();

  console.log('\n╔═══════════════════════════════════════════╗');
  console.log('║  CodeHub Blogger AutoPoster v1.0           ║');
  console.log('╚═══════════════════════════════════════════╝\n');
  console.log(`📊 Posts publicados: ${state.totalPosts} | Última vez: ${state.lastRun || 'nunca'}`);
  if (dryRun) console.log('🔵 MODO DRY-RUN — no se publicará en Blogger\n');

  // Seleccionar topic
  let topic;
  if (forceTopic) {
    topic = TOPICS.find(t => t.slug.includes(forceTopic) || t.title.toLowerCase().includes(forceTopic.toLowerCase()));
    if (!topic) {
      console.error(`❌ Topic "${forceTopic}" no encontrado.`);
      console.log('Topics disponibles:', TOPICS.map(t => t.slug).join(', '));
      process.exit(1);
    }
    console.log(`🎯 Topic forzado: ${topic.title}\n`);
  } else {
    topic = getNextTopic(state);
    console.log(`📌 Siguiente topic: ${topic.title}\n`);
  }

  try {
    // 1. Generar contenido
    const bodyHtml = await generatePostWithGroq(topic);
    console.log(`✅ Contenido generado: ${bodyHtml.length} caracteres\n`);

    // 2. Construir post completo
    const fullHtml = buildFullPost(topic, bodyHtml);

    // 3. Guardar copia local (siempre)
    const localPath = savePostLocally(topic, fullHtml);
    console.log(`💾 Post guardado localmente: ${localPath}`);

    if (dryRun) {
      console.log('\n🔵 DRY-RUN completado. Abre el archivo HTML para previsualizar.');
      console.log(`   → ${localPath}`);
      return;
    }

    // 4. Publicar en Blogger
    const published = await publishToBlogger(topic, fullHtml);
    console.log(`\n🎉 Post publicado exitosamente:`);
    console.log(`   📌 Título: ${published.title}`);
    console.log(`   🔗 URL:    ${published.url}`);
    console.log(`   🆔 ID:     ${published.id}`);

    // 5. Actualizar estado
    if (!forceTopic) {
      state.published.push(topic.slug);
    }
    state.lastRun   = new Date().toISOString();
    state.totalPosts++;
    saveState(state);

    console.log(`\n📊 Total posts publicados: ${state.totalPosts}`);
    console.log(`⏭  Siguiente topic: ${getNextTopic(state).title}\n`);

  } catch (err) {
    console.error(`\n❌ Error: ${err.message}\n`);
    if (err.message.includes('TOKEN EXPIRADO')) {
      console.log('💡 Ejecuta: node refresh-token.js\n');
    }
    process.exit(1);
  }
}

// ─────────────────────────────────────────────────────────────────────
// MODO SCHEDULE — Publica automáticamente cada N días
// ─────────────────────────────────────────────────────────────────────

async function runScheduled() {
  console.log(`🕐 Modo SCHEDULE activado — publicará cada ${CONFIG.scheduleMs / 86400000} días\n`);

  // Primera ejecución inmediata
  await run();

  // Luego cada N ms
  setInterval(async () => {
    console.log('\n⏰ Hora de publicar el siguiente post...');
    await run();
  }, CONFIG.scheduleMs);
}

// ─────────────────────────────────────────────────────────────────────
// CLI
// ─────────────────────────────────────────────────────────────────────

const args = process.argv.slice(2);
const isDryRun   = args.includes('--dry-run');
const isSchedule = args.includes('--schedule');
const topicIdx   = args.indexOf('--topic');
const forceTopic = topicIdx !== -1 ? args[topicIdx + 1] : null;

// Mostrar lista de topics
if (args.includes('--list')) {
  console.log('\n📚 Topics disponibles:\n');
  TOPICS.forEach((t, i) => {
    const state = loadState();
    const done  = state.published.includes(t.slug) ? '✅' : '⬜';
    console.log(`  ${done} ${i + 1}. [${t.slug}]`);
    console.log(`      ${t.title}\n`);
  });
  process.exit(0);
}

if (isSchedule) {
  runScheduled().catch(console.error);
} else {
  run({ dryRun: isDryRun, forceTopic }).catch(console.error);
}
