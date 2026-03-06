/* ═══════════════════════════════════════
   index — AI Chat Assistant
   CodeHub by Wilson.E
═══════════════════════════════════════ */

// ═══════════════════════════════════════
//  ASISTENTE IA — CODEHUB CHAT
//  Usa la API de Anthropic via proxy seguro
//  Para activar: el panel funciona con
//  respuestas inteligentes en tiempo real.
// ═══════════════════════════════════════
(function() {
  const fab    = document.getElementById('ai-fab');
  const panel  = document.getElementById('ai-panel');
  const msgs   = document.getElementById('ai-msgs');
  const input  = document.getElementById('ai-input');
  const sendBtn= document.getElementById('ai-send-btn');
  const closeB = document.getElementById('ai-close-btn');
  const sugs   = document.getElementById('ai-sugs');
  const status = document.getElementById('ai-status');

  // Historial de conversación
  let history = [];
  let isOpen  = false;
  let loading = false;

  // Sistema: quién es el asistente
  const SYSTEM = `Eres el asistente de CodeHub, el portfolio de Wilson.E, desarrollador guatemalteco. 
Eres conciso, técnico y amigable. Respondes en español.
CodeHub tiene: portfolio, 18 herramientas web (QR, contraseñas, hash, regex, UUID, Pomodoro, etc.), 
una tienda de apps Android (novedades.html) y juegos (Snake, Tetris).
Cuando expliques código, usa bloques de código breves. Máximo 3-4 oraciones por respuesta salvo que pidan detalle.`;

  fab.addEventListener('click', toggle);
  closeB.addEventListener('click', () => setOpen(false));
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && isOpen) setOpen(false); });

  function toggle() { setOpen(!isOpen); }
  function setOpen(v) {
    isOpen = v;
    panel.classList.toggle('open', v);
    if (v) {
      input.focus();
      fab.querySelector('.ai-notif').style.display = 'none';
      scrollMsgs();
    }
  }

  window.aiSugg = function(btn) {
    input.value = btn.textContent;
    sendAI();
  };

  // ── CONFIGURACIÓN DEL SERVIDOR ────────────────────────────
  // Detecta automáticamente si estás en local o producción.
  // Solo cambia BACKEND_URL cuando tengas tu servidor con IP pública.
  const BACKEND_URL = (function() {
    const host = window.location.hostname;
    // Desarrollo local → Termux
    if (host === 'localhost' || host === '127.0.0.1' || host === '') {
      return 'http://localhost:3001';
    }
    // Producción → Railway
    return 'https://codehub-production-729d.up.railway.app';
  })();
  const API_URL = BACKEND_URL + '/api/chat';

  // ID de sesión único por visita (no identifica al usuario, solo la sesión)
  const SESSION_ID = 'sess_' + Math.random().toString(36).slice(2) + Date.now().toString(36);
  // ────────────────────────────────────────────────────────

  window.sendAI = async function() {
    const text = input.value.trim();
    if (!text || loading) return;

    // Anti-spam: máx 30 mensajes por sesión
    const chatCount = parseInt(sessionStorage.getItem('ch_chat_count') || '0');
    if (chatCount >= 30) {
      addMsg('⚠️ Límite de sesión alcanzado (30 mensajes). Recarga la página para continuar.', 'bot');
      return;
    }
    sessionStorage.setItem('ch_chat_count', chatCount + 1);

    // Limpiar sugerencias la primera vez
    if (sugs) sugs.remove();

    addMsg('user', text);
    input.value = '';
    input.style.height = 'auto';

    loading = true;
    sendBtn.disabled = true;
    status.textContent = '● Pensando…';
    const typingEl = addMsg('typing', '▌');

    try {
      const res = await fetch(API_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: text,
          sessionId: SESSION_ID
        })
      });

      const data = await res.json();

      if (!res.ok) {
        throw new Error(data.error || 'HTTP ' + res.status);
      }

      typingEl.remove();
      addMsg('ai', formatAIReply(data.reply));

    } catch (err) {
      typingEl.remove();
      // Fallback offline si el servidor no responde
      addMsg('ai', getFallback(text));
      console.warn('Chat error:', err.message);
    } finally {
      loading = false;
      sendBtn.disabled = false;
      status.textContent = '● Online · CodeHub AI';
      scrollMsgs();
    }
  };

  function addMsg(type, html) {
    const d = document.createElement('div');
    d.className = 'ai-msg ' + type;
    d.innerHTML = html;
    msgs.appendChild(d);
    scrollMsgs();
    return d;
  }

  function scrollMsgs() {
    requestAnimationFrame(() => msgs.scrollTop = msgs.scrollHeight);
  }

  function formatAIReply(text) {
    // Convertir markdown básico a HTML
    return text
      .replace(/```([\s\S]*?)```/g, '<code>$1</code>')
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/\n/g, '<br>');
  }

  // Respuestas offline inteligentes basadas en palabras clave
  function getFallback(q) {
    const ql = q.toLowerCase();
    if (ql.includes('qr'))       return 'El <strong>Generador de QR</strong> está en <code>tools.html</code>. Soporta 3 tamaños y 4 colores. Se puede descargar como PNG. 🔳';
    if (ql.includes('contraseña') || ql.includes('password')) return 'El <strong>Generador de Contraseñas</strong> usa <code>crypto.getRandomValues()</code> para máxima seguridad. Soporta hasta 64 caracteres con mayúsculas, números y símbolos. 🔐';
    if (ql.includes('uuid'))     return '<strong>UUID v4</strong> es un identificador único universal de 128 bits. Tiene ~5.3×10³⁶ combinaciones posibles. Lo encuentras en Tools → Generador de UUID. 🎲';
    if (ql.includes('base64'))   return '<strong>Base64</strong> codifica datos binarios en texto ASCII. Se usa en emails, URLs y tokens. En Tools puedes codificar y decodificar al instante. 💻';
    if (ql.includes('hash'))     return 'Los <strong>hashes</strong> son funciones de una sola vía. <code>SHA-256</code> genera 256 bits, ideal para verificar integridad. Pruébalo en Tools → Generador de Hash. 🔒';
    if (ql.includes('python'))   return '<strong>Tip Python:</strong> usa <code>enumerate()</code> en lugar de <code>range(len())</code> para iterar con índice. Más pythonico y legible. 🐍';
    if (ql.includes('pomodoro')) return 'La técnica <strong>Pomodoro</strong> divide el trabajo en bloques de 25 min con 5 min de descanso. Está en Tools con notificaciones del navegador. 🍅';
    if (ql.includes('herramienta') || ql.includes('tool')) return 'CodeHub tiene <strong>18 herramientas</strong>: QR, contraseñas, hash SHA-256/512, Base64, Regex tester, UUID, Pomodoro, convertidor de unidades, monedas, IMC, préstamos, velocidad de internet y más. 🛠️';
    if (ql.includes('app') || ql.includes('android')) return 'La <strong>tienda de apps</strong> tiene Spotify Premium, YouTube ReVanced, TikTok Mod, Remini Pro y más. Todo en <code>novedades.html</code>. Solo para Android. 📱';
    if (ql.includes('juego') || ql.includes('snake') || ql.includes('tetris')) return 'Los juegos <strong>Snake</strong> y <strong>Tetris</strong> están implementados con Canvas API. Puedes jugarlos desde la sección de Proyectos. 🎮';
    if (ql.includes('contact') || ql.includes('wilson')) return '<strong>Wilson.E</strong> es desarrollador guatemalteco disponible para freelance. Contacto: WhatsApp +502 4146 8185 o via el formulario de contacto. 💬';
    return 'Entendido. Para preguntas técnicas detalladas, la conexión a la IA requiere configuración del servidor. Mientras tanto, puedo responder sobre las herramientas de CodeHub. ¿Qué quieres saber? 🤖';
  }

  // Mostrar notificación del FAB después de 8 segundos
  setTimeout(() => {
    if (!isOpen) fab.querySelector('.ai-notif').style.display = 'block';
  }, 8000);
})();

// ═══ SKILLS TABS ══════════════════════════════════════════════════
function switchSkillTab(tab, btn) {
    document.querySelectorAll('.sk-tab').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    ['sk-core','sk-2025','sk-tools'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.classList.toggle('hidden', id !== 'sk-' + tab);
    });
}

// ═══ CONFIGURACIÓN DEL SITIO ══════════════════════════════════════
function openCfg() {
    const p = document.getElementById('config-panel');
    p.classList.add('open');
    p.setAttribute('aria-hidden','false');
    loadCfg();
}
function closeCfgPanel() {
    const p = document.getElementById('config-panel');
    p.classList.remove('open');
    p.setAttribute('aria-hidden','true');
}

document.getElementById('cfg-close')?.addEventListener('click', closeCfgPanel);
document.getElementById('config-panel')?.addEventListener('click', e => {
    if (e.target === document.getElementById('config-panel')) closeCfgPanel();
});
document.addEventListener('keydown', e => {
    if (e.key === 'Escape') closeCfgPanel();
});

function setCfg(key, val, btn) {
    // Desactivar otros del grupo
    btn.closest('.cfg-options').querySelectorAll('.cfg-opt').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    localStorage.setItem('cfg_' + key, val);

    if (key === 'theme') {
        if (val === 'dark')  document.body.classList.remove('light-mode');
        if (val === 'light') document.body.classList.add('light-mode');
        if (val === 'auto') {
            const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
            document.body.classList.toggle('light-mode', !dark);
        }
    }
    if (key === 'font') {
        document.body.classList.remove('font-sm','font-lg');
        if (val !== 'md') document.body.classList.add('font-' + val);
    }
    if (key === 'lang') {
        // Placeholder — expansión futura
        console.log('Idioma:', val);
    }
}

function toggleAnim(chk) {
    localStorage.setItem('cfg_anim', chk.checked ? '1' : '0');
    document.body.classList.toggle('no-anim', !chk.checked);
}
function toggleNeural(chk) {
    localStorage.setItem('cfg_neural', chk.checked ? '1' : '0');
    const canvas = document.getElementById('neural-network');
    if (canvas) canvas.style.opacity = chk.checked ? '1' : '0';
}

function loadCfg() {
    const theme  = localStorage.getItem('cfg_theme')  || 'dark';
    const font   = localStorage.getItem('cfg_font')   || 'md';
    const anim   = localStorage.getItem('cfg_anim')   !== '0';
    const neural = localStorage.getItem('cfg_neural') !== '0';
    const lang   = localStorage.getItem('cfg_lang')   || 'es';

    // Marcar botones activos
    document.querySelectorAll('[onclick*="setCfg(\'theme\'"]').forEach(b => {
        b.classList.toggle('active', b.getAttribute('onclick').includes("'"+theme+"'"));
    });
    document.querySelectorAll('[onclick*="setCfg(\'font\'"]').forEach(b => {
        b.classList.toggle('active', b.getAttribute('onclick').includes("'"+font+"'"));
    });
    document.querySelectorAll('[onclick*="setCfg(\'lang\'"]').forEach(b => {
        b.classList.toggle('active', b.getAttribute('onclick').includes("'"+lang+"'"));
    });

    const animChk = document.getElementById('cfg-anim');
    if (animChk) animChk.checked = anim;
    const neuralChk = document.getElementById('cfg-neural');
    if (neuralChk) neuralChk.checked = neural;

    // Aplicar al cargar
    document.body.classList.toggle('no-anim', !anim);
    document.body.classList.remove('font-sm','font-lg');
    if (font !== 'md') document.body.classList.add('font-' + font);
    const canvas = document.getElementById('neural-network');
    if (canvas) canvas.style.opacity = neural ? '1' : '0';
}

// Aplicar config guardada al cargar la página
(function() {
    const font   = localStorage.getItem('cfg_font');
    const anim   = localStorage.getItem('cfg_anim');
    const neural = localStorage.getItem('cfg_neural');
    if (font && font !== 'md') document.body.classList.add('font-' + font);
    if (anim === '0') document.body.classList.add('no-anim');
    const canvas = document.getElementById('neural-network');
    if (canvas && neural === '0') canvas.style.opacity = '0';
})();



// ═══════════════════════════════════════════════════════════
//  REPRODUCTOR DE MÚSICA AMBIENT
// ═══════════════════════════════════════════════════════════
// ═══════════════════════════════════════════════════════════
//  PROTECCIONES DEL SITIO
// ═══════════════════════════════════════════════════════════

// 1. Anti clic derecho
document.addEventListener('contextmenu', (e) => {
  e.preventDefault();
  // Mostrar toast personalizado en lugar de menú
  if (typeof toast === 'function') {
    toast('🔒 Contenido protegido — Wilson.E © 2025');
  }
});

// 2. Anti selección de texto en secciones clave
const protectedSelectors = ['#hero', '#mi-pueblo', '.project-card', '.skill-chip', 'footer'];
protectedSelectors.forEach(sel => {
  document.querySelectorAll(sel).forEach(el => {
    el.style.userSelect = 'none';
    el.style.webkitUserSelect = 'none';
  });
});

// 3. Anti keyboard shortcuts de DevTools
document.addEventListener('keydown', (e) => {
  // Bloquear F12, Ctrl+Shift+I, Ctrl+Shift+J, Ctrl+U (ver fuente)
  if (
    e.key === 'F12' ||
    (e.ctrlKey && e.shiftKey && ['I','J','C'].includes(e.key.toUpperCase())) ||
    (e.ctrlKey && e.key.toLowerCase() === 'u')
  ) {
    e.preventDefault();
    if (typeof toast === 'function') toast('🔒 Acceso restringido');
    return false;
  }
  // Bloquear Ctrl+C en zonas protegidas
  if (e.ctrlKey && e.key.toLowerCase() === 'c') {
    const sel = window.getSelection()?.toString();
    if (sel && sel.length > 50) {
      e.preventDefault();
      if (typeof toast === 'function') toast('🔒 Copia restringida — © Wilson.E 2025');
    }
  }
});

// 4. Anti-DevTools — detectar si están abiertos por tamaño de ventana
(function detectDevTools() {
  const threshold = 160;
  let devOpen = false;

  function check() {
    const widthDiff  = window.outerWidth  - window.innerWidth;
    const heightDiff = window.outerHeight - window.innerHeight;
    const isOpen = widthDiff > threshold || heightDiff > threshold;

    if (isOpen && !devOpen) {
      devOpen = true;
      document.getElementById('devtools-warning')?.classList.add('show');
    } else if (!isOpen && devOpen) {
      devOpen = false;
      document.getElementById('devtools-warning')?.classList.remove('show');
    }
  }

  setInterval(check, 1000);
})();

// 5. Ofuscación básica — deshabilitar vista de fuente via URL
// (Nota: esto disuade usuarios casuales, no a desarrolladores avanzados)
if (window.location.href.includes('view-source:')) {
  window.location.href = 'https://wilson360-labs.vercel.app';
}

// ═══════════════════════════════════════════════════════════
//  CLIMA — OpenWeatherMap (más preciso)
//  NOTA: Reemplaza 'OWM_API_KEY_PLACEHOLDER' con tu key real
//  de openweathermap.org → API Keys
// ═══════════════════════════════════════════════════════════
const OWM_KEY = 'OWM_API_KEY_PLACEHOLDER';

async function fetchWeatherByCoords(lat, lon, cityName) {
  try {
    let data, city;

    if (OWM_KEY !== 'OWM_API_KEY_PLACEHOLDER') {
      // ── OpenWeatherMap (preciso, datos en español) ──
      const res = await fetch(
        `https://api.openweathermap.org/data/2.5/weather?lat=${lat}&lon=${lon}&appid=${OWM_KEY}&units=metric&lang=es`
      );
      data = await res.json();

      if (data.cod !== 200 && data.cod !== '200') throw new Error(data.message);

      const icon = getOWMIcon(data.weather[0].id, data.weather[0].icon);
      city = cityName || `${data.name}, ${data.sys.country}`;

      document.getElementById('wx-city').textContent   = city;
      document.getElementById('wx-desc').textContent   = `${icon} ${capitalize(data.weather[0].description)}`;
      document.getElementById('wx-temp').textContent   = `${Math.round(data.main.temp)}°`;
      document.getElementById('wx-feels').textContent  = `Sensación térmica: ${Math.round(data.main.feels_like)}°C`;
      document.getElementById('wx-hum').textContent    = `${data.main.humidity}%`;
      document.getElementById('wx-wind').textContent   = `${Math.round(data.wind.speed * 3.6)} km/h`;
      document.getElementById('wx-precip').textContent = data.rain?.['1h'] ? `${data.rain['1h']} mm` : '0 mm';
      document.getElementById('wx-updated').textContent= new Date().toLocaleTimeString('es-GT',{hour:'2-digit',minute:'2-digit'});

    } else {
      // ── Fallback: Open-Meteo (sin key) ──
      const res = await fetch(
        `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation&wind_speed_unit=kmh&timezone=auto`
      );
      const d = await res.json();
      const c = d.current;
      const WX = {0:'☀️ Despejado',1:'🌤️ Mayormente despejado',2:'⛅ Parcialmente nublado',3:'☁️ Nublado',45:'🌫️ Niebla',51:'🌦️ Llovizna',61:'🌧️ Lluvia',80:'🌦️ Chubascos',95:'⛈️ Tormenta'};

      document.getElementById('wx-city').textContent   = cityName || 'Tu ubicación';
      document.getElementById('wx-desc').textContent   = WX[c.weather_code] || '🌡️ Variable';
      document.getElementById('wx-temp').textContent   = `${Math.round(c.temperature_2m)}°`;
      document.getElementById('wx-feels').textContent  = `Sensación térmica: ${Math.round(c.apparent_temperature)}°C`;
      document.getElementById('wx-hum').textContent    = `${c.relative_humidity_2m}%`;
      document.getElementById('wx-wind').textContent   = `${Math.round(c.wind_speed_10m)} km/h`;
      document.getElementById('wx-precip').textContent = `${c.precipitation} mm`;
      document.getElementById('wx-updated').textContent= new Date().toLocaleTimeString('es-GT',{hour:'2-digit',minute:'2-digit'});
    }

    document.getElementById('wx-loading').style.display = 'none';
    document.getElementById('wx-result').style.display  = 'block';
    document.getElementById('wx-error').style.display   = 'none';

  } catch(e) {
    console.warn('fetchWeatherByCoords error:', e.message);
    showWeatherError('No se pudo obtener el clima. Intenta buscar tu ciudad manualmente.');
  }
}

function getOWMIcon(id, icon) {
  const isDay = icon && icon.endsWith('d');
  if (id >= 200 && id < 300) return '⛈️';
  if (id >= 300 && id < 400) return '🌦️';
  if (id >= 500 && id < 600) return id >= 502 ? '🌧️' : '🌦️';
  if (id >= 600 && id < 700) return '❄️';
  if (id >= 700 && id < 800) return '🌫️';
  if (id === 800) return isDay ? '☀️' : '🌙';
  if (id === 801) return isDay ? '🌤️' : '🌥️';
  if (id <= 804) return '☁️';
  return '🌡️';
}

function capitalize(s) { return s ? s.charAt(0).toUpperCase() + s.slice(1) : s; }

async function detectWeatherByIP() {
  try {
    // Prioridad 1: ipapi.co
    const r1 = await fetch('https://ipapi.co/json/');
    const g1 = await r1.json();
    if (g1.latitude && !g1.error) {
      const flag = g1.country_code === 'GT' ? ' 🇬🇹' : '';
      await fetchWeatherByCoords(g1.latitude, g1.longitude, `${g1.city}, ${g1.country_name}${flag}`);
      return;
    }
  } catch(e) {}
  try {
    // Prioridad 2: ip-api.com
    const r2 = await fetch('https://ip-api.com/json/?fields=lat,lon,city,country,countryCode,status');
    const g2 = await r2.json();
    if (g2.status === 'success' && g2.lat) {
      const flag = g2.countryCode === 'GT' ? ' 🇬🇹' : '';
      await fetchWeatherByCoords(g2.lat, g2.lon, `${g2.city}, ${g2.country}${flag}`);
      return;
    }
  } catch(e) {}
  showWeatherError('No se pudo detectar tu ubicación. Escribe tu ciudad abajo.');
}

function requestGeoLocation() {
  const btn = document.getElementById('wx-loc-btn');
  if (btn) { btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Detectando…'; btn.disabled = true; }

  if (!navigator.geolocation) { showWeatherError('Tu navegador no soporta geolocalización.'); return; }

  navigator.geolocation.getCurrentPosition(
    async (pos) => {
      try {
        const { latitude: lat, longitude: lon } = pos.coords;
        // Geocodificación inversa con Nominatim (OpenStreetMap, gratis)
        const r = await fetch(`https://nominatim.openstreetmap.org/reverse?lat=${lat}&lon=${lon}&format=json&accept-language=es`);
        const d = await r.json();
        const city = d.address?.city || d.address?.town || d.address?.village || d.address?.county || 'Tu ubicación';
        const country = d.address?.country || '';
        await fetchWeatherByCoords(lat, lon, `${city}, ${country} 📍`);
        if (btn) { btn.innerHTML = '<i class="fas fa-location-dot"></i> Ubicación exacta ✅'; btn.style.color='#00e676'; }
      } catch(e) {
        await fetchWeatherByCoords(pos.coords.latitude, pos.coords.longitude, 'Tu ubicación 📍');
      }
      if (btn) btn.disabled = false;
    },
    () => { detectWeatherByIP(); if (btn) { btn.innerHTML = '<i class="fas fa-location-dot"></i> Usar mi ubicación'; btn.disabled = false; } },
    { timeout: 10000 }
  );
}

async function searchWeatherCity() {
  const val = document.getElementById('wx-search-input')?.value.trim();
  if (val) await searchWeatherByName(val);
}
async function searchWeatherManual() {
  const val = document.getElementById('wx-manual-input')?.value.trim();
  if (val) await searchWeatherByName(val);
}
async function searchWeatherByName(name) {
  document.getElementById('wx-loading').style.display = 'flex';
  document.getElementById('wx-result').style.display  = 'none';
  document.getElementById('wx-error').style.display   = 'none';
  try {
    if (OWM_KEY !== 'OWM_API_KEY_PLACEHOLDER') {
      const r = await fetch(`https://api.openweathermap.org/geo/1.0/direct?q=${encodeURIComponent(name)}&limit=1&appid=${OWM_KEY}`);
      const d = await r.json();
      if (!d.length) { showWeatherError(`Ciudad "${name}" no encontrada.`); return; }
      await fetchWeatherByCoords(d[0].lat, d[0].lon, `${d[0].local_names?.es || d[0].name}, ${d[0].country}`);
    } else {
      const r = await fetch(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(name)}&count=1&language=es`);
      const d = await r.json();
      if (!d.results?.length) { showWeatherError(`Ciudad "${name}" no encontrada.`); return; }
      const g = d.results[0];
      await fetchWeatherByCoords(g.latitude, g.longitude, `${g.name}, ${g.country}`);
    }
  } catch(e) { showWeatherError('Error al buscar la ciudad.'); }
}

function showWeatherError(msg) {
  document.getElementById('wx-loading').style.display  = 'none';
  document.getElementById('wx-result').style.display   = 'none';
  document.getElementById('wx-error').style.display    = 'flex';
  const errMsg = document.getElementById('wx-error-msg');
  if (errMsg) errMsg.textContent = msg;
}

// ═══════════════════════════════════════════════════════════
//  NOTICIAS EN EL INDEX
// ═══════════════════════════════════════════════════════════

const INDEX_NEWS = {
  technology: [
    { title:'OpenAI lanza nuevas capacidades de razonamiento en GPT', source:'TechCrunch', time:'hace 2h', url:'https://techcrunch.com', icon:'🤖' },
    { title:'Apple anuncia chips M4 para toda la línea Mac', source:'The Verge', time:'hace 4h', url:'https://theverge.com', icon:'🍎' },
    { title:'Google actualiza Android con nuevas funciones de IA', source:'9to5Google', time:'hace 6h', url:'https://9to5google.com', icon:'📱' },
    { title:'Microsoft integra Copilot en más productos de Office', source:'ZDNet', time:'hace 8h', url:'https://zdnet.com', icon:'💼' },
    { title:'Meta lanza Llama 4 como modelo open source gratuito', source:'VentureBeat', time:'hace 10h', url:'https://venturebeat.com', icon:'🦙' },
    { title:'Groq lanza nuevos modelos con velocidad récord de inferencia', source:'Groq Blog', time:'hace 12h', url:'https://groq.com', icon:'⚡' },
  ],
  gaming: [
    { title:'GTA VI confirma fecha de lanzamiento para 2025', source:'IGN', time:'hace 1h', url:'https://ign.com', icon:'🎮' },
    { title:'Steam rompe récord con 35 millones de usuarios simultáneos', source:'PC Gamer', time:'hace 3h', url:'https://pcgamer.com', icon:'💻' },
    { title:'Nintendo Switch 2 supera ventas de lanzamiento', source:'GameSpot', time:'hace 5h', url:'https://gamespot.com', icon:'🕹️' },
    { title:'Valorant Mobile llega finalmente a Android e iOS', source:'Riot Games', time:'hace 7h', url:'https://playvalorant.com', icon:'🔫' },
    { title:'Minecraft supera los 300 millones de copias vendidas', source:'Mojang', time:'hace 9h', url:'https://minecraft.net', icon:'⛏️' },
    { title:'The Game Awards 2025 anuncia los nominados oficiales', source:'TGA', time:'hace 11h', url:'https://thegameawards.com', icon:'🏆' },
  ],
  science: [
    { title:'James Webb descubre galaxia más antigua conocida', source:'NASA', time:'hace 2h', url:'https://nasa.gov', icon:'🔭' },
    { title:'Científicos logran fusión nuclear con ganancia de energía neta', source:'Nature', time:'hace 5h', url:'https://nature.com', icon:'⚡' },
    { title:'IA predice estructura de proteínas con 99% de precisión', source:'DeepMind', time:'hace 7h', url:'https://deepmind.com', icon:'🧬' },
    { title:'Primera misión tripulada a Marte planificada para 2030', source:'SpaceX', time:'hace 10h', url:'https://spacex.com', icon:'🚀' },
    { title:'Nuevo material superconductor funciona a temperatura ambiente', source:'Science', time:'hace 12h', url:'https://science.org', icon:'🔬' },
    { title:'Descubren posible señal de vida en exoplaneta K2-18b', source:'ESA', time:'hace 14h', url:'https://esa.int', icon:'🌍' },
  ],
  general: [
    { title:'Economía global muestra signos de recuperación sostenida', source:'Reuters', time:'hace 1h', url:'https://reuters.com', icon:'📈' },
    { title:'OMS aprueba nueva vacuna para enfermedades tropicales', source:'WHO', time:'hace 3h', url:'https://who.int', icon:'💉' },
    { title:'Guatemala avanza en proyectos de infraestructura digital', source:'Prensa Libre', time:'hace 4h', url:'https://prensalibre.com', icon:'🇬🇹' },
    { title:'Energías renovables superan al carbón por primera vez', source:'BBC', time:'hace 6h', url:'https://bbc.com', icon:'🌱' },
    { title:'Récord de turismo en Centroamérica durante 2025', source:'UNWTO', time:'hace 8h', url:'https://unwto.org', icon:'✈️' },
    { title:'Inteligencia artificial transforma el mercado laboral global', source:'WEF', time:'hace 10h', url:'https://weforum.org', icon:'🤖' },
  ],
};

function loadIndexNews(category, btn) {
  document.querySelectorAll('.news-idx-tab').forEach(b => b.classList.remove('active-idx'));
  if (btn) btn.classList.add('active-idx');

  const out = document.getElementById('news-index-out');
  const items = INDEX_NEWS[category] || [];

  out.innerHTML = items.map(n => `
    <a href="${n.url}" target="_blank" rel="noopener noreferrer" class="news-idx-card">
      <span class="news-idx-icon">${n.icon}</span>
      <div>
        <div class="news-idx-title">${n.title}</div>
        <div class="news-idx-meta">${n.source} · ${n.time}</div>
      </div>
    </a>`).join('');
}

// Cargar noticias tech al iniciar
window.addEventListener('load', () => {
  loadIndexNews('technology', document.querySelector('.news-idx-tab'));
});


// ═══════════════════════════════════════════════════════════
//  BURGER MENU MOBILE
// ═══════════════════════════════════════════════════════════
function toggleMobileNav() {
    const nav = document.getElementById('mobile-nav');
    const btn = document.getElementById('burger-btn');
    const isOpen = nav.classList.contains('open');
    if (isOpen) {
        nav.classList.remove('open');
        btn.classList.remove('open');
        document.body.style.overflow = '';
    } else {
        nav.classList.add('open');
        btn.classList.add('open');
        document.body.style.overflow = 'hidden';
    }
}
function closeMobileNav() {
    document.getElementById('mobile-nav')?.classList.remove('open');
    document.getElementById('burger-btn')?.classList.remove('open');
    document.body.style.overflow = '';
}
// Cerrar al hacer scroll
window.addEventListener('scroll', () => {
    if (document.getElementById('mobile-nav')?.classList.contains('open')) {
        closeMobileNav();
    }
}, { passive: true });
// Cerrar al tocar fuera del menú
document.addEventListener('click', (e) => {
    const nav = document.getElementById('mobile-nav');
    const btn = document.getElementById('burger-btn');
    if (nav?.classList.contains('open') && !nav.contains(e.target) && !btn.contains(e.target)) {
        closeMobileNav();
    }
});


// ── SERVICE WORKER (PWA) ────────────────────────────────
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js')
      .then(() => console.log('SW registrado ✅'))
      .catch(e => console.log('SW error:', e));
  });
}


// ════════════════════════════════════════════════════════════
//  EASTER EGG — 7 clicks en el logo
// ════════════════════════════════════════════════════════════
let eggClicks = 0, eggTimer = null;
function eggClick(e) {
    e.preventDefault();
    eggClicks++;
    const counter = document.getElementById('egg-counter');
    if (counter) { counter.style.display = 'flex'; counter.textContent = eggClicks; }

    clearTimeout(eggTimer);
    eggTimer = setTimeout(() => {
        eggClicks = 0;
        if (counter) counter.style.display = 'none';
    }, 2000);

    if (eggClicks >= 7) {
        eggClicks = 0;
        if (counter) counter.style.display = 'none';
        activateEgg();
    }
}

function activateEgg() {
    const overlay = document.getElementById('egg-overlay');
    overlay.classList.add('active');
    spawnParticles();
    startMatrix();
    // Confetti sonoro simulado con vibración
    if (navigator.vibrate) navigator.vibrate([100,50,100,50,200]);
}

function closeEgg() {
    document.getElementById('egg-overlay').classList.remove('active');
    document.getElementById('egg-matrix').textContent = '';
}

function spawnParticles() {
    const container = document.getElementById('egg-particles');
    container.innerHTML = '';
    const colors = ['#ff4500','#00e5ff','#ffbd69','#00e676','#a855f7','#ff6b35'];
    for (let i = 0; i < 60; i++) {
        const p = document.createElement('div');
        p.className = 'egg-particle';
        p.style.cssText = `
            left: ${Math.random()*100}%;
            top: ${Math.random()*100}%;
            background: ${colors[Math.floor(Math.random()*colors.length)]};
            --tx: ${(Math.random()-0.5)*400}px;
            --ty: ${(Math.random()-0.5)*400}px;
            animation-delay: ${Math.random()*0.5}s;
            animation-duration: ${1.5+Math.random()}s;
            width: ${2+Math.random()*6}px;
            height: ${2+Math.random()*6}px;
        `;
        container.appendChild(p);
    }
}

function startMatrix() {
    const el = document.getElementById('egg-matrix');
    const chars = '01アイウエオカキクケコWilson.ECodeHubUltra</>{}[]';
    let txt = '';
    for (let i = 0; i < 800; i++) txt += chars[Math.floor(Math.random()*chars.length)] + (i%80===79?'\n':' ');
    el.textContent = txt;
}

// Cerrar egg con Escape
document.addEventListener('keydown', e => {
    if (e.key === 'Escape') closeEgg();
});

// ════════════════════════════════════════════════════════════
//  MULTIIDIOMA ES / EN
// ════════════════════════════════════════════════════════════
const i18n = {
    es: {
        available: 'Disponible',
        years: 'Años',
        projects: 'Proyectos',
        role: 'Desarrollador Web Full Stack · Guatemala 🇬🇹',
        bio: 'Programador autodidacta de <strong>Ciudad de Guatemala</strong>, 25 años. Me apasiona construir proyectos web modernos, chatbots con IA y herramientas útiles para la comunidad. Cada línea de código es una oportunidad de aprender algo nuevo.',
        from: 'Originario de <strong>San Luis Jilotepeque</strong>, Jalapa — donde aprendí a soñar en grande.',
        viewCV: 'Ver CV completo',
        contact: 'Contactar',
        // Nav
        navStats: 'Estadísticas', navSkills: 'Habilidades', navServices: 'Servicios',
        navExp: 'Experiencia', navProjects: 'Proyectos', navContact: 'Contacto',
    },
    en: {
        available: 'Available',
        years: 'Years',
        projects: 'Projects',
        role: 'Full Stack Web Developer · Guatemala 🇬🇹',
        bio: 'Self-taught developer from <strong>Guatemala City</strong>, 25 years old. Passionate about building modern web projects, AI chatbots and useful tools for the community. Every line of code is a chance to learn something new.',
        from: 'Originally from <strong>San Luis Jilotepeque</strong>, Jalapa — where I learned to dream big.',
        viewCV: 'View full CV',
        contact: 'Contact me',
        navStats: 'Statistics', navSkills: 'Skills', navServices: 'Services',
        navExp: 'Experience', navProjects: 'Projects', navContact: 'Contact',
    }
};

let currentLang = localStorage.getItem('ch_lang') || 'es';

function applyLang(lang) {
    currentLang = lang;
    localStorage.setItem('ch_lang', lang);
    const dict = i18n[lang];
    // Traducir elementos con data-i18n
    document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.dataset.i18n;
        if (dict[key] !== undefined) el.innerHTML = dict[key];
    });
    // Actualizar botón
    const btn = document.getElementById('lang-toggle');
    if (btn) btn.textContent = lang === 'es' ? '🌐 EN' : '🌐 ES';
    // Cambiar lang del html
    document.documentElement.lang = lang;
}

function toggleLang() {
    applyLang(currentLang === 'es' ? 'en' : 'es');
}

// Aplicar idioma al cargar
document.addEventListener('DOMContentLoaded', () => {
    applyLang(currentLang);
});