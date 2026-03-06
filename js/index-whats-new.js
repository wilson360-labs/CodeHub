/* ═══════════════════════════════════════
   index — Whats New Dialog
   CodeHub by Wilson.E
═══════════════════════════════════════ */

/* ====== JS: control del icono y diálogo ====== */
(function(){
  const whatsIcon = document.getElementById('whatsNewIcon');
  const overlay = document.getElementById('whatsNewOverlay');
  const btnClose = document.getElementById('closeWhatsNew');
  const btnNovedades = document.getElementById('openNovedades');
  const btnTools = document.getElementById('openTools');

  if (!whatsIcon || !overlay || !btnClose || !btnNovedades || !btnTools) {
    console.warn('whatsNew: elementos no encontrados (verifica IDs).');
    return;
  }

  // abrir diálogo al tocar/enter en icono (no automático)
  const openDialog = () => {
    overlay.classList.add('active');
    overlay.setAttribute('aria-hidden','false');
    btnNovedades.focus();
  };

  const closeDialog = () => {
    overlay.classList.remove('active');
    overlay.setAttribute('aria-hidden','true');
    whatsIcon.focus();
  };

  whatsIcon.addEventListener('click', openDialog);
  whatsIcon.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') openDialog(); });

  btnClose.addEventListener('click', closeDialog);

  // cerrar al click fuera del dialog (overlay)
  overlay.addEventListener('click', (e) => { if (e.target === overlay) closeDialog(); });

  // acciones: abrir páginas (misma pestaña)
  btnNovedades.addEventListener('click', () => { window.location.href = 'novedades.html'; });
  btnTools.addEventListener('click', () => { window.location.href = 'tools.html'; });

  // Esc para cerrar
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && overlay.classList.contains('active')) closeDialog(); });
})();