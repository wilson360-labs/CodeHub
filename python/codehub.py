#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════╗
║          CodeHub AutoScript — Wilson.E 2026                  ║
║  Setup · Deploy · Health · Backup · Update · Clean           ║
╚══════════════════════════════════════════════════════════════╝

Uso:
  python codehub.py setup       → configuración inicial completa
  python codehub.py deploy      → deploy frontend + backend
  python codehub.py health      → verificar que todo funciona
  python codehub.py backup      → backup de MongoDB
  python codehub.py update      → actualizar versiones de apps
  python codehub.py clean       → limpiar cachés y logs
  python codehub.py all         → ejecutar todo en orden
  python codehub.py status      → resumen rápido del estado
"""

import os
import sys
import json
import time
import shutil
import hashlib
import platform
import subprocess
import urllib.request
import urllib.error
from pathlib import Path
from datetime import datetime, timezone, timedelta
from typing import Optional

# ── COLORES ───────────────────────────────────────────────────
IS_WIN = platform.system() == "Windows"

def c(text: str, color: str) -> str:
    if IS_WIN:
        return text
    colors = {
        "red":    "\033[91m", "green":  "\033[92m",
        "yellow": "\033[93m", "blue":   "\033[94m",
        "purple": "\033[95m", "cyan":   "\033[96m",
        "white":  "\033[97m", "bold":   "\033[1m",
        "reset":  "\033[0m",
    }
    return f"{colors.get(color,'')}{text}{colors['reset']}"

def ok(msg):  print(f"  {c('✅', 'green')}  {msg}")
def err(msg): print(f"  {c('❌', 'red')}  {msg}")
def warn(msg):print(f"  {c('⚠️ ', 'yellow')} {msg}")
def info(msg):print(f"  {c('ℹ️ ', 'cyan')}  {msg}")
def step(msg):print(f"\n{c('━━', 'blue')} {c(msg, 'bold')}")
def sep():    print(f"\n{c('─'*56, 'blue')}")

LOGO = f"""
{c('╔══════════════════════════════════════════════════════╗', 'blue')}
{c('║', 'blue')} {c('<Wilson.E/>', 'cyan')} {c('CodeHub AutoScript v2.0', 'white')}           {c('║', 'blue')}
{c('╚══════════════════════════════════════════════════════╝', 'blue')}
"""

# ── CONFIG ────────────────────────────────────────────────────
ROOT      = Path(__file__).parent.parent
ENV_FILE  = ROOT / ".env"
BACKEND   = ROOT / "backend"
LOG_DIR   = ROOT / "logs"
BACKUP_DIR= ROOT / "backups"

BACKEND_URL = "https://codehub-production-729d.up.railway.app"
FRONTEND_URL= "https://wilson360-labs.vercel.app"

GT_TZ = timezone(timedelta(hours=-6))

# ── UTILIDADES ────────────────────────────────────────────────

def run(cmd: list, cwd=None, capture=False) -> tuple[int, str]:
    """Ejecuta un comando y retorna (código, output)."""
    try:
        result = subprocess.run(
            cmd, cwd=cwd or ROOT,
            capture_output=capture, text=True,
            timeout=120
        )
        return result.returncode, result.stdout + result.stderr
    except FileNotFoundError:
        return 1, f"Comando no encontrado: {cmd[0]}"
    except subprocess.TimeoutExpired:
        return 1, "Timeout después de 120s"
    except Exception as e:
        return 1, str(e)


def http_get(url: str, headers: dict = None, timeout: int = 20) -> tuple[int, dict]:
    """HTTP GET simple sin dependencias externas."""
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode("utf-8")
            try:
                return r.status, json.loads(body)
            except json.JSONDecodeError:
                return r.status, {"raw": body}
    except urllib.error.HTTPError as e:
        return e.code, {}
    except Exception as e:
        return 0, {"error": str(e)}


def load_env() -> dict:
    """Carga variables del .env."""
    env = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, _, v = line.partition("=")
                env[k.strip()] = v.strip().strip('"').strip("'")
    # También del entorno actual
    for key in ["MONGODB_URI","ADMIN_KEY","TELEGRAM_TOKEN","TELEGRAM_CHAT_ID","IA_ACCESS_KEY","IA_SECRET_KEY","IA_ITEM_ID"]:
        if key in os.environ and key not in env:
            env[key] = os.environ[key]
    return env


def save_env(env: dict):
    """Guarda variables al .env."""
    lines = ["# CodeHub — Variables de entorno", "# Generado por codehub.py\n"]
    for k, v in env.items():
        lines.append(f"{k}={v}")
    ENV_FILE.write_text("\n".join(lines) + "\n")


def ensure_dirs():
    LOG_DIR.mkdir(exist_ok=True)
    BACKUP_DIR.mkdir(exist_ok=True)


def log_to_file(action: str, data: dict):
    """Guarda log en JSON lines."""
    ensure_dirs()
    log_file = LOG_DIR / f"{datetime.now(GT_TZ).strftime('%Y-%m')}.jsonl"
    entry = {
        "ts": datetime.now(GT_TZ).isoformat(),
        "action": action,
        **data
    }
    with open(log_file, "a") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def check_tool(name: str) -> bool:
    """Verifica si una herramienta está instalada."""
    return shutil.which(name) is not None

# ══════════════════════════════════════════════════════════════
# 1. SETUP
# ══════════════════════════════════════════════════════════════

def cmd_setup():
    step("SETUP INICIAL")
    env = load_env()
    changed = False

    # 1. Verificar herramientas
    step("Verificando herramientas del sistema")
    tools = {
        "node":   "nodejs.org",
        "npm":    "viene con Node.js",
        "git":    "git-scm.com",
        "python3": "python.org",
    }
    optional = {
        "vercel": "npm install -g vercel",
        "railway":"npm install -g @railway/cli",
    }
    all_ok = True
    for tool, install in tools.items():
        if check_tool(tool) or check_tool(tool.replace("3","")):
            ok(f"{tool}")
        else:
            err(f"{tool} no encontrado — instala desde {install}")
            all_ok = False

    for tool, install_cmd in optional.items():
        if check_tool(tool):
            ok(f"{tool} (CLI)")
        else:
            warn(f"{tool} CLI no instalado — ejecuta: {install_cmd}")

    # 2. Instalar dependencias del backend
    step("Instalando dependencias del backend")
    if (BACKEND / "package.json").exists():
        code, out = run(["npm", "install", "--prefer-offline"], cwd=BACKEND, capture=True)
        if code == 0:
            ok("npm install completado")
        else:
            err(f"npm install falló: {out[:200]}")
    else:
        warn("backend/package.json no encontrado")

    # 3. Instalar dependencias Python
    step("Instalando dependencias Python")
    py_req = ROOT / "python" / "requirements.txt"
    if py_req.exists():
        code, out = run(
            [sys.executable, "-m", "pip", "install", "-r", str(py_req), "-q"],
            capture=True
        )
        ok("Dependencias Python instaladas") if code == 0 else err(out[:200])
    else:
        info("python/requirements.txt no encontrado — omitiendo")

    # 4. Configurar .env interactivo
    step("Configurando variables de entorno")
    required_vars = {
        "MONGODB_URI":        "URI de MongoDB Atlas (mongodb+srv://...)",
        "ADMIN_KEY":          "Clave admin del panel",
        "GROQ_API_KEY":       "API key de Groq (groq.com)",
        "B2_KEY_ID":          "Backblaze Key ID",
        "B2_APP_KEY":         "Backblaze App Key",
        "B2_BUCKET_ID":       "Backblaze Bucket ID",
        "B2_BUCKET_NAME":     "Backblaze Bucket Name",
        "FRONTEND_URL":       f"URL del frontend (default: {FRONTEND_URL})",
        "TELEGRAM_TOKEN":     "Token del bot Telegram (@BotFather)",
        "TELEGRAM_CHAT_ID":   "Tu chat ID de Telegram",
        "IA_ACCESS_KEY":      "Internet Archive S3 Access Key (archive.org/account/s3.php)",
        "IA_SECRET_KEY":      "Internet Archive S3 Secret Key",
        "IA_ITEM_ID":         "Internet Archive Item ID donde se subirán los APKs",
    }
    for var, desc in required_vars.items():
        current = env.get(var, "")
        if current:
            ok(f"{var} ya configurado")
        else:
            warn(f"{var} no configurado — {desc}")
            try:
                val = input(f"    Valor (Enter para omitir): ").strip()
                if val:
                    env[var] = val
                    changed = True
                    ok(f"{var} guardado")
            except (KeyboardInterrupt, EOFError):
                print()
                info("Setup interactivo cancelado")
                break

    # Defaults
    if "FRONTEND_URL" not in env:
        env["FRONTEND_URL"] = FRONTEND_URL
        changed = True
    if "RATE_LIMIT_MAX" not in env:
        env["RATE_LIMIT_MAX"] = "50"
        changed = True

    if changed:
        save_env(env)
        ok(f".env guardado en {ENV_FILE}")

    # 5. Verificar .gitignore
    step("Verificando seguridad del repo")
    gitignore = ROOT / ".gitignore"
    protected = [".env", "node_modules/", "backups/", "logs/", "*.apk", "__pycache__/"]
    if gitignore.exists():
        content = gitignore.read_text()
        for p in protected:
            if p in content:
                ok(f".gitignore protege: {p}")
            else:
                warn(f".gitignore NO protege: {p} — agregando...")
                with open(gitignore, "a") as f:
                    f.write(f"\n{p}")
    else:
        warn(".gitignore no encontrado — creando...")
        gitignore.write_text("\n".join(protected) + "\n")
        ok(".gitignore creado")

    sep()
    ok("Setup completado")
    log_to_file("setup", {"status": "ok"})

# ══════════════════════════════════════════════════════════════
# 2. DEPLOY
# ══════════════════════════════════════════════════════════════

def cmd_deploy():
    step("DEPLOY AUTOMÁTICO")

    # Git status
    step("Verificando Git")
    code, out = run(["git", "status", "--porcelain"], capture=True)
    if code != 0:
        err("No es un repositorio Git")
        return
    changes = [l for l in out.splitlines() if l.strip()]
    if changes:
        info(f"{len(changes)} archivo(s) con cambios:")
        for ch in changes[:5]:
            print(f"    {ch}")
        if len(changes) > 5:
            print(f"    ... y {len(changes)-5} más")
    else:
        ok("Working tree limpio")
        info("Nada que hacer — no hay cambios")
        return

    # Git add + commit + push
    step("Commiteando y pusheando a GitHub")
    ts = datetime.now(GT_TZ).strftime("%Y-%m-%d %H:%M")
    commit_msg = f"chore: auto-deploy {ts} GT"

    cmds = [
        (["git", "add", "-A"],               "git add"),
        (["git", "commit", "-m", commit_msg],"git commit"),
        (["git", "push", "origin", "main"],   "git push"),
    ]
    for cmd, label in cmds:
        code, out = run(cmd, capture=True)
        if code == 0:
            ok(label)
        elif "nothing to commit" in out:
            info(f"{label} — nada que commitear")
        else:
            err(f"{label} falló:\n    {out[:300]}")
            return

    # Vercel (si está instalado)
    step("Deploy Frontend → Vercel")
    if check_tool("vercel"):
        code, out = run(["vercel", "--prod", "--yes"], capture=True)
        if code == 0:
            ok("Vercel deploy completado")
            # Extraer URL del output
            for line in out.splitlines():
                if "vercel.app" in line or "wilson360" in line:
                    info(f"URL: {line.strip()}")
                    break
        else:
            warn(f"Vercel CLI falló (el push a GitHub ya triggerea el deploy automático)")
    else:
        info("Vercel CLI no instalado — el push a main triggerea auto-deploy en Vercel")

    # Railway (si está instalado)
    step("Deploy Backend → Railway")
    if check_tool("railway"):
        code, out = run(["railway", "up", "--detach"], cwd=BACKEND, capture=True)
        if code == 0:
            ok("Railway deploy iniciado")
        else:
            warn("Railway CLI falló — verifica en railway.app")
    else:
        info("Railway CLI no instalado — Railway auto-deploya desde GitHub")

    # Esperar y verificar
    step("Verificando deploy (esperando 30s...)")
    time.sleep(30)
    status_code, data = http_get(f"{BACKEND_URL}/api/health")
    if status_code == 200 and data.get("status") == "ok":
        ok(f"Backend OK — v{data.get('version','?')} — uptime: {data.get('uptime','?')}")
    else:
        warn(f"Backend respondió {status_code} — puede estar iniciando aún")

    sep()
    ok(f"Deploy completado — {commit_msg}")
    log_to_file("deploy", {"commit": commit_msg, "status": "ok"})

# ══════════════════════════════════════════════════════════════
# 3. HEALTH CHECK
# ══════════════════════════════════════════════════════════════

def cmd_health():
    step("HEALTH CHECK COMPLETO")
    results = {}

    # Backend API
    step("Backend Railway")
    checks = [
        ("/api/health",    "Health endpoint"),
        ("/api/apps",      "Apps públicas"),
        ("/api/ratings",   "Ratings"),
        ("/api/ws-info",   "WebSocket info"),
    ]
    env = load_env()
    admin_headers = {"x-admin-key": env.get("ADMIN_KEY", "")}

    for path, label in checks:
        url = f"{BACKEND_URL}{path}"
        code, data = http_get(url, headers=admin_headers if "admin" in path else {})
        if code == 200:
            extra = ""
            if path == "/api/health":
                archive_status = data.get('archive','missing')
                archive_icon   = '🏛️ ✅' if archive_status.startswith('ok') else '🏛️ ⚠️'
                extra = f"v{data.get('version','?')} · mongo:{data.get('mongo','?')} · uptime:{data.get('uptime','?')} · archive:{archive_icon}" 
                results["backend"] = "ok"
            elif path == "/api/apps":
                extra = f"{data.get('total', len(data.get('apps',[])))} apps"
            ok(f"{label} — {extra}")
        elif code == 0:
            err(f"{label} — sin conexión ({data.get('error','')})")
            results["backend"] = "error"
        else:
            err(f"{label} — HTTP {code}")

    # Frontend Vercel
    step("Frontend Vercel")
    pages = [
        ("/",              "index.html"),
        ("/tools.html",    "tools.html"),
        ("/opensource.html","opensource.html"),
        ("/servicios.html","servicios.html"),
        ("/sw.js",         "Service Worker"),
        ("/manifest.json", "PWA Manifest"),
        ("/sitemap.xml",   "Sitemap"),
        ("/robots.txt",    "Robots.txt"),
    ]
    frontend_ok = 0
    for path, label in pages:
        url = f"{FRONTEND_URL}{path}"
        code, _ = http_get(url)
        if code == 200:
            ok(label)
            frontend_ok += 1
        elif code == 404:
            err(f"{label} — 404 NOT FOUND")
        else:
            warn(f"{label} — HTTP {code}")

    results["frontend"] = f"{frontend_ok}/{len(pages)}"

    # SEO checks
    step("SEO + Seguridad")
    code, data = http_get(f"{FRONTEND_URL}/")
    if code == 200:
        raw = data.get("raw", "")
        seo_checks = [
            ("og:title",           "Open Graph title"),
            ("schema.org",         "Schema.org"),
            ("application/ld+json","Structured data"),
            ("theme-color",        "Theme color PWA"),
            ("rel=\"canonical\"",  "Canonical URL"),
        ]
        for needle, label in seo_checks:
            if needle in raw:
                ok(label)
            else:
                warn(f"{label} no encontrado")

    # Velocidad básica
    step("Performance")
    start = time.time()
    code, _ = http_get(f"{FRONTEND_URL}/")
    elapsed = (time.time() - start) * 1000
    if elapsed < 500:
        ok(f"TTFB index.html: {elapsed:.0f}ms ⚡")
    elif elapsed < 1500:
        warn(f"TTFB index.html: {elapsed:.0f}ms (aceptable)")
    else:
        err(f"TTFB index.html: {elapsed:.0f}ms (lento)")

    start = time.time()
    code, _ = http_get(f"{BACKEND_URL}/api/health")
    api_ms = (time.time() - start) * 1000
    if api_ms < 300:
        ok(f"API latencia: {api_ms:.0f}ms ⚡")
    elif api_ms < 1000:
        warn(f"API latencia: {api_ms:.0f}ms")
    else:
        err(f"API latencia: {api_ms:.0f}ms (lento)")

    sep()
    ok(f"Health check completado — frontend:{results.get('frontend','?')} backend:{results.get('backend','?')}")
    log_to_file("health", results)
    return results

# ══════════════════════════════════════════════════════════════
# 4. BACKUP MONGODB
# ══════════════════════════════════════════════════════════════

def cmd_backup():
    step("BACKUP MONGODB")
    ensure_dirs()
    env = load_env()

    # Obtener datos del backend
    step("Descargando datos desde el backend")
    admin_headers = {"x-admin-key": env.get("ADMIN_KEY", "")}

    ts      = datetime.now(GT_TZ).strftime("%Y%m%d_%H%M%S")
    backup  = {"timestamp": datetime.now(GT_TZ).isoformat(), "collections": {}}
    total   = 0

    endpoints = {
        "apps":     "/api/admin/apps",
        "ratings":  "/api/ratings",
        "requests": "/api/requests",
    }

    for name, path in endpoints.items():
        url  = f"{BACKEND_URL}{path}"
        code, data = http_get(url, headers=admin_headers)
        if code == 200:
            backup["collections"][name] = data
            count = len(data.get(name, data.get("apps", data.get("requests", []))))
            ok(f"{name}: {count} documentos")
            total += count
        else:
            warn(f"{name}: error HTTP {code}")
            backup["collections"][name] = {"error": f"HTTP {code}"}

    # Guardar backup
    step("Guardando backup")
    backup_file = BACKUP_DIR / f"backup_{ts}.json"
    backup_file.write_text(
        json.dumps(backup, indent=2, ensure_ascii=False, default=str)
    )
    size_kb = backup_file.stat().st_size / 1024
    ok(f"Backup guardado: {backup_file.name} ({size_kb:.1f} KB, {total} documentos)")

    # Limpiar backups viejos (mantener últimos 30)
    step("Limpiando backups viejos")
    all_backups = sorted(BACKUP_DIR.glob("backup_*.json"))
    if len(all_backups) > 30:
        to_delete = all_backups[:-30]
        for f in to_delete:
            f.unlink()
            info(f"Eliminado: {f.name}")
        ok(f"{len(to_delete)} backups viejos eliminados")
    else:
        ok(f"Backups actuales: {len(all_backups)}/30")

    sep()
    ok(f"Backup completado — {total} documentos respaldados")
    log_to_file("backup", {"file": backup_file.name, "docs": total, "size_kb": round(size_kb,1)})

# ══════════════════════════════════════════════════════════════
# 5. ACTUALIZAR VERSIONES DE APPS
# ══════════════════════════════════════════════════════════════

def cmd_update():
    step("ACTUALIZAR APPS")
    env  = load_env()
    admin_headers = {"x-admin-key": env.get("ADMIN_KEY", "")}

    # Obtener apps actuales
    step("Obteniendo apps del backend")
    code, data = http_get(f"{BACKEND_URL}/api/admin/apps", headers=admin_headers)
    if code != 200:
        err(f"No se pudo obtener apps: HTTP {code}")
        return

    apps = data.get("apps", [])
    info(f"{len(apps)} apps en la base de datos")

    # Verificar apps sin APK
    step("Verificando estado de APKs")
    sin_apk = [a for a in apps if not a.get("b2_file_name")]
    con_apk = [a for a in apps if a.get("b2_file_name")]

    ok(f"Con APK en Backblaze: {len(con_apk)}")
    if sin_apk:
        warn(f"Sin APK (solo enlace externo): {len(sin_apk)}")
        for a in sin_apk[:5]:
            info(f"  · {a['nombre']} v{a.get('version','?')} — {a.get('tag','')}")

    # Verificar apps con tags viejos
    step("Verificando tags")
    from datetime import datetime as dt
    now = datetime.now(GT_TZ)
    hoy = now.date()
    apps_viejas = []
    for a in apps:
        updated = a.get("updatedAt", "")
        if updated:
            try:
                d = dt.fromisoformat(updated.replace("Z","+00:00")).date()
                dias = (hoy - d).days
                if dias > 30 and a.get("tag") == "🆕":
                    apps_viejas.append((a["nombre"], dias))
            except Exception:
                pass

    if apps_viejas:
        warn(f"{len(apps_viejas)} apps con tag '🆕' pero sin actualizar en 30+ días:")
        for nombre, dias in apps_viejas[:5]:
            info(f"  · {nombre} — {dias} días sin actualizar")
        print()
        try:
            resp = input("  ¿Actualizar sus tags a '📦 Estable'? (s/N): ").strip().lower()
            if resp == "s":
                for a in apps:
                    if a.get("tag") == "🆕":
                        updated = a.get("updatedAt", "")
                        if updated:
                            try:
                                d = dt.fromisoformat(updated.replace("Z","+00:00")).date()
                                if (hoy - d).days > 30:
                                    # PATCH al backend
                                    req_data = json.dumps({"tag": "📦 Estable"}).encode()
                                    req = urllib.request.Request(
                                        f"{BACKEND_URL}/api/admin/apps/{a['appId']}",
                                        data=req_data,
                                        headers={**admin_headers, "Content-Type":"application/json"},
                                        method="PATCH"
                                    )
                                    try:
                                        with urllib.request.urlopen(req, timeout=10) as r:
                                            if r.status == 200:
                                                ok(f"Tag actualizado: {a['nombre']}")
                                    except Exception as e:
                                        warn(f"Error actualizando {a['nombre']}: {e}")
                            except Exception:
                                pass
        except (KeyboardInterrupt, EOFError):
            info("Omitido")
    else:
        ok("Todos los tags están actualizados")

    # Resumen de categorías
    step("Distribución por categorías")
    from collections import Counter
    cats = Counter(a.get("categoria", "sin categoría") for a in apps)
    for cat, count in cats.most_common():
        info(f"  {cat or 'sin categoría'}: {count} apps")

    sep()
    ok("Verificación de apps completada")
    log_to_file("update", {"total_apps": len(apps), "sin_apk": len(sin_apk)})

# ══════════════════════════════════════════════════════════════
# 6. LIMPIAR CACHÉS Y LOGS
# ══════════════════════════════════════════════════════════════

def cmd_clean():
    step("LIMPIEZA DEL PROYECTO")
    total_freed = 0

    # node_modules (si existe en raíz)
    step("Verificando node_modules")
    nm = ROOT / "node_modules"
    if nm.exists():
        size = sum(f.stat().st_size for f in nm.rglob("*") if f.is_file())
        size_mb = size / (1024*1024)
        warn(f"node_modules en raíz: {size_mb:.1f} MB")
        try:
            resp = input("  ¿Eliminar? (s/N): ").strip().lower()
            if resp == "s":
                shutil.rmtree(nm)
                total_freed += size
                ok("node_modules eliminado")
        except (KeyboardInterrupt, EOFError):
            info("Omitido")
    else:
        ok("Sin node_modules en raíz")

    # __pycache__
    step("Limpiando __pycache__")
    freed = 0
    for d in ROOT.rglob("__pycache__"):
        if d.is_dir():
            size = sum(f.stat().st_size for f in d.rglob("*") if f.is_file())
            shutil.rmtree(d)
            freed += size
    if freed > 0:
        ok(f"__pycache__ eliminados: {freed/1024:.1f} KB liberados")
        total_freed += freed
    else:
        ok("Sin __pycache__ que limpiar")

    # Logs viejos (más de 90 días)
    step("Limpiando logs viejos")
    if LOG_DIR.exists():
        now = time.time()
        old_logs = [
            f for f in LOG_DIR.glob("*.jsonl")
            if (now - f.stat().st_mtime) > 90*24*3600
        ]
        for f in old_logs:
            size = f.stat().st_size
            f.unlink()
            total_freed += size
            info(f"Log eliminado: {f.name}")
        if old_logs:
            ok(f"{len(old_logs)} logs viejos eliminados")
        else:
            ok("Sin logs viejos que eliminar")

        # Comprimir logs actuales en resumen
        all_logs = list(LOG_DIR.glob("*.jsonl"))
        if all_logs:
            info(f"Logs actuales: {len(all_logs)} archivo(s)")

    # Backups > 60 días
    step("Limpiando backups viejos")
    if BACKUP_DIR.exists():
        now = time.time()
        old_bk = [
            f for f in BACKUP_DIR.glob("backup_*.json")
            if (now - f.stat().st_mtime) > 60*24*3600
        ]
        for f in old_bk:
            size = f.stat().st_size
            f.unlink()
            total_freed += size
        if old_bk:
            ok(f"{len(old_bk)} backups viejos eliminados")
        else:
            ok("Sin backups viejos")

    # Archivos temporales
    step("Archivos temporales")
    patterns = ["*.pyc", "*.pyo", ".DS_Store", "Thumbs.db", "*.log", "*.tmp"]
    found = 0
    for pattern in patterns:
        for f in ROOT.rglob(pattern):
            if f.is_file() and "node_modules" not in str(f):
                size = f.stat().st_size
                f.unlink()
                total_freed += size
                found += 1
    if found:
        ok(f"{found} archivos temporales eliminados")
    else:
        ok("Sin archivos temporales")

    sep()
    freed_mb = total_freed / (1024*1024)
    ok(f"Limpieza completada — {freed_mb:.1f} MB liberados")
    log_to_file("clean", {"freed_mb": round(freed_mb, 2)})

# ══════════════════════════════════════════════════════════════
# STATUS — resumen rápido
# ══════════════════════════════════════════════════════════════

def cmd_status():
    print(LOGO)
    step("ESTADO RÁPIDO — CodeHub")

    now = datetime.now(GT_TZ).strftime("%d/%m/%Y %H:%M GT")
    info(f"Fecha: {now}")

    # Backend
    code, data = http_get(f"{BACKEND_URL}/api/health")
    if code == 200:
        v   = data.get("version","?")
        up  = data.get("uptime","?")
        mg  = "🟢" if "connected" in str(data.get("mongo","")) else "🔴"
        rd  = "🟢" if "connected" in str(data.get("redis","")) else "🟡"
        ws  = data.get("ws","?")
        ia  = "🏛️ ✅" if str(data.get("archive","")).startswith("ok") else "🏛️ ⚠️"
        ok(f"Backend v{v} — uptime:{up} mongo:{mg} redis:{rd} ws:{ws} archive:{ia}")
    else:
        err(f"Backend no responde (HTTP {code})")

    # Frontend
    code, _ = http_get(FRONTEND_URL)
    ok("Frontend Vercel accesible") if code == 200 else err(f"Frontend HTTP {code}")

    # Último backup
    if BACKUP_DIR.exists():
        backups = sorted(BACKUP_DIR.glob("backup_*.json"))
        if backups:
            last = backups[-1]
            age  = (time.time() - last.stat().st_mtime) / 3600
            ok(f"Último backup: {last.name} ({age:.1f}h ago)")
        else:
            warn("Sin backups — ejecuta: python codehub.py backup")

    # Último log
    if LOG_DIR.exists():
        logs = sorted(LOG_DIR.glob("*.jsonl"))
        if logs:
            last_log = logs[-1]
            lines    = last_log.read_text().strip().splitlines()
            if lines:
                last_entry = json.loads(lines[-1])
                ok(f"Último log: {last_entry.get('action','?')} — {last_entry.get('ts','')[:16]}")

    sep()

# ══════════════════════════════════════════════════════════════
# ALL — ejecutar todo
# ══════════════════════════════════════════════════════════════

def cmd_all():
    print(LOGO)
    print(f"  {c('Ejecutando todos los módulos en orden...', 'cyan')}\n")
    start = time.time()

    modules = [
        ("health",  cmd_health,  "Health check"),
        ("backup",  cmd_backup,  "Backup MongoDB"),
        ("update",  cmd_update,  "Actualizar apps"),
        ("clean",   cmd_clean,   "Limpiar caché"),
        ("deploy",  cmd_deploy,  "Deploy"),
    ]

    results = {}
    for key, fn, label in modules:
        print(f"\n{c('▶', 'purple')} {c(label.upper(), 'bold')}")
        try:
            fn()
            results[key] = "✅"
        except KeyboardInterrupt:
            info(f"{label} cancelado")
            results[key] = "⏭️"
        except Exception as e:
            err(f"{label} falló: {e}")
            results[key] = "❌"

    elapsed = time.time() - start
    sep()
    print(f"\n  {c('RESUMEN FINAL', 'bold')}")
    for key, status in results.items():
        print(f"    {status} {key}")
    print(f"\n  ⏱️  Tiempo total: {elapsed:.1f}s")
    log_to_file("all", results)

# ══════════════════════════════════════════════════════════════
# MAIN
# ══════════════════════════════════════════════════════════════

COMMANDS = {
    "setup":  cmd_setup,
    "deploy": cmd_deploy,
    "health": cmd_health,
    "backup": cmd_backup,
    "update": cmd_update,
    "clean":  cmd_clean,
    "all":    cmd_all,
    "status": cmd_status,
}

def main():
    print(LOGO)

    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(f"  {c('Uso:', 'bold')} python codehub.py <comando>\n")
        print(f"  {c('Comandos:', 'bold')}")
        descs = {
            "setup":  "Configuración inicial completa",
            "deploy": "Deploy automático a Vercel + Railway",
            "health": "Verificar que todo funciona",
            "backup": "Backup de MongoDB",
            "update": "Actualizar versiones de apps",
            "clean":  "Limpiar cachés y logs",
            "all":    "Ejecutar todo en orden",
            "status": "Resumen rápido del estado",
        }
        for cmd, desc in descs.items():
            print(f"    {c(cmd.ljust(10), 'cyan')} {desc}")
        print()
        sys.exit(0)

    cmd = sys.argv[1]
    try:
        COMMANDS[cmd]()
    except KeyboardInterrupt:
        print(f"\n\n  {c('Cancelado por el usuario', 'yellow')}")
        sys.exit(0)
    except Exception as e:
        err(f"Error inesperado: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    main()
