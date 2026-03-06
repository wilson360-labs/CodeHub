/* ═══════════════════════════════════════
   Downloader — Scripts
   CodeHub by Wilson.E
═══════════════════════════════════════ */

// ═══ CONFIGURACIÓN DE SERVICIOS POR PLATAFORMA ═════════════════
const PLATFORMS = {
  youtube: {
    name: 'YouTube',
    icon: '🎬',
    patterns: [/youtube\.com\/watch/i, /youtu\.be\//i, /youtube\.com\/shorts/i, /youtube\.com\/live/i],
    services: [
      {
        label: 'SSYouTube ⭐',
        sub: 'El mejor · HD hasta 4K · MP4',
        icon: 'fa-crown',
        url: u => { const id = extractYtId(u); return id ? 'https://ssyoutube.com/watch?v=' + id : 'https://ssyoutube.com/?url=' + encodeURIComponent(u); }
      },
      {
        label: 'YT1S · Video MP4',
        sub: 'Hasta 4K · rápido',
        icon: 'fa-video',
        url: u => 'https://yt1s.io/en68?url=' + encodeURIComponent(u)
      },
      {
        label: 'MP3 · Solo Audio',
        sub: 'Alta calidad · 320kbps',
        icon: 'fa-music',
        url: u => 'https://ytmp3.cc/en13/youtube-to-mp3/?url=' + encodeURIComponent(u)
      },
      {
        label: 'SaveFrom',
        sub: 'Multi-formato · confiable',
        icon: 'fa-download',
        url: u => 'https://en.savefrom.net/#url=' + encodeURIComponent(u)
      }
    ]
  },
  tiktok: {
    name: 'TikTok',
    icon: '🎵',
    patterns: [/tiktok\.com/i, /vm\.tiktok\.com/i],
    services: [
      {
        label: 'SnapTik ⭐',
        sub: 'Sin marca de agua · el mejor',
        icon: 'fa-crown',
        url: u => 'https://snaptik.app/en?url=' + encodeURIComponent(u)
      },
      {
        label: 'SSSTik',
        sub: 'Rápido · sin watermark',
        icon: 'fa-video',
        url: u => 'https://ssstik.io/en?url=' + encodeURIComponent(u)
      },
      {
        label: 'TikMate',
        sub: 'Video + audio separados',
        icon: 'fa-music',
        url: u => 'https://tikmate.online/?url=' + encodeURIComponent(u)
      },
      {
        label: 'SaveTT',
        sub: 'Batch · múltiples videos',
        icon: 'fa-layer-group',
        url: u => 'https://savett.cc/en?url=' + encodeURIComponent(u)
      }
    ]
  },
  instagram: {
    name: 'Instagram',
    icon: '📸',
    patterns: [/instagram\.com/i, /instagr\.am/i],
    services: [
      {
        label: 'SnapInsta ⭐',
        sub: 'Reels · posts · stories · el mejor',
        icon: 'fa-crown',
        url: u => 'https://snapinsta.app/en?url=' + encodeURIComponent(u)
      },
      {
        label: 'SaveIG',
        sub: 'Videos e imágenes HD',
        icon: 'fa-image',
        url: u => 'https://saveig.app/en?url=' + encodeURIComponent(u)
      },
      {
        label: 'IgDownloader',
        sub: 'Reels sin marca',
        icon: 'fa-video',
        url: u => 'https://igdownloader.app/en?url=' + encodeURIComponent(u)
      },
      {
        label: 'StorySaver',
        sub: 'Stories anónimas',
        icon: 'fa-clock',
        url: u => 'https://storysaver.net/?url=' + encodeURIComponent(u)
      }
    ]
  },
  facebook: {
    name: 'Facebook',
    icon: '👥',
    patterns: [/facebook\.com/i, /fb\.com/i, /fb\.watch/i],
    services: [
      {
        label: 'FDown ⭐',
        sub: 'HD y SD · el más confiable',
        icon: 'fa-crown',
        url: u => 'https://fdown.net/?url=' + encodeURIComponent(u)
      },
      {
        label: 'GetFVid',
        sub: 'Rápido · sin registro',
        icon: 'fa-bolt',
        url: u => 'https://www.getfvid.com/downloader?url=' + encodeURIComponent(u)
      },
      {
        label: 'SaveFrom',
        sub: 'Multi-formato',
        icon: 'fa-download',
        url: u => 'https://en.savefrom.net/#url=' + encodeURIComponent(u)
      },
      {
        label: 'FB Video Save',
        sub: 'Privados y públicos',
        icon: 'fa-lock-open',
        url: u => 'https://fbvideosave.net/?url=' + encodeURIComponent(u)
      }
    ]
  },
  twitter: {
    name: 'Twitter / X',
    icon: '𝕏',
    patterns: [/twitter\.com/i, /x\.com/i, /t\.co\//i],
    services: [
      {
        label: 'TWSave ⭐',
        sub: 'Videos y GIFs · el mejor',
        icon: 'fa-crown',
        url: u => 'https://twsave.com/?url=' + encodeURIComponent(u)
      },
      {
        label: 'SaveTweetVid',
        sub: 'Calidad original',
        icon: 'fa-video',
        url: u => 'https://savetweetvid.com/en?url=' + encodeURIComponent(u)
      },
      {
        label: 'XDownloader',
        sub: 'Multi-calidad · rápido',
        icon: 'fa-sliders',
        url: u => 'https://xdownloader.io/?url=' + encodeURIComponent(u)
      },
      {
        label: 'TwitterVid',
        sub: 'HD · GIFs animados',
        icon: 'fa-film',
        url: u => 'https://twittervid.com/?url=' + encodeURIComponent(u)
      }
    ]
  },
  pinterest: {
    name: 'Pinterest',
    icon: '📌',
    patterns: [/pinterest\.com/i, /pin\.it\//i],
    services: [
      {
        label: 'PinDown ⭐',
        sub: 'Videos e imágenes · el mejor',
        icon: 'fa-crown',
        url: u => 'https://pindown.net/?url=' + encodeURIComponent(u)
      },
      {
        label: 'PinterestDown',
        sub: 'HD rápido',
        icon: 'fa-video',
        url: u => 'https://pinterestdown.com/?url=' + encodeURIComponent(u)
      }
    ]
  },
  vimeo: {
    name: 'Vimeo',
    icon: '🎞️',
    patterns: [/vimeo\.com/i],
    services: [
      {
        label: 'SaveFrom ⭐',
        sub: 'HD · el mejor para Vimeo',
        icon: 'fa-crown',
        url: u => 'https://en.savefrom.net/#url=' + encodeURIComponent(u)
      },
      {
        label: 'VimeoDown',
        sub: 'Multi-calidad',
        icon: 'fa-video',
        url: u => 'https://vimeodownloader.net/?url=' + encodeURIComponent(u)
      }
    ]
  }
};

// ═══ HELPERS ════════════════════════════════════════════════════
function extractYtId(url) {
  const m = url.match(/(?:v=|youtu\.be\/|shorts\/|live\/)([A-Za-z0-9_-]{11})/);
  return m ? m[1] : '';
}
function extractIgId(url) {
  const m = url.match(/\/(p|reel|tv)\/([A-Za-z0-9_-]+)/);
  return m ? m[2] : '';
}

// ═══ DETECTAR PLATAFORMA ════════════════════════════════════════
let currentPlatform = null;
let currentUrl = '';

function detectPlatform(url) {
  currentUrl = url.trim();
  const platInfo = document.getElementById('plat-info');
  const platName = document.getElementById('plat-name');
  const platIcon = document.getElementById('plat-icon');
  const dlOptions = document.getElementById('dl-options');

  if (!currentUrl || !currentUrl.startsWith('http')) {
    platInfo.classList.remove('show');
    dlOptions.classList.remove('show');
    currentPlatform = null;
    return;
  }

  // Detectar
  currentPlatform = null;
  for (const [key, plat] of Object.entries(PLATFORMS)) {
    if (plat.patterns.some(p => p.test(currentUrl))) {
      currentPlatform = key;
      break;
    }
  }

  if (!currentPlatform) {
    platName.textContent = 'No reconocida';
    platIcon.textContent = '❓';
    platInfo.classList.add('show');
    platInfo.style.borderColor = 'rgba(255,71,87,.2)';
    platInfo.style.background = 'rgba(255,71,87,.05)';
    dlOptions.classList.remove('show');
    return;
  }

  const plat = PLATFORMS[currentPlatform];
  platName.textContent = plat.name;
  platIcon.textContent = plat.icon;
  platInfo.classList.add('show');
  platInfo.style.borderColor = 'rgba(0,230,118,.2)';
  platInfo.style.background = 'rgba(0,230,118,.06)';

  // Renderizar opciones
  dlOptions.innerHTML = plat.services.map(s => `
    <a class="dl-opt-btn" href="${s.url(currentUrl)}" target="_blank" rel="noopener noreferrer" onclick="toast('Abriendo ${s.sub.split('·')[0].trim()}…')">
      <div class="opt-label"><i class="fas ${s.icon}"></i> ${s.label}</div>
      <div class="opt-sub">${s.sub}</div>
    </a>
  `).join('');
  dlOptions.classList.add('show');
}

// ═══ BOTÓN PRINCIPAL ════════════════════════════════════════════
function goDownload() {
  const url = document.getElementById('url-input').value.trim();
  if (!url) { toast('⚠️ Pega un enlace primero'); return; }
  if (!url.startsWith('http')) { toast('⚠️ El enlace debe empezar con https://'); return; }

  if (!currentPlatform) {
    toast('❓ Plataforma no reconocida — prueba con savefrom.net');
    setTimeout(() => window.open('https://en.savefrom.net/#url=' + encodeURIComponent(url), '_blank'), 800);
    return;
  }

  // Abrir el primer servicio automáticamente
  const first = PLATFORMS[currentPlatform].services[0];
  window.open(first.url(currentUrl), '_blank', 'noopener,noreferrer');
  toast('✅ Abriendo ' + PLATFORMS[currentPlatform].name + ' downloader…');
}

// ═══ TOAST ══════════════════════════════════════════════════════
function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.add('on');
  clearTimeout(t._to);
  t._to = setTimeout(() => t.classList.remove('on'), 2200);
}

// ═══ SCROLL PROGRESS ════════════════════════════════════════════
window.addEventListener('scroll', () => {
  const s = document.documentElement.scrollTop;
  const h = document.documentElement.scrollHeight - window.innerHeight;
  document.getElementById('pbar').style.width = (s / h * 100) + '%';
});

// ═══ ENTER EN INPUT ═════════════════════════════════════════════
document.getElementById('url-input').addEventListener('keydown', e => {
  if (e.key === 'Enter') goDownload();
});

// Intentar leer URL del clipboard al llegar a la página
window.addEventListener('focus', async () => {
  if (document.getElementById('url-input').value) return;
  try {
    const text = await navigator.clipboard.readText();
    if (text.startsWith('http')) {
      document.getElementById('url-input').value = text;
      detectPlatform(text);
    }
  } catch(_) {}
});