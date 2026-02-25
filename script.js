document.getElementById('floating-menu').addEventListener('click', () => {
    alert('Botón flotante clicado!'); // Reemplaza con la funcionalidad deseada
});

document.getElementById('floating-menu').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        alert('Botón flotante activado con teclado!'); // Reemplaza con la funcionalidad deseada
    }
});
window.addEventListener('load', () => {
    // El splash se maneja en index.html con duración de 6 segundos
    // No ocultar aquí para evitar conflictos
});
