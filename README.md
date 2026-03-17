<div align="center">

# `<Wilson.E/>` — CodeHub

**Portfolio profesional · 27 herramientas web · Apps Android · Chat IA**

[![Deploy](https://img.shields.io/badge/Deploy-Vercel-black?logo=vercel)](https://wilson360-labs.vercel.app)
[![Backend](https://img.shields.io/badge/Backend-Railway-purple?logo=railway)](https://codehub-production-729d.up.railway.app/api/health)
[![License](https://img.shields.io/badge/License-MIT-orange)](LICENSE)
[![PWA](https://img.shields.io/badge/PWA-Ready-blue)](https://wilson360-labs.vercel.app)

[🌐 Ver sitio](https://wilson360-labs.vercel.app) · [🛠️ Tools](https://wilson360-labs.vercel.app/tools.html) · [📱 Apps](https://wilson360-labs.vercel.app/novedades.html) · [💼 Servicios](https://wilson360-labs.vercel.app/servicios.html)

</div>

---

## 🚀 ¿Qué es CodeHub?

Portfolio de **Wilson.E**, desarrollador web full stack de Guatemala. Incluye:

- **27 herramientas web** gratuitas (contraseñas, QR, clima, traductor, imagen IA, y más)
- **Apps Android premium** con sistema de descargas, ratings y solicitudes
- **Chat IA** powered by Groq (LLaMA 3.3 70B) con fallback a Gemini
- **Descargador de videos** de redes sociales
- **PWA** instalable con soporte offline y notificaciones en tiempo real

## 🛠️ Stack

| Layer | Tecnología |
|-------|-----------|
| Frontend | HTML5, CSS3, JavaScript (Vanilla) |
| Backend | Node.js, Express.js v2.0 |
| Base de datos | MongoDB Atlas |
| Storage APKs | Backblaze B2 |
| IA | Groq (LLaMA 3.3 70B) + Gemini fallback |
| Deploy Frontend | Vercel (auto-deploy) |
| Deploy Backend | Railway |
| PWA | Service Worker v3.0 |
| Bot | Python + Telegram API |
| CI/CD | GitHub Actions |

## 📁 Estructura

```
CodeHub/
├── index.html          # Portfolio principal
├── tools.html          # 27 herramientas web
├── novedades.html      # Apps Android
├── servicios.html      # Servicios freelance
├── downloader.html     # Descargador de videos
├── cv.html             # Hoja de vida
├── sw.js               # Service Worker v3.0
├── manifest.json       # PWA Manifest
├── vercel.json         # Headers seguridad + caché
├── sitemap.xml         # SEO Sitemap
├── robots.txt          # SEO Robots
├── python/
│   ├── codehub.py      # AutoScript (setup/deploy/health/backup)
│   ├── telegram_bot.py # Bot resumen diario
│   └── requirements.txt
├── .github/workflows/
│   ├── deploy.yml      # CI/CD en cada push
│   ├── daily_report.yml# Bot Telegram 9PM GT
│   └── autoscript.yml  # AutoScript lunes 8AM GT
├── css/                # Estilos
├── js/                 # Scripts
└── backend/
    ├── server.js       # API REST
    ├── package.json
    └── railway.json
```

## ⚡ Variables de Entorno (GitHub Secrets)

```env
GROQ_API_KEY        # groq.com
GEMINI_API_KEY      # aistudio.google.com (fallback)
MONGODB_URI         # MongoDB Atlas
FRONTEND_URL        # https://wilson360-labs.vercel.app
ADMIN_KEY           # Clave del panel admin
B2_KEY_ID           # Backblaze B2
B2_APP_KEY          # Backblaze B2
B2_BUCKET_ID        # Backblaze B2
B2_BUCKET_NAME      # Backblaze B2
TELEGRAM_TOKEN      # Bot Telegram
TELEGRAM_CHAT_ID    # Tu chat ID
BACKEND_URL         # https://codehub-production-729d.up.railway.app
```

## 🤖 AutoScript

```bash
python python/codehub.py status    # estado rápido
python python/codehub.py health    # health check completo
python python/codehub.py backup    # backup MongoDB
python python/codehub.py deploy    # git push + verificar
python python/codehub.py all       # todo en orden
```

## 📬 Contacto

- **Email:** wilsonenrique686@gmail.com
- **WhatsApp:** +502 4146 8185
- **Web:** [wilson360-labs.vercel.app](https://wilson360-labs.vercel.app)

## 📄 Licencia

MIT © 2026 [Wilson Enriquez](https://wilson360-labs.vercel.app)
