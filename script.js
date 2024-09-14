window.addEventListener('load', () => {
    const splashScreen = document.getElementById('splash-screen');
    splashScreen.style.opacity = 0;
    splashScreen.style.transition = 'opacity 8s ease-out';
    setTimeout(() => {
        splashScreen.style.display = 'none';
    }, 2000); // Asegúrate de que el splash screen se oculte después de la animación
});
