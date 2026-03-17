"""
CodeHub Telegram Bot — Wilson.E 2026
=====================================
Envía un resumen diario automático con estadísticas de CodeHub.

Uso:
  python telegram_bot.py          → envía resumen ahora (test)
  python telegram_bot.py --schedule → ejecuta en loop (producción)

Variables de entorno (.env):
  TELEGRAM_TOKEN   → token del bot de @BotFather
  TELEGRAM_CHAT_ID → tu chat ID (usa /start en el bot para obtenerlo)
  BACKEND_URL      → https://codehub-production-729d.up.railway.app
  ADMIN_KEY        → tu clave admin del backend
  MONGODB_URI      → opcional, para stats extra directas de MongoDB
"""

import os
import sys
import json
import time
import asyncio
import logging
from datetime import datetime, timezone, timedelta
from typing import Optional

import httpx
from dotenv import load_dotenv

load_dotenv()

# ── CONFIG ────────────────────────────────────────────────────
TOKEN     = os.getenv("TELEGRAM_TOKEN", "")
CHAT_ID   = os.getenv("TELEGRAM_CHAT_ID", "")
BACKEND   = os.getenv("BACKEND_URL", "https://codehub-production-729d.up.railway.app").rstrip("/")
ADMIN_KEY = os.getenv("ADMIN_KEY", "")

# Guatemala es UTC-6
GT_TZ = timezone(timedelta(hours=-6))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
log = logging.getLogger("codehub-bot")

# ── TELEGRAM API ──────────────────────────────────────────────

async def send_message(text: str, parse_mode: str = "HTML") -> bool:
    """Envía un mensaje a Telegram."""
    if not TOKEN or not CHAT_ID:
        log.error("❌ TELEGRAM_TOKEN o TELEGRAM_CHAT_ID no configurados")
        return False

    url = f"https://api.telegram.org/bot{TOKEN}/sendMessage"
    payload = {
        "chat_id": CHAT_ID,
        "text": text,
        "parse_mode": parse_mode,
        "disable_web_page_preview": True,
    }

    async with httpx.AsyncClient(timeout=30) as client:
        try:
            r = await client.post(url, json=payload)
            r.raise_for_status()
            log.info("✅ Mensaje enviado a Telegram")
            return True
        except Exception as e:
            log.error(f"❌ Error enviando a Telegram: {e}")
            return False


async def get_my_chat_id() -> Optional[str]:
    """Obtiene tu chat_id enviando /start al bot primero."""
    url = f"https://api.telegram.org/bot{TOKEN}/getUpdates"
    async with httpx.AsyncClient(timeout=15) as client:
        try:
            r = await client.get(url)
            data = r.json()
            updates = data.get("result", [])
            if updates:
                chat_id = str(updates[-1]["message"]["chat"]["id"])
                log.info(f"Tu CHAT_ID es: {chat_id}")
                return chat_id
            else:
                log.warning("Sin mensajes aún. Envía /start al bot primero.")
                return None
        except Exception as e:
            log.error(f"Error: {e}")
            return None

# ── OBTENER DATOS DEL BACKEND ─────────────────────────────────

async def fetch_backend(endpoint: str, method: str = "GET") -> Optional[dict]:
    """Hace una petición al backend de CodeHub."""
    headers = {"x-admin-key": ADMIN_KEY}
    url = f"{BACKEND}{endpoint}"

    async with httpx.AsyncClient(timeout=30) as client:
        try:
            if method == "GET":
                r = await client.get(url, headers=headers)
            else:
                r = await client.post(url, headers=headers)
            r.raise_for_status()
            return r.json()
        except httpx.HTTPStatusError as e:
            log.warning(f"HTTP {e.response.status_code} en {endpoint}")
            return None
        except Exception as e:
            log.warning(f"Error en {endpoint}: {e}")
            return None


async def get_stats() -> dict:
    """Recopila todas las estadísticas del backend."""
    # Paralelo — todo a la vez
    results = await asyncio.gather(
        fetch_backend("/api/health"),
        fetch_backend("/api/admin/apps"),
        fetch_backend("/api/ratings"),
        fetch_backend("/api/requests"),
        fetch_backend("/api/stats/live"),
        return_exceptions=True
    )

    health   = results[0] if isinstance(results[0], dict) else {}
    apps     = results[1] if isinstance(results[1], dict) else {}
    ratings  = results[2] if isinstance(results[2], dict) else {}
    requests = results[3] if isinstance(results[3], dict) else {}
    live     = results[4] if isinstance(results[4], dict) else {}

    return {
        "health":   health,
        "apps":     apps,
        "ratings":  ratings,
        "requests": requests,
        "live":     live,
    }

# ── FORMATEAR EL RESUMEN ──────────────────────────────────────

def format_daily_summary(stats: dict) -> str:
    """Genera el mensaje HTML del resumen diario."""
    now_gt = datetime.now(GT_TZ)
    fecha  = now_gt.strftime("%A %d de %B, %Y")
    hora   = now_gt.strftime("%H:%M")

    health   = stats.get("health", {})
    apps_data = stats.get("apps", {})
    ratings  = stats.get("ratings", {})
    requests = stats.get("requests", {})
    live     = stats.get("live", {})

    # Estado del backend
    status     = health.get("status", "?")
    version    = health.get("version", "?")
    uptime_s   = health.get("uptime", "?")
    mongo_st   = health.get("mongo", "?")
    redis_st   = health.get("redis", "?")
    ws_clients = health.get("ws", "?")

    # Uptime legible
    try:
        secs = int(str(uptime_s).replace("s", ""))
        h, r = divmod(secs, 3600)
        m, _ = divmod(r, 60)
        uptime_str = f"{h}h {m}m"
    except Exception:
        uptime_str = str(uptime_s)

    # Apps
    apps_list  = apps_data.get("apps", [])
    total_apps = len(apps_list)
    # Apps con APK en B2
    apps_con_apk = sum(1 for a in apps_list if a.get("b2_file_name"))

    # Ratings
    all_ratings = ratings.get("ratings", {})
    rated_apps  = len(all_ratings)
    if all_ratings:
        avg_global = sum(v.get("avg", 0) for v in all_ratings.values()) / len(all_ratings)
        top_app = max(all_ratings.items(), key=lambda x: x[1].get("avg", 0))
        top_name, top_data = top_app
        top_str = f"⭐ <b>{top_name}</b> — {top_data.get('avg', 0)}/5 ({top_data.get('count', 0)} votos)"
    else:
        avg_global = 0
        top_str = "Sin ratings aún"

    # Requests pendientes
    pending_reqs = requests.get("requests", [])
    pending_count = len(pending_reqs)
    top_req = pending_reqs[0].get("appName", "—") if pending_reqs else "—"
    top_req_votes = pending_reqs[0].get("votes", 0) if pending_reqs else 0

    # Visitas en vivo
    visitors_today = live.get("visitors", 0)
    ws_live        = live.get("wsClients", 0)

    # Iconos de estado
    def st_icon(val: str) -> str:
        if "connected" in str(val) or "ok" in str(val):
            return "🟢"
        if "memory" in str(val):
            return "🟡"
        return "🔴"

    backend_icon = "🟢" if status == "ok" else "🔴"

    lines = [
        f"<b>📊 CodeHub — Resumen del día</b>",
        f"<i>{fecha} · {hora} GT</i>",
        "",
        f"<b>🖥️ Backend v{version}</b>",
        f"  {backend_icon} Estado: <code>{status}</code>",
        f"  ⏱️ Uptime: <code>{uptime_str}</code>",
        f"  {st_icon(mongo_st)} MongoDB: <code>{mongo_st}</code>",
        f"  {st_icon(redis_st)} Redis: <code>{redis_st}</code>",
        f"  🔌 WS clientes: <code>{ws_clients}</code>",
        "",
        f"<b>📱 Apps Android</b>",
        f"  📦 Total apps: <b>{total_apps}</b>",
        f"  ✅ Con APK en B2: <b>{apps_con_apk}</b>",
        f"  ⚠️  Sin APK: <b>{total_apps - apps_con_apk}</b>",
        "",
        f"<b>⭐ Ratings</b>",
        f"  Apps calificadas: <b>{rated_apps}</b>",
        f"  Promedio global: <b>{avg_global:.1f}/5</b>",
        f"  {top_str}",
        "",
        f"<b>📬 Solicitudes pendientes</b>",
        f"  Total: <b>{pending_count}</b>",
    ]

    if pending_count > 0:
        lines.append(f"  🔝 Más votada: <b>{top_req}</b> ({top_req_votes} votos)")
        if pending_count > 1:
            others = [r.get("appName","?") for r in pending_reqs[1:4]]
            lines.append(f"  📋 Otras: {', '.join(others)}")

    lines += [
        "",
        f"<b>👁️ Tráfico hoy</b>",
        f"  Visitas: <b>{visitors_today}</b>",
        f"  En línea ahora: <b>{ws_live}</b>",
        "",
        f"<a href='https://wilson360-labs.vercel.app'>🌐 Ver CodeHub</a> · "
        f"<a href='https://codehub-production-729d.up.railway.app/api/health'>🔧 API Health</a>",
    ]

    return "\n".join(lines)


def format_error_alert(error: str) -> str:
    """Mensaje de alerta de error crítico."""
    now = datetime.now(GT_TZ).strftime("%H:%M GT")
    return (
        f"🚨 <b>CodeHub — Alerta crítica</b>\n"
        f"<i>{now}</i>\n\n"
        f"<code>{error}</code>\n\n"
        f"<a href='https://railway.app'>Ver Railway →</a>"
    )

# ── COMANDOS DEL BOT ──────────────────────────────────────────

async def handle_updates():
    """Procesa comandos entrantes del bot."""
    url = f"https://api.telegram.org/bot{TOKEN}/getUpdates"
    last_update_id = None

    async with httpx.AsyncClient(timeout=30) as client:
        while True:
            try:
                params = {"timeout": 30, "offset": last_update_id}
                r = await client.get(url, params=params)
                data = r.json()

                for update in data.get("result", []):
                    last_update_id = update["update_id"] + 1
                    msg = update.get("message", {})
                    text = msg.get("text", "").strip()
                    chat = str(msg.get("chat", {}).get("id", ""))

                    if not text or chat != CHAT_ID:
                        continue

                    log.info(f"Comando recibido: {text}")

                    if text in ["/start", "/help"]:
                        await send_message(
                            "👋 <b>CodeHub Bot activo</b>\n\n"
                            "/stats — resumen ahora mismo\n"
                            "/health — estado del backend\n"
                            "/apps — lista de apps\n"
                            "/help — este menú"
                        )

                    elif text == "/stats":
                        await send_message("⏳ Obteniendo estadísticas...")
                        stats = await get_stats()
                        msg_text = format_daily_summary(stats)
                        await send_message(msg_text)

                    elif text == "/health":
                        health = await fetch_backend("/api/health")
                        if health:
                            lines = [f"<b>🔧 Backend Health</b>\n"]
                            for k, v in health.items():
                                lines.append(f"  <code>{k}</code>: {v}")
                            await send_message("\n".join(lines))
                        else:
                            await send_message("❌ Backend no responde")

                    elif text == "/apps":
                        apps_data = await fetch_backend("/api/admin/apps")
                        apps = apps_data.get("apps", []) if apps_data else []
                        if apps:
                            lines = [f"<b>📱 Apps ({len(apps)})</b>\n"]
                            for a in apps[:15]:
                                icon = "✅" if a.get("b2_file_name") else "⚠️"
                                lines.append(f"  {icon} <b>{a['nombre']}</b> v{a.get('version','?')} {a.get('tag','')}")
                            if len(apps) > 15:
                                lines.append(f"\n  ...y {len(apps)-15} más")
                            await send_message("\n".join(lines))
                        else:
                            await send_message("Sin apps en la DB")

            except asyncio.CancelledError:
                break
            except Exception as e:
                log.error(f"Error en handle_updates: {e}")
                await asyncio.sleep(5)


# ── SCHEDULER ─────────────────────────────────────────────────

async def daily_scheduler():
    """
    Ejecuta el resumen todos los días a las 9:00 PM hora Guatemala (UTC-6).
    Eso es 03:00 AM UTC del día siguiente.
    """
    target_hour_gt = 21  # 9 PM Guatemala

    log.info(f"🤖 Bot iniciado — resumen diario a las {target_hour_gt}:00 GT")
    await send_message(
        f"🤖 <b>CodeHub Bot iniciado</b>\n"
        f"Resumen diario a las {target_hour_gt}:00 GT\n"
        f"Comandos: /stats /health /apps /help"
    )

    while True:
        now_gt    = datetime.now(GT_TZ)
        target_gt = now_gt.replace(hour=target_hour_gt, minute=0, second=0, microsecond=0)

        # Si ya pasó la hora hoy, programar para mañana
        if now_gt >= target_gt:
            target_gt += timedelta(days=1)

        wait_secs = (target_gt - now_gt).total_seconds()
        log.info(f"⏰ Próximo resumen en {wait_secs/3600:.1f}h ({target_gt.strftime('%d/%m %H:%M GT')})")

        await asyncio.sleep(wait_secs)

        try:
            log.info("📊 Enviando resumen diario...")
            stats    = await get_stats()
            msg_text = format_daily_summary(stats)
            await send_message(msg_text)
            log.info("✅ Resumen enviado")
        except Exception as e:
            log.error(f"❌ Error en resumen diario: {e}")
            await send_message(format_error_alert(str(e)))

        # Esperar 1 minuto para no re-enviar en el mismo minuto
        await asyncio.sleep(60)


async def main():
    """Punto de entrada principal."""
    if not TOKEN:
        print("❌ TELEGRAM_TOKEN no configurado en .env")
        sys.exit(1)
    if not CHAT_ID:
        print("⚠️  TELEGRAM_CHAT_ID no configurado.")
        print("   Envía /start al bot y ejecuta: python telegram_bot.py --get-id")
        sys.exit(1)

    mode = sys.argv[1] if len(sys.argv) > 1 else ""

    if mode == "--get-id":
        # Obtener chat ID
        chat_id = await get_my_chat_id()
        if chat_id:
            print(f"\n✅ Agrega esto a tu .env:\nTELEGRAM_CHAT_ID={chat_id}")

    elif mode == "--test":
        # Enviar resumen una vez (test)
        print("📊 Obteniendo estadísticas...")
        stats    = await get_stats()
        msg_text = format_daily_summary(stats)
        print("\n--- PREVIEW DEL MENSAJE ---")
        print(msg_text)
        print("---\n")
        success = await send_message(msg_text)
        print("✅ Enviado a Telegram" if success else "❌ Error al enviar")

    elif mode == "--schedule":
        # Modo producción: scheduler + listener de comandos
        await asyncio.gather(
            daily_scheduler(),
            handle_updates(),
        )

    else:
        # Default: enviar resumen ahora
        print("📊 Enviando resumen ahora...")
        stats    = await get_stats()
        msg_text = format_daily_summary(stats)
        success  = await send_message(msg_text)
        print("✅ Enviado" if success else "❌ Error")


if __name__ == "__main__":
    asyncio.run(main())
