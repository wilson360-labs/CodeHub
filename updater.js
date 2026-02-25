// ====================================================
// SISTEMA DE ACTUALIZACIÓN AUTOMÁTICA DESDE PASTEBIN
// CodeHub - Premium Apps
// ====================================================

// CONFIGURACIÓN
const CONFIG = {
    // Usa el proxy CORS para pruebas locales
    // Cuando subas a producción, usa la URL directa de Pastebin
    pastebinURL: 'https://api.allorigins.win/raw?url=https://pastebin.com/raw/DTSfarwd',
    // pastebinURL: 'https://pastebin.com/raw/DTSfarwd', // Usa esta en producción
    checkInterval: 300000, // Revisar cada 5 minutos (300000ms)
    enableAutoUpdate: true, // Cambiar a false para actualización manual
    showNotifications: true,
    updateAnimations: true,
    showManualButton: false // DESACTIVADO: No mostrar botón de actualización manual
};

// Variables globales
let currentVersion = localStorage.getItem('appVersion') || '1.0.0';
let updateCheckTimer = null;

// ====================================================
// FUNCIÓN PRINCIPAL DE ACTUALIZACIÓN
// ====================================================
async function checkForUpdates(showMessage = false) {
    const loadingIndicator = document.getElementById('loading-indicator');
    
    try {
        if (loadingIndicator) loadingIndicator.style.display = 'block';
        
        // Obtener datos desde Pastebin
        const response = await fetch(CONFIG.pastebinURL, {
            cache: 'no-cache',
            headers: {
                'Cache-Control': 'no-cache'
            }
        });
        
        if (!response.ok) {
            throw new Error('No se pudo conectar con el servidor');
        }
        
        const data = await response.json();
        
        // Verificar si hay actualización
        if (data.version !== currentVersion) {
            showUpdateNotification(data);
            if (CONFIG.enableAutoUpdate) {
                await applyUpdate(data);
            }
        } else if (showMessage) {
            showNotification('✅ Estás usando la última versión', 'success');
        }
        
        // Guardar última comprobación
        localStorage.setItem('lastUpdateCheck', new Date().toISOString());
        
    } catch (error) {
        console.error('Error al verificar actualizaciones:', error);
        if (showMessage) {
            showNotification('❌ Error al verificar actualizaciones', 'error');
        }
    } finally {
        if (loadingIndicator) loadingIndicator.style.display = 'none';
    }
}

// ====================================================
// APLICAR ACTUALIZACIONES
// ====================================================
async function applyUpdate(data) {
    try {
        showNotification('🔄 Aplicando actualización...', 'info');
        
        // Actualizar apps
        if (data.apps && data.apps.length > 0) {
            updateAppsGrid(data.apps);
        }
        
        // Actualizar avisos de seguridad
        if (data.securityInfo) {
            updateSecurityInfo(data.securityInfo);
        }
        
        // Actualizar estilos personalizados
        if (data.customCSS) {
            applyCustomCSS(data.customCSS);
        }
        
        // Actualizar versión
        currentVersion = data.version;
        localStorage.setItem('appVersion', currentVersion);
        
        showNotification(`✅ Actualizado a versión ${data.version}`, 'success');
        
        // Animar cambios
        if (CONFIG.updateAnimations) {
            animateUpdates();
        }
        
    } catch (error) {
        console.error('Error al aplicar actualización:', error);
        showNotification('❌ Error al aplicar actualización', 'error');
    }
}

// ====================================================
// ACTUALIZAR GRID DE APLICACIONES
// ====================================================
function updateAppsGrid(apps) {
    const container = document.getElementById('apps-container');
    if (!container) return;
    
    // Limpiar contenedor
    container.innerHTML = '';
    
    // Agregar apps actualizadas
    apps.forEach(app => {
        const appCard = createAppCard(app);
        container.appendChild(appCard);
    });
}

// ====================================================
// CREAR TARJETA DE APP
// ====================================================
function createAppCard(app) {
    const card = document.createElement('div');
    card.className = 'app-card';
    card.setAttribute('data-app-id', app.id);
    
    // Determinar badge
    let badge = '';
    if (app.isNew) {
        badge = '<span class="update-tag">🆕</span>';
    } else if (app.isUpdated) {
        badge = '<span class="update-tag">🔄 Actualizada</span>';
    } else if (app.isFeatured) {
        badge = '<span class="update-tag">⭐</span>';
    }
    
    card.innerHTML = `
        <img src="${app.icon}" alt="${app.name}">
        <div class="app-info">
            <h2>${app.name} ${badge}</h2>
            <p>${app.description}</p>
            ${app.downloadLinks.map((link, index) => `
                <a href="${link.url}" class="btn ${index > 0 ? 'secondary-btn' : ''}">
                    ${link.label || 'Descargar'}
                </a>
            `).join('')}
        </div>
    `;
    
    return card;
}

// ====================================================
// ACTUALIZAR INFORMACIÓN DE SEGURIDAD
// ====================================================
function updateSecurityInfo(info) {
    const securitySection = document.querySelector('.info-seguridad');
    if (!securitySection) return;
    
    securitySection.innerHTML = `
        <h2>${info.title || 'Seguridad y Uso Responsable'}</h2>
        <p>${info.message}</p>
    `;
}

// ====================================================
// APLICAR CSS PERSONALIZADO
// ====================================================
function applyCustomCSS(css) {
    let styleElement = document.getElementById('custom-update-styles');
    
    if (!styleElement) {
        styleElement = document.createElement('style');
        styleElement.id = 'custom-update-styles';
        document.head.appendChild(styleElement);
    }
    
    styleElement.textContent = css;
}

// ====================================================
// MOSTRAR NOTIFICACIONES
// ====================================================
function showNotification(message, type = 'info') {
    if (!CONFIG.showNotifications) return;
    
    const notification = document.getElementById('update-notification');
    if (!notification) return;
    
    // Configurar notificación
    notification.textContent = message;
    notification.className = `update-notification show ${type}`;
    
    // Auto-ocultar después de 5 segundos
    setTimeout(() => {
        notification.classList.remove('show');
    }, 5000);
}

// ====================================================
// ANIMAR ACTUALIZACIONES
// ====================================================
function animateUpdates() {
    const cards = document.querySelectorAll('.app-card');
    
    cards.forEach((card, index) => {
        card.style.opacity = '0';
        card.style.transform = 'translateY(20px)';
        
        setTimeout(() => {
            card.style.transition = 'all 0.5s ease';
            card.style.opacity = '1';
            card.style.transform = 'translateY(0)';
        }, index * 100);
    });
}

// ====================================================
// MOSTRAR NOTIFICACIÓN DE ACTUALIZACIÓN
// ====================================================
function showUpdateNotification(data) {
    const notification = document.createElement('div');
    notification.className = 'update-banner';
    notification.innerHTML = `
        <div class="update-banner-content">
            <h3>🎉 Nueva actualización disponible</h3>
            <p>Versión ${data.version} - ${data.updateMessage || 'Nuevas apps y mejoras'}</p>
            <div class="update-banner-actions">
                <button onclick="window.location.reload()" class="update-btn">Actualizar ahora</button>
                <button onclick="this.parentElement.parentElement.parentElement.remove()" class="dismiss-btn">Más tarde</button>
            </div>
        </div>
    `;
    
    document.body.insertBefore(notification, document.body.firstChild);
}

// ====================================================
// FUNCIÓN DE BÚSQUEDA
// ====================================================
function buscarApp() {
    let input = document.getElementById("search").value.toLowerCase();
    let apps = document.querySelectorAll(".app-card");
    
    apps.forEach(app => {
        let nombre = app.querySelector("h2").innerText.toLowerCase();
        if (nombre.includes(input)) {
            app.style.display = "block";
        } else {
            app.style.display = "none";
        }
    });
}

// ====================================================
// CREAR PARTÍCULAS ANIMADAS
// ====================================================
function createParticles() {
    const particlesContainer = document.getElementById('particles');
    if (!particlesContainer) return;
    
    const particleCount = 50;
    
    for (let i = 0; i < particleCount; i++) {
        const particle = document.createElement('div');
        particle.className = 'particle';
        particle.style.left = Math.random() * 100 + '%';
        particle.style.animationDelay = Math.random() * 8 + 's';
        particle.style.animationDuration = (Math.random() * 10 + 5) + 's';
        particlesContainer.appendChild(particle);
    }
}

// ====================================================
// INICIALIZAR SISTEMA
// ====================================================
function initUpdateSystem() {
    console.log('🚀 Sistema de actualizaciones iniciado');
    console.log(`📱 Versión actual: ${currentVersion}`);
    
    // Verificar actualizaciones al cargar
    checkForUpdates();
    
    // Configurar verificación automática
    if (CONFIG.enableAutoUpdate) {
        updateCheckTimer = setInterval(() => {
            checkForUpdates();
        }, CONFIG.checkInterval);
        
        console.log(`⏰ Revisión automática cada ${CONFIG.checkInterval / 60000} minutos`);
    }
    
    // Timeout forzado para ocultar loading si se queda atascado
    setTimeout(() => {
        const loadingIndicator = document.getElementById('loading-indicator');
        if (loadingIndicator && loadingIndicator.style.display !== 'none') {
            loadingIndicator.style.display = 'none';
            console.log('Loading indicator ocultado forzosamente');
        }
    }, 10000); // 10 segundos
}

// ====================================================
// EVENT LISTENERS
// ====================================================

// Búsqueda con Enter
document.addEventListener('DOMContentLoaded', function() {
    const searchInput = document.getElementById('search');
    if (searchInput) {
        searchInput.addEventListener('keypress', function(e) {
            if (e.key === 'Enter') {
                buscarApp();
            }
        });
    }
    
    // Crear partículas
    createParticles();
    
    // Inicializar sistema de actualización
    initUpdateSystem();
    
    // Mensajes de consola
    console.log('%c💎 CodeHub Premium Apps', 'color: #667eea; font-size: 20px; font-weight: bold;');
    console.log('%c🔄 Sistema de actualización automática activo', 'color: #4ecdc4; font-size: 14px;');
});

// Exponer funciones globales para uso en HTML
window.checkForUpdates = checkForUpdates;
window.buscarApp = buscarApp;