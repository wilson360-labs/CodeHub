/* ═══════════════════════════════════════
   Servicios — Scripts
   CodeHub by Wilson.E
═══════════════════════════════════════ */

window.addEventListener('scroll', () => {
  const s = document.documentElement.scrollTop;
  const h = document.documentElement.scrollHeight - window.innerHeight;
  document.getElementById('pbar').style.width = (s / h * 100) + '%';
});

function toggleFaq(btn) {
  const item = btn.parentElement;
  const isOpen = item.classList.contains('open');
  document.querySelectorAll('.faq-item.open').forEach(i => i.classList.remove('open'));
  if (!isOpen) item.classList.add('open');
}