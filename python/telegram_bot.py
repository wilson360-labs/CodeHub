"""
CodeHub Telegram Bot v3 — Wilson.E 2026
Ultra simple: sin dependencias, texto plano, debug completo
"""
import os, sys, json, logging
import urllib.request, urllib.error, urllib.parse

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger(__name__)

try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

TOKEN    = os.getenv("TELEGRAM_TOKEN", "").strip()
CHAT_ID  = os.getenv("TELEGRAM_CHAT_ID", "").strip()
BACKEND  = os.getenv("BACKEND_URL", "https://codehub-98s6.onrender.com").strip().rstrip("/")
ADMIN_KEY= os.getenv("ADMIN_KEY", "").strip()


def send(text):
    if not TOKEN or not CHAT_ID:
        log.error("TOKEN o CHAT_ID vacios")
        return False

    log.info(f"TOKEN empieza con: {TOKEN[:10]}...")
    log.info(f"CHAT_ID: {CHAT_ID}")
    log.info(f"Mensaje a enviar ({len(text)} chars): {text[:100]}")

    payload = {
        "chat_id": int(CHAT_ID),
        "text": text
    }
    data = json.dumps(payload).encode("utf-8")

    url = f"https://api.telegram.org/bot{TOKEN}/sendMessage"
    req = urllib.request.Request(
        url, data=data,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            resp = r.read().decode("utf-8")
            log.info(f"OK {r.status}: {resp[:200]}")
            return True
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
        log.error(f"HTTP {e.code}: {body}")
        return False
    except Exception as e:
        log.error(f"Error: {e}")
        return False


def http_get(url, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            body = r.read().decode("utf-8")
            try:    return r.status, json.loads(body)
            except: return r.status, {}
    except Exception as e:
        return 0, {}


def main():
    if not TOKEN:
        print("ERROR: TELEGRAM_TOKEN no configurado")
        sys.exit(1)
    if not CHAT_ID:
        print("ERROR: TELEGRAM_CHAT_ID no configurado")
        sys.exit(1)

    headers = {"x-admin-key": ADMIN_KEY}

    # Obtener datos
    code1, health = http_get(f"{BACKEND}/api/health")
    code2, apps_d = http_get(f"{BACKEND}/api/admin/apps", headers)

    apps  = apps_d.get("apps", [])
    total = len(apps)
    ver   = health.get("version", "?")
    st    = health.get("status", "?")
    mongo = health.get("mongo", "?")
    uptime= health.get("uptime", "?")

    from datetime import datetime, timezone, timedelta
    now   = datetime.now(timezone(timedelta(hours=-6)))
    fecha = now.strftime("%d/%m/%Y %H:%M GT")

    msg = (
        f"CodeHub - Resumen diario\n"
        f"{fecha}\n\n"
        f"Backend v{ver}: {st}\n"
        f"MongoDB: {mongo}\n"
        f"Uptime: {uptime}\n\n"
        f"Apps Android: {total}\n"
        f"Con APK: {sum(1 for a in apps if a.get('b2_file_name'))}\n\n"
        f"https://wilson360-labs.vercel.app"
    )

    print("Enviando mensaje...")
    ok = send(msg)
    if ok:
        print("Enviado correctamente")
        sys.exit(0)
    else:
        print("Error al enviar")
        sys.exit(1)


if __name__ == "__main__":
    main()
