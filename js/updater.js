// ============================================================
// CODEHUB — SISTEMA DE ACTUALIZACIÓN v4.0
// Simplificado: apps_data.json eliminado por políticas de contenido.
// Mantenido como stub por si se necesita en el futuro.
// ============================================================

const UPDATER_CONFIG = {
  checkInterval: 0,
  showLinkBadge: false,
  animateChanges: false,
};

const APP_ID_MAP = {};

async function loadAppsData() { return null; }
function detectChanges() { return []; }
function applyDataToCards() {}
function initUpdater() {}

if (typeof window !== 'undefined') {
  window.initUpdater = initUpdater;
}
