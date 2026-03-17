"""
CodeHub Telegram Bot — Wilson.E 2026
Resumen diario via GitHub Actions
"""

import os, sys, json, asyncio, logging
from datetime import datetime, timezone, timedelta
from typing import Optional
import urllib.request, urllib.error

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("codehub-bot")

# Config
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

TOKEN     = os.getenv("TELEGRAM_TOKEN", "")
CHAT_ID   = os.getenv("TELEGRAM_CHAT_ID", "")
BACKEND   = os.getenv("BACKEND_URL", "https://codehub-production-729d.up.railway.app").rstrip("/")
ADMIN_KEY = os.getenv("ADMIN_KEY", "")
GT_TZ     = timezone(timedelta(hours=-6))


def http_get(url, headers=None, timeout=20):
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode("utf-8")
            try:    return r.status, json.loads(body)
            except: return r.status, {"raw": body}
    except urllib.error.HTTPError as e:
        return e.code, {}
    except Exception as e:
        return 0, {"error": str(e)}


def send_telegram(text):
    """Envía mensaje de texto plano a Telegram."""
    if not TOKEN or not CHAT_ID:
        log.error("TOKEN o CHAT_ID no configurados")
        return False

    data = json.dumps({
        "chat_id": CHAT_ID,
        "text": text,
        "disable_web_page_preview": True
    }).encode("utf-8")

    req = urllib.request.Request(
        f"https://api.telegram.org/bot{TOKEN}/sendMessage",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            log.info(f"Telegram OK: {r.status}")
            return True
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
        log.error(f"Telegram error {e.code}: {body}")
        return False
    except Exception as e:
        log.error(f"Error: {e}")
        return False


def get_stats():
    """Obtiene estadísticas del backend."""
    headers = {"x-admin-key": ADMIN_KEY}
    stats = {}

    endpoints = {
        "health":   ("/api/health",       {}),
        "apps":     ("/api/admin/apps",   headers),
        "ratings":  ("/api/ratings",      {}),
        "requests": ("/api/requests",     {}),
    }

    for key, (path, hdrs) in endpoints.items():
        code, data = http_get(f"{BACKEND}{path}", headers=hdrs)
        stats[key] = data if code == 200 else {}
        log.info(f"{path}: HTTP {code}")

    return stats


def build_message(stats):
    """Construye el mensaje de texto plano."""
    now = datetime.now(GT_TZ)
    fecha = now.strftime("%d/%m/%Y")
    hora  = now.strftime("%H:%M")

    health   = stats.get("health", {})
    apps_d   = stats.get("apps", {})
    ratings  = stats.get("ratings", {})
    requests = stats.get("requests", {})

    version  = health.get("version", "?")
    status   = health.get("status", "?")
    mongo    = health.get("mongo", "?")
    uptime   = health.get("uptime", "?")

    apps     = apps_d.get("apps", [])
    total    = len(apps)
    con_apk  = sum(1 for a in apps if a.get("b2_file_name"))

    all_r    = ratings.get("ratings", {})
    rated    = len(all_r)
    avg      = round(sum(v.get("avg",0) for v in all_r.values()) / len(all_r), 1) if all_r else 0

    reqs     = requests.get("requests", [])
    pending  = len(reqs)
    top_req  = reqs[0].get("appName", "-") if reqs else "-"

    lines = [
        "=== CodeHub - Resumen del dia ===",
        f"Fecha: {fecha} {hora} GT",
        "",
        f"BACKEND v{version}",
        f"  Estado: {status}",
        f"  Uptime: {uptime}",
        f"  MongoDB: {mongo}",
        "",
        f"APPS ANDROID",
        f"  Total: {total}",
        f"  Con APK: {con_apk}",
        f"  Sin APK: {total - con_apk}",
        "",
        f"RATINGS",
        f"  Apps calificadas: {rated}",
        f"  Promedio: {avg}/5",
        "",
        f"SOLICITUDES",
        f"  Pendientes: {pending}",
        f"  Mas votada: {top_req}",
        "",
        "https://wilson360-labs.vercel.app",
    ]
    return "\n".join(lines)


def main():
    if not TOKEN:
        print("ERROR: TELEGRAM_TOKEN no configurado")
        sys.exit(1)
    if not CHAT_ID:
        print("ERROR: TELEGRAM_CHAT_ID no configurado")
        sys.exit(1)

    mode = sys.argv[1] if len(sys.argv) > 1 else ""

    if mode == "--get-id":
        code, data = http_get(f"https://api.telegram.org/bot{TOKEN}/getUpdates")
        updates = data.get("result", [])
        if updates:
            chat_id = updates[-1]["message"]["chat"]["id"]
            print(f"Tu CHAT_ID es: {chat_id}")
        else:
            print("Sin mensajes. Envia /start al bot primero.")
        return

    print("Obteniendo estadisticas...")
    stats = get_stats()
    msg   = build_message(stats)

    print("--- MENSAJE ---")
    print(msg)
    print("---")

    ok = send_telegram(msg)
    print("Enviado OK" if ok else "ERROR al enviar")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
