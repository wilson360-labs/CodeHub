// script.js — CodeHub
// Funciones auxiliares del sitio

// Floating menu (si existe en el DOM)
const floatingMenu = document.getElementById('floating-menu');
if (floatingMenu) {
  floatingMenu.addEventListener('click', () => {
    console.log('Menú flotante activado');
  });
  floatingMenu.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') floatingMenu.click();
  });
}
