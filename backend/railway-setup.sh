#!/bin/bash
# ══════════════════════════════════════════════════════════════════
#  CodeHub Backend — Script de configuración para Railway
#  Autor: Wilson.E  |  github.com/tu-usuario/codehub-backend
#
#  USO:
#    1. Sube la carpeta backend/ a un repo de GitHub
#    2. Crea un proyecto en railway.app y conecta el repo
#    3. En Railway → Variables, agrega OPENAI_API_KEY y MONGODB_URI
#    4. Railway ejecutará esto automáticamente al hacer deploy
# ══════════════════════════════════════════════════════════════════

set -e  # Detener si hay cualquier error

echo "═══════════════════════════════════════════════"
echo "  🚀 CodeHub Backend — Iniciando en Railway"
echo "═══════════════════════════════════════════════"

# ── Verificar variables de entorno obligatorias ────────────────
check_env() {
  if [ -z "${!1}" ]; then
    echo "❌ ERROR: La variable $1 no está configurada en Railway"
    echo "   Ve a Railway → Tu proyecto → Variables → Add Variable"
    exit 1
  fi
  echo "✅ $1 → configurada"
}

echo ""
echo "── Verificando variables de entorno ─────────────"
check_env "OPENAI_API_KEY"
check_env "MONGODB_URI"
echo "✅ PORT      → ${PORT:-3001} (Railway lo asigna automáticamente)"
echo ""

# ── Instalar dependencias ──────────────────────────────────────
echo "── Instalando dependencias ──────────────────────"
npm install --production
echo "✅ Dependencias instaladas"
echo ""

# ── Verificar archivos críticos ────────────────────────────────
echo "── Verificando archivos del proyecto ────────────"

FILES=("server.js" "package.json")
for f in "${FILES[@]}"; do
  if [ -f "$f" ]; then
    echo "✅ $f"
  else
    echo "❌ Falta: $f"
    exit 1
  fi
done
echo ""

# ── Info del entorno ───────────────────────────────────────────
echo "── Entorno ──────────────────────────────────────"
echo "   Node.js: $(node --version)"
echo "   npm:     $(npm --version)"
echo "   Puerto:  ${PORT:-3001}"
echo "   Entorno: ${NODE_ENV:-production}"
echo ""

echo "═══════════════════════════════════════════════"
echo "  ✅ Setup completado — iniciando server.js"
echo "═══════════════════════════════════════════════"
echo ""

# ── Iniciar el servidor ────────────────────────────────────────
exec node server.js
