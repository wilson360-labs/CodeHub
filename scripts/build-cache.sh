#!/usr/bin/env bash
# ============================================================
# CODEHUB — PARTICIONES DE BUILD EN SUPABASE STORAGE (PRIVADO)
# ------------------------------------------------------------
# Misma filosofía que el keystore: mejor esfuerzo. Ningún fallo
# de descarga, validación o subida puede abortar la compilación
# (por eso NO se usa `set -e`).
#
# Particiones:
#   gradle-buildcache -> ~/.gradle/caches/build-cache-1
#                        (salidas de tareas ya compiladas; key por
#                        hash de entradas, seguro entre commits)
#   android-sdk       -> $ANDROID_HOME/platforms/android-37.0
#                        $ANDROID_HOME/build-tools/36.0.0
#                        (evita bajar la API preview en cada run)
#   gradle-deps       -> ~/.gradle/caches/modules-2
#                        (opcional; GH Actions ya lo cachea)
#
# El manifest guarda un `key` calculado solo con archivos de
# configuración de build (nunca fuentes). Si el key cambia, se
# descarta la caché y se recompila desde cero: cero riesgo de
# reutilizar salidas incompatibles.
# En runs consecutivos con el mismo key se reutilizan las particiones:
# no se vuelven a subir si el sha256 no cambia.
#
# Uso:
#   bash scripts/build-cache.sh restore
#   bash scripts/build-cache.sh save
# ============================================================
set -uo pipefail

MODE="${1:-}"
BASE="${SUPABASE_URL:-}"
TOKEN="${SUPABASE_SERVICE_ROLE_KEY:-}"
PREFIX="${BUILD_CACHE_SUPABASE_PATH:-codehub/build}"
CAP_MB="${BUILD_CACHE_MAX_MB_PER_PART:-280}"
INCLUDE_DEPS="${BUILD_CACHE_INCLUDE_DEPS:-false}"
GRADLE_VERSION="9.3.1"
TMP="${RUNNER_TEMP:-/tmp}/codehub-build-cache"
MANIFEST_NAME="cache-manifest.json"

log() { echo "[build-cache] $*"; }

case "$CAP_MB" in
  ''|*[!0-9]*) CAP_MB=280 ;;
esac

if [ "$MODE" != "restore" ] && [ "$MODE" != "save" ]; then
  log "Uso: build-cache.sh restore|save"
  exit 0
fi

if [ -z "$BASE" ] || [ -z "$TOKEN" ]; then
  log "Faltan SUPABASE_URL o SUPABASE_SERVICE_ROLE_KEY; se omite (build normal)."
  exit 0
fi
if ! command -v jq >/dev/null 2>&1; then
  log "jq no disponible; se omite caché (build normal)."
  exit 0
fi

BASE="${BASE%/}"
obj_url() { printf '%s/storage/v1/object/private/%s/%s' "$BASE" "$PREFIX" "$1"; }

CONFIG_FILES=(
  apk/android/build.gradle
  apk/android/settings.gradle
  apk/android/gradle.properties
  apk/android/app/build.gradle
  apk/android/codesu/build.gradle.kts
  apk/android/echo/build.gradle.kts
  apk/android/codestream/build.gradle.kts
  apk/android/pairipfix/build.gradle
)
compute_key() {
  {
    for f in "${CONFIG_FILES[@]}"; do
      # El workflow inyecta versionName/versionCode en app/build.gradle con
      # sed -i ANTES de este paso. Leer desde HEAD evita que el key cambie
      # en cada build y deje la caché inutilizable.
      if git show "HEAD:$f" 2>/dev/null; then :; elif [ -f "$f" ]; then cat "$f"; fi
    done
    echo "gradle=$GRADLE_VERSION"
  } | sha256sum | cut -c1-16
}
KEY="$(compute_key)"

part_base() {
  case "$1" in
    gradle-deps|gradle-buildcache) printf '%s' "${HOME:-/home/runner}" ;;
    android-sdk)                   printf '%s' "${ANDROID_HOME:-}" ;;
  esac
}
part_rel() {
  case "$1" in
    gradle-deps)       printf '%s' ".gradle/caches/modules-2" ;;
    gradle-buildcache) printf '%s' ".gradle/caches/build-cache-1" ;;
    android-sdk)       printf '%s' "platforms/android-37.0 build-tools/36.0.0" ;;
  esac
}
all_parts() {
  printf '%s\n' gradle-buildcache android-sdk
  if [ "$INCLUDE_DEPS" = "true" ]; then printf '%s\n' gradle-deps; fi
}
fmt_ext() { command -v zstd >/dev/null 2>&1 && printf 'zst' || printf 'gz'; }

pack_part() {
  local name="$1" out="$2" base rel ext existing=()
  base=$(part_base "$name")
  rel=$(part_rel "$name")
  ext=$(fmt_ext)
  [ -n "$base" ] || return 2
  for r in $rel; do
    [ -e "$base/$r" ] && existing+=("$r")
  done
  [ "${#existing[@]}" -gt 0 ] || return 2
  # Tar determinista: mismo contenido => mismo sha => no se resube.
  local taropts=(--sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner)
  if [ "$ext" = "zst" ]; then
    ( cd "$base" && tar "${taropts[@]}" -cf - "${existing[@]}" | zstd -3 -q -o "$out" ) || return 3
  else
    ( cd "$base" && tar "${taropts[@]}" -czf "$out" "${existing[@]}" ) || return 3
  fi
  [ -s "$out" ] || return 3
}

unpack_part() {
  local file="$1" base="$2" ext="$3"
  case "$ext" in
    zst) tar --use-compress-program='zstd -d -q' -xf "$file" -C "$base" ;;
    gz)  tar -xzf "$file" -C "$base" ;;
    *)   return 1 ;;
  esac
}

mkdir -p "$TMP"

restore() {
  local mf="$TMP/$MANIFEST_NAME"
  if ! curl -fsSL --max-time 60 --retry 2 \
      -H "Authorization: Bearer $TOKEN" \
      -o "$mf" "$(obj_url "$MANIFEST_NAME")"; then
    log "Sin manifest en Supabase → compilación desde cero (normal)."
    return 0
  fi
  local remote
  remote=$(jq -r '.key // empty' "$mf" 2>/dev/null)
  if [ "$remote" != "$KEY" ]; then
    log "Key distinto ($remote vs $KEY): configuración cambió, se descarta caché."
    return 0
  fi

  local restored=0
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    local file ext base tmpfile
    file=$(jq -r --arg p "$p" '.parts[$p].file // empty' "$mf" 2>/dev/null)
    [ -n "$file" ] || continue
    case "$file" in
      *.tar.zst) ext=zst ;;
      *.tar.gz)  ext=gz ;;
      *) continue ;;
    esac
    base=$(part_base "$p")
    if [ -z "$base" ]; then
      log "$p: ruta base vacía, se omite."
      continue
    fi
    tmpfile="$TMP/$file"
    if ! curl -fsSL --max-time 300 --retry 2 \
        -H "Authorization: Bearer $TOKEN" \
        -o "$tmpfile" "$(obj_url "$file")"; then
      log "$p: descarga fallida; se continúa sin esta partición."
      rm -f "$tmpfile"
      continue
    fi
    [ -s "$tmpfile" ] || { rm -f "$tmpfile"; continue; }
    local expected actual
    expected=$(jq -r --arg p "$p" '.parts[$p].sha256 // empty' "$mf" 2>/dev/null)
    actual=$(sha256sum "$tmpfile" | cut -d' ' -f1)
    if [ -n "$expected" ] && [ "$actual" != "$expected" ]; then
      log "$p: sha no coincide; se descarta (no se extrae)."
      rm -f "$tmpfile"
      continue
    fi
    if ! unpack_part "$tmpfile" "$base" "$ext"; then
      log "$p: archivo corrupto; se descarta."
      rm -f "$tmpfile"
      continue
    fi
    log "$p: restaurada ($(du -h "$tmpfile" | cut -f1))."
    rm -f "$tmpfile"
    restored=$((restored + 1))
  done < <(all_parts)

  log "Listo: $restored particiones restauradas. key=$KEY"
}

save() {
  local ext; ext=$(fmt_ext)
  local cap_bytes=$((CAP_MB * 1024 * 1024))
  local parts_json='{}' uploaded=0
  local prev_mf="$TMP/$MANIFEST_NAME" prev_key=''

  # Manifest anterior: evita resubir particiones byte-idénticas.
  if curl -fsSL --max-time 60 --retry 2 \
      -H "Authorization: Bearer $TOKEN" \
      -o "$prev_mf" "$(obj_url "$MANIFEST_NAME")"; then
    prev_key=$(jq -r '.key // empty' "$prev_mf" 2>/dev/null)
  fi

  while IFS= read -r p; do
    [ -n "$p" ] || continue
    local file tmpfile bytes
    file="$p.tar.$ext"
    tmpfile="$TMP/$file"
    if ! pack_part "$p" "$tmpfile"; then
      log "$p: sin datos locales para cachear."
      continue
    fi
    bytes=$(stat -c%s "$tmpfile" 2>/dev/null || echo 0)
    local sha; sha=$(sha256sum "$tmpfile" | cut -d' ' -f1)
    if [ "$prev_key" = "$KEY" ]; then
      local prev_sha
      prev_sha=$(jq -r --arg p "$p" '.parts[$p].sha256 // empty' "$prev_mf" 2>/dev/null)
      if [ "$prev_sha" = "$sha" ]; then
        log "$p: sin cambios (sha idéntico), no se resube."
        rm -f "$tmpfile"
        continue
      fi
    fi
    if [ "$bytes" -gt "$cap_bytes" ]; then
      log "$p: $((bytes / 1024 / 1024)) MB > ${CAP_MB} MB (cap); se omite."
      rm -f "$tmpfile"
      continue
    fi
    if ! curl -fsS --max-time 600 --retry 2 -X POST \
        -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/octet-stream' \
        -H 'x-upsert: true' \
        --data-binary @"$tmpfile" "$(obj_url "$file")"; then
      log "$p: subida fallida; no afecta al build."
      rm -f "$tmpfile"
      continue
    fi
    parts_json=$(jq -c --arg p "$p" --arg f "$file" --argjson b "$bytes" --arg s "$sha" \
      '. + {($p): {file: $f, bytes: $b, sha256: $s}}' <<<"$parts_json") || parts_json='{}'
    log "$p: subida ($((bytes / 1024 / 1024)) MB)."
    rm -f "$tmpfile"
    uploaded=$((uploaded + 1))
  done < <(all_parts)

  if [ "$uploaded" -eq 0 ]; then
    log "Ninguna partición subida; se conserva el manifest anterior."
    return 0
  fi

  local mf="$TMP/$MANIFEST_NAME"
  jq -n --arg key "$KEY" --arg sha "${GITHUB_SHA:-}" \
        --arg now "$(date -u +%FT%TZ)" --arg fmt "tar.$ext" \
        --argjson parts "$parts_json" \
        '{key:$key, commit:$sha, created_at:$now, format:$fmt, parts:$parts}' > "$mf"
  if curl -fsS --max-time 60 --retry 2 -X POST \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -H 'x-upsert: true' \
      --data-binary @"$mf" "$(obj_url "$MANIFEST_NAME")"; then
    log "Manifest actualizado: key=$KEY, particiones=$uploaded."
  else
    log "Manifest no actualizado (las particiones sí subieron)."
  fi
}

if [ "$MODE" = "restore" ]; then
  restore
else
  save
fi
exit 0
