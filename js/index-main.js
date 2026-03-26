/* ═══════════════════════════════════════
   index — Main Scripts
   Partículas, animaciones, filtros, traducción
   CodeHub by Wilson.E
═══════════════════════════════════════ */

console.log('Script started');
        // Crear partículas mejoradas con drift
        function createParticles() {
            const container = document.getElementById('particles1');
            container.innerHTML = '';
            for (let i = 0; i < 80; i++) { // Aumentado de 50 a 80
                const particle = document.createElement('div');
                particle.className = 'particle';
                particle.style.left = Math.random() * 100 + '%';
                particle.style.top = Math.random() * 100 + '%';
                particle.style.setProperty('--drift', (Math.random() - 0.5) * 200 + 'px');
                particle.style.animationDelay = Math.random() * 4 + 's';
                particle.style.animationDuration = (3 + Math.random() * 4) + 's';
                
                // Variación de tamaño
                const size = 2 + Math.random() * 4;
                particle.style.width = size + 'px';
                particle.style.height = size + 'px';
                
                container.appendChild(particle);
            }
        }

        // Animar porcentaje de carga
        function animatePercentage() {
            const percentageEl = document.getElementById('percentage');
            let count = 0;
            const duration = 2000; // 2 segundos
            const steps = 100;
            const intervalTime = duration / steps;
            const interval = setInterval(() => {
                count++;
                if (count >= 100) {
                    count = 100;
                    clearInterval(interval);
                }
                percentageEl.textContent = count + '%';
            }, intervalTime);
        }

        // Efecto de pulso en el logo
        function addLogoPulse() {
            const logo = document.querySelector('.logo-3d');
            setInterval(() => {
                logo.style.transform = 'perspective(1000px) rotateX(0) scale(1.05)';
                setTimeout(() => {
                    logo.style.transform = 'perspective(1000px) rotateX(0) scale(1)';
                }, 200);
            }, 3000);
        }

        // Inicializar todo
        createParticles();
        animatePercentage();
        addLogoPulse();
        console.log('Initial functions called');

        // Modo oscuro — sincronizar con el toggle flotante (.theme-toggle-circle)
        if (localStorage.getItem('theme') === 'light') {
            document.body.classList.add('light-mode');
            const icon = document.querySelector('.theme-toggle-circle i');
            if (icon) icon.className = 'fas fa-sun';
        }

        // Beta Test
        const betaBtn = document.getElementById('beta-btn');
        if (betaBtn) {
            betaBtn.addEventListener('click', () => {
                alert('¡Únete al Beta Test! Envía un email a beta@codehub.com para participar.');
            });
        }

        // Actualización manual (update-btn ya no está en el nav, pero lo dejamos por si existe en otro lado)
        const updateBtn = document.getElementById('update-btn');
        if (updateBtn) {
            updateBtn.addEventListener('click', () => {
                if (typeof checkForUpdates === 'function') checkForUpdates(true);
            });
        }

        // Función central para cerrar el splash
        function closeSplash() {
            const splash = document.getElementById('splash-screen');
            if (!splash || splash.style.display === 'none') return;
            splash.style.opacity = '0';
            splash.style.transition = 'opacity 0.8s ease';
            setTimeout(() => {
                splash.style.display = 'none';
                console.log('✅ Splash cerrado');
                if (typeof animateCounters === 'function') animateCounters();
                if (typeof animateVisitorCounter === 'function') animateVisitorCounter();
                if (typeof typeEffect === 'function') typeEffect();
            }, 800);
        }

        // Skip splash
        const skipBtn = document.getElementById('skip-splash');
        if (skipBtn) {
            skipBtn.addEventListener('click', closeSplash);
        }

        // ── SPLASH TIPS & BARRA PROGRESO (8s) ─────────────────
        (function() {
            const tips = [
                'INICIALIZANDO CODEHUB<span class="loading-dots"></span>',
                '🧠 Cargando red neuronal...',
                '🛠️ Activando 18 herramientas...',
                '🔐 Módulo de seguridad listo...',
                '🎨 Calibrando paleta de colores...',
                '📱 Sincronizando catálogo de apps...',
                '⚡ Optimizando rendimiento...',
                '🚀 ¡Todo listo. Bienvenido!'
            ];
            let i = 0;
            const el = document.querySelector('.loading-text-3d');
            const iv = setInterval(() => {
                i++;
                if (i < tips.length && el) {
                    el.style.opacity = '0';
                    setTimeout(() => {
                        el.innerHTML = tips[i];
                        el.style.opacity = '1';
                        el.style.transition = 'opacity .3s';
                    }, 160);
                }
                if (i >= tips.length - 1) clearInterval(iv);
            }, 1000);

            // Barra de progreso sincronizada a 8 segundos
            const pct = document.getElementById('percentage');
            const bar = document.querySelector('.progress-bar-3d');
            if (pct) pct.textContent = '0%';
            let n = 0;
            let turnstileSolved = false;
            let progressDone = false;

            // Callback global cuando Turnstile se resuelve
            window.onSplashTurnstileSuccess = function() {
                turnstileSolved = true;
                const wrap = document.getElementById('splash-turnstile-wrap');
                if (wrap) {
                    wrap.innerHTML = '<p style="font-family:JetBrains Mono,monospace;font-size:.7rem;color:#00e676;letter-spacing:.1em">✅ VERIFICACIÓN COMPLETADA</p>';
                }
                // Si la barra ya terminó, cerrar inmediatamente
                if (progressDone) setTimeout(closeSplash, 600);
            };

            const pIv = setInterval(() => {
                n++;
                const display = Math.min(100, Math.round(n * 1.1 - Math.pow(n / 10, 1.4)));
                if (pct) pct.textContent = Math.min(display, 100) + '%';
                if (bar) bar.style.width = Math.min(n, 100) + '%';

                // Al 80% mostrar el Turnstile
                if (n === 80) {
                    const wrap = document.getElementById('splash-turnstile-wrap');
                    if (wrap) wrap.style.display = 'flex';
                }

                if (n >= 100) {
                    clearInterval(pIv);
                    progressDone = true;
                    // Solo cerrar si Turnstile ya fue resuelto
                    if (turnstileSolved) {
                        setTimeout(closeSplash, 600);
                    } else {
                        // Mostrar botón saltar como fallback tras 6s adicionales
                        setTimeout(() => {
                            if (document.getElementById('splash-screen')?.style.display !== 'none') {
                                const skip = document.getElementById('skip-splash');
                                if (skip) skip.style.display = 'block';
                            }
                        }, 6000);
                    }
                }
            }, 80);
        })();

        // Cerrar splash automáticamente si Turnstile no carga (fallback 14s)
        setTimeout(closeSplash, 14000);

        // Fallback de emergencia a los 10 segundos
        setTimeout(() => {
            const splash = document.getElementById('splash-screen');
            if (splash && splash.style.display !== 'none') {
                console.warn('⚠️ Fallback: cerrando splash forzosamente');
                splash.style.display = 'none';
                if (typeof animateCounters === 'function') animateCounters();
                if (typeof animateVisitorCounter === 'function') animateVisitorCounter();
                if (typeof typeEffect === 'function') typeEffect();
            }
        }, 10000);
        
        // ========== FUNCIONES BÁSICAS ==========
        
        // Typing Effect
        const typingPhrases = [
            'Desarrollador Web Full Stack 💻',
            'Especialista en Python 🐍',
            'Creador de Experiencias Digitales ✨',
            'De Guatemala para el Mundo 🇬🇹',
            'Programador Apasionado 🚀',
            'Problem Solver 🧠'
        ];
        let phraseIndex = 0;
        let charIndex = 0;
        let isDeleting = false;

        function typeEffect() {
            const typingElement = document.getElementById('typingEffect');
            const currentPhrase = typingPhrases[phraseIndex];
            
            if (isDeleting) {
                typingElement.textContent = currentPhrase.substring(0, charIndex - 1);
                charIndex--;
            } else {
                typingElement.textContent = currentPhrase.substring(0, charIndex + 1);
                charIndex++;
            }
            
            typingElement.innerHTML += '<span class="typing-cursor">|</span>';
            
            if (!isDeleting && charIndex === currentPhrase.length) {
                setTimeout(() => isDeleting = true, 2000);
            } else if (isDeleting && charIndex === 0) {
                isDeleting = false;
                phraseIndex = (phraseIndex + 1) % typingPhrases.length;
            }
            
            setTimeout(typeEffect, isDeleting ? 50 : 100);
        }

        // Visitor Counter — real con localStorage
        function animateVisitorCounter() {
            const KEY_COUNT = 'ch_visits';
            const KEY_DATE  = 'ch_visit_date';
            const today = new Date().toDateString();

            let total  = parseInt(localStorage.getItem(KEY_COUNT) || '0');
            const last = localStorage.getItem(KEY_DATE);

            // Contar solo 1 visita nueva por día por dispositivo
            if (last !== today) {
                total += 1;
                localStorage.setItem(KEY_COUNT, total);
                localStorage.setItem(KEY_DATE, today);
            }

            // Animar el número
            const counter = document.getElementById('visitorCount');
            if (!counter) return;
            const start = Math.max(0, total - 80);
            const duration = 1600;
            const startTime = performance.now();

            function step(now) {
                const p = Math.min((now - startTime) / duration, 1);
                const ease = 1 - Math.pow(1 - p, 3);
                counter.textContent = Math.floor(start + (total - start) * ease).toLocaleString();
                if (p < 1) requestAnimationFrame(step);
                else counter.textContent = total.toLocaleString();
            }
            requestAnimationFrame(step);
        }

        // Actualizar label del counter
        (function() {
            const wrap = document.querySelector('.footer-visitors span');
            if (wrap) wrap.textContent = 'Visitas totales:';
        })();

        // Neural Network Animation
        const canvas = document.getElementById('neural-network');
        const ctx = canvas.getContext('2d');
        let nodes = [];
        const mouse = { x: 0, y: 0 };

        class Node {
            constructor() {
                this.x = Math.random() * canvas.width;
                this.y = Math.random() * canvas.height;
                this.radius = 2;
                this.speedX = (Math.random() - 0.5) * 0.5;
                this.speedY = (Math.random() - 0.5) * 0.5;
            }

            draw() {
                ctx.beginPath();
                ctx.arc(this.x, this.y, this.radius, 0, Math.PI * 2);
                ctx.fillStyle = 'rgba(255, 69, 0, 0.8)';
                ctx.fill();
            }

            update() {
                this.x += this.speedX;
                this.y += this.speedY;

                if (this.x < 0 || this.x > canvas.width) this.speedX *= -1;
                if (this.y < 0 || this.y > canvas.height) this.speedY *= -1;

                const dx = mouse.x - this.x;
                const dy = mouse.y - this.y;
                const distance = Math.sqrt(dx * dx + dy * dy);
                if (distance < 100) {
                    this.x += dx * 0.01;
                    this.y += dy * 0.01;
                }
            }
        }

        function init() {
            canvas.width = window.innerWidth;
            canvas.height = window.innerHeight;
            nodes = [];
            for (let i = 0; i < 60; i++) {
                nodes.push(new Node());
            }
        }

        function animate() {
            ctx.clearRect(0, 0, canvas.width, canvas.height);

            nodes.forEach(node => {
                node.update();
                node.draw();
            });

            for (let i = 0; i < nodes.length; i++) {
                for (let j = i + 1; j < nodes.length; j++) {
                    const dx = nodes[i].x - nodes[j].x;
                    const dy = nodes[i].y - nodes[j].y;
                    const distance = Math.sqrt(dx * dx + dy * dy);
                    
                    if (distance < 150) {
                        ctx.beginPath();
                        ctx.moveTo(nodes[i].x, nodes[i].y);
                        ctx.lineTo(nodes[j].x, nodes[j].y);
                        ctx.strokeStyle = `rgba(255, 69, 0, ${0.3 * (1 - distance / 150)})`;
                        ctx.lineWidth = 0.5;
                        ctx.stroke();
                    }
                }
            }

            requestAnimationFrame(animate);
        }

        window.addEventListener('mousemove', (e) => {
            mouse.x = e.clientX;
            mouse.y = e.clientY;
            
            if (Math.random() > 0.85) {
                createCursorParticle(e.clientX, e.clientY);
            }
        });

        window.addEventListener('resize', init);
        init();
        animate();

        // Cursor Particles
        function createCursorParticle(x, y) {
            const particle = document.createElement('div');
            particle.className = 'cursor-particle';
            particle.style.left = x + 'px';
            particle.style.top = y + 'px';
            document.body.appendChild(particle);
            setTimeout(() => particle.remove(), 1000);
        }

        // Theme Toggle
        function toggleTheme() {
            const body = document.body;
            const icon = document.querySelector('.theme-toggle-circle i');
            
            if (body.classList.contains('light-mode')) {
                body.classList.remove('light-mode');
                icon.className = 'fas fa-moon';
                localStorage.setItem('theme', 'dark');
            } else {
                body.classList.add('light-mode');
                icon.className = 'fas fa-sun';
                localStorage.setItem('theme', 'light');
            }
        }

        if (localStorage.getItem('theme') === 'light') {
            document.body.classList.add('light-mode');
            document.querySelector('.theme-toggle-circle i').className = 'fas fa-sun';
        }

        // ========== MENÚ EXPERIMENTAL ==========
        function toggleExperimentalMenu() {
            const menu = document.getElementById('experimental-menu');
            const handle = document.getElementById('exp-handle');
            menu.classList.toggle('active');
            if (handle) handle.classList.toggle('open', menu.classList.contains('active'));
        }

        // Matrix Mode
        function activateMatrixMode() {
            const matrixCanvas = document.createElement('canvas');
            matrixCanvas.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:100%;z-index:9999;pointer-events:none;';
            document.body.appendChild(matrixCanvas);
            
            const ctx = matrixCanvas.getContext('2d');
            matrixCanvas.width = window.innerWidth;
            matrixCanvas.height = window.innerHeight;
            
            const chars = '01アイウエオカキクケコサシスセソタチツテト';
            const fontSize = 16;
            const columns = matrixCanvas.width / fontSize;
            const drops = Array(Math.floor(columns)).fill(1);
            
            function drawMatrix() {
                ctx.fillStyle = 'rgba(0, 0, 0, 0.05)';
                ctx.fillRect(0, 0, matrixCanvas.width, matrixCanvas.height);
                
                ctx.fillStyle = '#0F0';
                ctx.font = fontSize + 'px monospace';
                
                for (let i = 0; i < drops.length; i++) {
                    const text = chars[Math.floor(Math.random() * chars.length)];
                    ctx.fillText(text, i * fontSize, drops[i] * fontSize);
                    
                    if (drops[i] * fontSize > matrixCanvas.height && Math.random() > 0.975) {
                        drops[i] = 0;
                    }
                    drops[i]++;
                }
            }
            
            const interval = setInterval(drawMatrix, 35);
            
            setTimeout(() => {
                clearInterval(interval);
                matrixCanvas.remove();
            }, 10000);
            
            toggleExperimentalMenu();
            alert('🟢 Matrix Mode activado por 10 segundos!');
        }

        // Particle Explosion
        function activateParticleExplosion() {
            for (let i = 0; i < 100; i++) {
                setTimeout(() => {
                    const particle = document.createElement('div');
                    particle.style.cssText = `
                        position: fixed;
                        top: 50%;
                        left: 50%;
                        width: 10px;
                        height: 10px;
                        background: hsl(${Math.random() * 360}, 100%, 50%);
                        border-radius: 50%;
                        z-index: 9999;
                        pointer-events: none;
                    `;
                    document.body.appendChild(particle);
                    
                    const angle = (Math.PI * 2 * i) / 100;
                    const velocity = 5 + Math.random() * 10;
                    let x = 0, y = 0;
                    
                    const animateParticle = () => {
                        x += Math.cos(angle) * velocity;
                        y += Math.sin(angle) * velocity;
                        particle.style.transform = `translate(${x}px, ${y}px) scale(${1 - Math.abs(x) / 500})`;
                        particle.style.opacity = 1 - Math.abs(x) / 500;
                        
                        if (Math.abs(x) < 500) {
                            requestAnimationFrame(animateParticle);
                        } else {
                            particle.remove();
                        }
                    };
                    animateParticle();
                }, i * 10);
            }
            toggleExperimentalMenu();
        }

        // Screen Shake
        function activateScreenShake() {
            const body = document.body;
            let count = 0;
            const maxShakes = 20;
            
            const shake = setInterval(() => {
                const x = (Math.random() - 0.5) * 20;
                const y = (Math.random() - 0.5) * 20;
                body.style.transform = `translate(${x}px, ${y}px)`;
                count++;
                
                if (count >= maxShakes) {
                    clearInterval(shake);
                    body.style.transform = '';
                }
            }, 50);
            
            toggleExperimentalMenu();
        }

        // Glitch Effect
        function activateGlitchEffect() {
            const sections = document.querySelectorAll('section, header');
            let count = 0;
            
            const glitch = setInterval(() => {
                sections.forEach(section => {
                    section.style.transform = `translate(${(Math.random() - 0.5) * 10}px, ${(Math.random() - 0.5) * 10}px)`;
                    section.style.filter = `hue-rotate(${Math.random() * 360}deg)`;
                });
                count++;
                
                if (count >= 30) {
                    clearInterval(glitch);
                    sections.forEach(section => {
                        section.style.transform = '';
                        section.style.filter = '';
                    });
                }
            }, 100);
            
            toggleExperimentalMenu();
        }

        // Rainbow Scroll
        let rainbowActive = false;
        function activateRainbowScroll() {
            rainbowActive = !rainbowActive;
            
            if (rainbowActive) {
                window.addEventListener('scroll', rainbowScrollHandler);
                alert('🌈 Rainbow Scroll activado! Desplázate para ver el efecto');
            } else {
                window.removeEventListener('scroll', rainbowScrollHandler);
                document.body.style.filter = '';
            }
            
            toggleExperimentalMenu();
        }

        function rainbowScrollHandler() {
            const scrollPercent = (window.scrollY / (document.body.scrollHeight - window.innerHeight)) * 360;
            document.body.style.filter = `hue-rotate(${scrollPercent}deg)`;
        }

        // Voice Commands (Experimental)
        function activateVoiceCommands() {
            if (!('webkitSpeechRecognition' in window)) {
                alert('❌ Tu navegador no soporta reconocimiento de voz');
                return;
            }
            
            const recognition = new webkitSpeechRecognition();
            recognition.lang = 'es-ES';
            recognition.continuous = false;
            
            recognition.onresult = (event) => {
                const command = event.results[0][0].transcript.toLowerCase();
                
                if (command.includes('inicio')) {
                    window.scrollTo({ top: 0, behavior: 'smooth' });
                } else if (command.includes('contacto')) {
                    document.getElementById('contact').scrollIntoView({ behavior: 'smooth' });
                } else if (command.includes('proyecto')) {
                    document.getElementById('latest-projects').scrollIntoView({ behavior: 'smooth' });
                } else if (command.includes('habilidad')) {
                    document.getElementById('skills').scrollIntoView({ behavior: 'smooth' });
                }
            };
            
            recognition.start();
            alert('🎤 Escuchando... Di "inicio", "contacto", "proyectos" o "habilidades"');
            toggleExperimentalMenu();
        }

        // Code Rain
        function activateCodeRain() {
            for (let i = 0; i < 50; i++) {
                setTimeout(() => {
                    const code = document.createElement('div');
                    code.textContent = Math.random() > 0.5 ? '0' : '1';
                    code.style.cssText = `
                        position: fixed;
                        top: -20px;
                        left: ${Math.random() * 100}%;
                        color: var(--primary);
                        font-size: ${20 + Math.random() * 30}px;
                        font-family: monospace;
                        z-index: 9999;
                        pointer-events: none;
                        animation: fall ${3 + Math.random() * 3}s linear forwards;
                    `;
                    document.body.appendChild(code);
                    setTimeout(() => code.remove(), 6000);
                }, i * 100);
            }
            
            const style = document.createElement('style');
            style.textContent = `
                @keyframes fall {
                    to {
                        transform: translateY(100vh) rotate(360deg);
                        opacity: 0;
                    }
                }
            `;
            document.head.appendChild(style);
            
            toggleExperimentalMenu();
        }

        // Screenshot
        function takeScreenshot() {
            alert('📸 Función de screenshot en desarrollo. Por ahora, usa Ctrl+Shift+S en tu navegador.');
            toggleExperimentalMenu();
        }

        // Export Portfolio
        function exportPortfolio() {
            const info = `
PORTFOLIO - Wilson.E
====================
Email: wilson.e360labs@gmail.com
Telegram: @d3exg3aeyag1ko
WhatsApp: +502 4146 8185

HABILIDADES:
- HTML5: 90%
- CSS3: 85%
- JavaScript: 75%
- Python: 80%
- Git: 70%

Ubicación: San Luis Jilotepeque, Jalapa, Guatemala
            `;
            
            const blob = new Blob([info], { type: 'text/plain' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'Wilson_Portfolio.txt';
            a.click();
            
            toggleExperimentalMenu();
            alert('📥 Portfolio exportado!');
        }

        // ========== RESTO DE FUNCIONES ==========
        
        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if (e.ctrlKey && e.key === 'k') {
                e.preventDefault();
                openCommandPalette();
            }
            
            if (e.altKey && e.key === 't') {
                e.preventDefault();
                toggleTheme();
            }
            
            if (e.key === 'Escape') {
                closeCommandPalette();
                const menu = document.getElementById('experimental-menu');
                if (menu.classList.contains('active')) {
                    toggleExperimentalMenu();
                }
            }
        });

        // Command Palette
        const commands = [
            { name: 'Ir a Estadísticas', icon: 'fa-chart-line', action: () => scrollToSection('stats') },
            { name: 'Ir a Habilidades', icon: 'fa-code', action: () => scrollToSection('skills') },
            { name: 'Ir a Servicios', icon: 'fa-briefcase', action: () => scrollToSection('services') },
            { name: 'Ir a Logros', icon: 'fa-trophy', action: () => scrollToSection('achievements') },
            { name: 'Ir a Experiencia', icon: 'fa-calendar', action: () => scrollToSection('timeline') },
            { name: 'Ir a Proyectos', icon: 'fa-folder', action: () => scrollToSection('latest-projects') },
            { name: 'Ir a Testimonios', icon: 'fa-comment', action: () => scrollToSection('testimonials') },
            { name: 'Ir a Sobre Mí', icon: 'fa-user', action: () => scrollToSection('about') },
            { name: 'Ir a Contacto', icon: 'fa-envelope', action: () => scrollToSection('contact') },
            { name: 'Ir a Juegos', icon: 'fa-gamepad', action: () => scrollToSection('game') },
            { name: 'Cambiar Tema', icon: 'fa-moon', action: toggleTheme },
            { name: 'Copiar Email', icon: 'fa-copy', action: copyEmail },
            { name: 'Ver Novedades', icon: 'fa-star', action: openNewWindow },
            { name: 'Jugar Snake', icon: 'fa-gamepad', action: () => openGameWindow('snake') },
            { name: 'Jugar Tetris', icon: 'fa-table-cells', action: () => openGameWindow('tetris') },
            { name: 'Menú Experimental', icon: 'fa-flask', action: toggleExperimentalMenu }
        ];

        function openCommandPalette() {
            const overlay = document.getElementById('command-overlay');
            const input = document.getElementById('commandInput');
            overlay.classList.add('active');
            input.focus();
            renderCommands(commands);
        }

        function closeCommandPalette(event) {
            if (!event || event.target.id === 'command-overlay') {
                document.getElementById('command-overlay').classList.remove('active');
                document.getElementById('commandInput').value = '';
            }
        }

        function renderCommands(commandList) {
            const results = document.getElementById('commandResults');
            results.innerHTML = commandList.map((cmd, index) => `
                <div class="command-item" onclick="executeCommand(${index})">
                    <i class="fas ${cmd.icon}"></i>
                    <span>${cmd.name}</span>
                </div>
            `).join('');
        }

        function executeCommand(index) {
            commands[index].action();
            closeCommandPalette();
        }

        document.getElementById('commandInput').addEventListener('input', (e) => {
            const search = e.target.value.toLowerCase();
            const filtered = commands.filter(cmd => 
                cmd.name.toLowerCase().includes(search)
            );
            renderCommands(filtered);
        });

        function scrollToSection(id) {
            document.getElementById(id).scrollIntoView({ behavior: 'smooth' });
        }

        // Scroll Progress Bar
        window.addEventListener('scroll', () => {
            const winScroll = document.documentElement.scrollTop;
            const height = document.documentElement.scrollHeight - document.documentElement.clientHeight;
            const scrolled = (winScroll / height) * 100;
            document.getElementById('scroll-progress').style.width = scrolled + '%';
        });

        // Stats Counter Animation
        function animateCounters() {
            const counters = document.querySelectorAll('.stat-number');
            counters.forEach(counter => {
                const target = parseInt(counter.getAttribute('data-target'));
                const duration = 2000;
                const increment = target / (duration / 16);
                let current = 0;
                
                const updateCounter = () => {
                    current += increment;
                    if (current < target) {
                        counter.textContent = Math.floor(current);
                        requestAnimationFrame(updateCounter);
                    } else {
                        counter.textContent = target;
                    }
                };
                updateCounter();
            });
        }

        // Copy Email Function
        function copyEmail() {
            const email = 'wilson.e360labs@gmail.com';
            navigator.clipboard.writeText(email).then(() => {
                const btn = event.target.closest('.copy-email');
                const originalText = btn.innerHTML;
                btn.innerHTML = '<i class="fas fa-check"></i> ¡Copiado!';
                btn.style.background = 'linear-gradient(45deg, #00ff00, #00cc00)';
                
                setTimeout(() => {
                    btn.innerHTML = originalText;
                    btn.style.background = '';
                }, 2000);
            });
        }

        // Game and Menu Functions
        function openGameWindow(game) {
            const games = {
                'snake': 'snake.html',
                'tetris': 'tetris.html'
            };
            
            if (games[game]) {
                window.open(games[game], '_blank', 'width=500,height=500,resizable=yes');
            }
        }

        function openNewWindow() {
            window.open('novedades.html', '_blank', 'width=800,height=600,resizable=yes');
        }

        // Konami Code Easter Egg (↑ ↑ ↓ ↓ ← → ← → B A)
        let konamiCode = [];
        const konamiSequence = ['ArrowUp', 'ArrowUp', 'ArrowDown', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'ArrowLeft', 'ArrowRight', 'b', 'a'];

        document.addEventListener('keydown', (e) => {
            konamiCode.push(e.key);
            konamiCode = konamiCode.slice(-10);
            
            if (konamiCode.join(',') === konamiSequence.join(',')) {
                activateKonamiMode();
            }
        });

        function activateKonamiMode() {
            document.body.classList.add('konami-active');
            
            // Crear confetti effect
            for (let i = 0; i < 100; i++) {
                setTimeout(() => {
                    const confetti = document.createElement('div');
                    confetti.style.cssText = `
                        position: fixed;
                        top: -10px;
                        left: ${Math.random() * 100}%;
                        width: 10px;
                        height: 10px;
                        background: hsl(${Math.random() * 360}, 100%, 50%);
                        z-index: 9999;
                        animation: fall ${2 + Math.random() * 3}s linear forwards;
                    `;
                    document.body.appendChild(confetti);
                    setTimeout(() => confetti.remove(), 5000);
                }, i * 20);
            }

            const style = document.createElement('style');
            style.textContent = `
                @keyframes fall {
                    to {
                        transform: translateY(100vh) rotate(360deg);
                        opacity: 0;
                    }
                }
            `;
            document.head.appendChild(style);
            
            alert('🎉 ¡Código Konami activado! Modo ARCOÍRIS 🌈');
            
            setTimeout(() => {
                document.body.classList.remove('konami-active');
            }, 10000);
        }

        // Console Easter Egg
        console.log('%c¡Hola Developer! 👋', 'color: #ff4500; font-size: 24px; font-weight: bold;');
        console.log('%c¿Curioseando el código? Me gusta tu estilo 😎', 'color: #ffbd69; font-size: 16px;');
        console.log('%cPrueba el código Konami: ↑ ↑ ↓ ↓ ← → ← → B A', 'color: #ff6b35; font-size: 14px;');
        console.log('%cO presiona Ctrl+K para la búsqueda rápida 🚀', 'color: #f7931e; font-size: 14px;');
        console.log('%cAbre el Menú Experimental en el lado izquierdo 🧪', 'color: #ff4500; font-size: 14px;');

        // Smooth scroll para todos los enlaces internos
        document.querySelectorAll('a[href^="#"]').forEach(anchor => {
            anchor.addEventListener('click', function (e) {
                e.preventDefault();
                const target = document.querySelector(this.getAttribute('href'));
                if (target) {
                    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
                }
            });
        });

        // Intersection Observer para animaciones al scroll
        const observerOptions = {
            threshold: 0.1,
            rootMargin: '0px 0px -100px 0px'
        };

        const observer = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    entry.target.style.opacity = '1';
                    entry.target.style.transform = 'translateY(0)';
                }
            });
        }, observerOptions);

        document.querySelectorAll('section').forEach(section => {
            section.style.opacity = '0';
            section.style.transform = 'translateY(50px)';
            section.style.transition = 'opacity 0.6s ease, transform 0.6s ease';
            observer.observe(section);
        });

        // Preloader para imágenes
        const images = document.querySelectorAll('img');
        images.forEach(img => {
            img.addEventListener('load', function() {
                this.style.opacity = '1';
            });
            img.style.opacity = '0';
            img.style.transition = 'opacity 0.5s ease';
        });

        // Detectar si el usuario está inactivo
        let inactivityTimer;
        function resetInactivityTimer() {
            clearTimeout(inactivityTimer);
            inactivityTimer = setTimeout(() => {
                console.log('👀 Usuario inactivo. ¿Todo bien?');
            }, 60000); // 1 minuto
        }

        ['mousedown', 'mousemove', 'keypress', 'scroll', 'touchstart'].forEach(event => {
            document.addEventListener(event, resetInactivityTimer, true);
        });

        resetInactivityTimer();

        // Notificación de bienvenida
        setTimeout(() => {
            if (Notification.permission === 'default') {
                Notification.requestPermission().then(permission => {
                    if (permission === 'granted') {
                        new Notification('¡Bienvenido a CodeHub! 🚀', {
                            body: 'Explora mi portfolio y descubre el menú experimental',
                            icon: 'https://i.postimg.cc/wBvCwpgP/LMC-30-Mar-18-300.jpg'
                        });
                    }
                });
            }
        }, 5000);

        // Performance monitoring
        window.addEventListener('load', () => {
            const perfData = performance.getEntriesByType('navigation')[0];
            console.log(`⚡ Página cargada en ${perfData.loadEventEnd - perfData.fetchStart}ms`);
        });

        // Dark mode automático según hora del día
        function autoTheme() {
            const hour = new Date().getHours();
            if (hour >= 20 || hour < 6) {
                if (!document.body.classList.contains('light-mode')) {
                    // Ya está en modo oscuro
                    console.log('🌙 Modo nocturno activo');
                }
            }
        }

        autoTheme();

        // Efecto parallax suave
        window.addEventListener('scroll', () => {
            const scrolled = window.pageYOffset;
            const parallaxElements = document.querySelectorAll('.skill-icon, .service-icon');
            
            parallaxElements.forEach((el, index) => {
                const speed = 0.5 + (index * 0.1);
                el.style.transform = `translateY(${scrolled * speed * 0.01}px)`;
            });
        });

        // Mensaje de despedida al salir
        window.addEventListener('beforeunload', (e) => {
            e.preventDefault();
            e.returnValue = '¿Seguro que quieres salir? 🥺';
        });

        // Log de actividad del usuario
        let activityLog = {
            scrolls: 0,
            clicks: 0,
            timeSpent: 0
        };

        window.addEventListener('scroll', () => {
            activityLog.scrolls++;
        });

        document.addEventListener('click', () => {
            activityLog.clicks++;
        });

        setInterval(() => {
            activityLog.timeSpent++;
        }, 1000);

        // Mostrar estadísticas al presionar Ctrl+Shift+S
        document.addEventListener('keydown', (e) => {
            if (e.ctrlKey && e.shiftKey && e.key === 'S') {
                e.preventDefault();
                console.table(activityLog);
                alert(`📊 Estadísticas de tu visita:\n\n` +
                      `Scrolls: ${activityLog.scrolls}\n` +
                      `Clicks: ${activityLog.clicks}\n` +
                      `Tiempo: ${Math.floor(activityLog.timeSpent / 60)}m ${activityLog.timeSpent % 60}s`);
            }
        });

        // Easter egg: escribir "dev" en cualquier parte
        let typedText = '';
        document.addEventListener('keypress', (e) => {
            typedText += e.key;
            typedText = typedText.slice(-3);
            
            if (typedText === 'dev') {
                console.log('%c🎮 MODO DESARROLLADOR ACTIVADO', 'color: lime; font-size: 20px; font-weight: bold;');
                console.log('%cComandos disponibles:', 'color: cyan; font-size: 14px;');
                console.log('• Ctrl+K: Búsqueda rápida');
                console.log('• Alt+T: Cambiar tema');
                console.log('• Ctrl+Shift+S: Ver estadísticas');
                console.log('• Esc: Cerrar menús');
                console.log('• Código Konami: ↑↑↓↓←→←→BA');
            }
        });

        // Añadir efecto de ripple a los botones
        document.querySelectorAll('button, .menu-item, .command-item').forEach(button => {
            button.addEventListener('click', function(e) {
                const ripple = document.createElement('span');
                const rect = this.getBoundingClientRect();
                const size = Math.max(rect.width, rect.height);
                const x = e.clientX - rect.left - size / 2;
                const y = e.clientY - rect.top - size / 2;
                
                ripple.style.cssText = `
                    position: absolute;
                    width: ${size}px;
                    height: ${size}px;
                    border-radius: 50%;
                    background: rgba(255, 255, 255, 0.5);
                    left: ${x}px;
                    top: ${y}px;
                    pointer-events: none;
                    animation: rippleEffect 0.6s ease-out;
                `;
                
                this.style.position = 'relative';
                this.style.overflow = 'hidden';
                this.appendChild(ripple);
                
                setTimeout(() => ripple.remove(), 600);
            });
        });

        const rippleStyle = document.createElement('style');
        rippleStyle.textContent = `
            @keyframes rippleEffect {
                0% { transform: scale(0); opacity: 1; }
                100% { transform: scale(4); opacity: 0; }
            }
        `;
        document.head.appendChild(rippleStyle);

        // Finalización de carga
        console.log('%c✨ CodeHub Portfolio Cargado Completamente', 'color: #ff4500; font-size: 16px; font-weight: bold;');
        console.log('%c🧪 Menú Experimental disponible en el lado izquierdo', 'color: #ffbd69; font-size: 14px;');
        console.log('%c📝 Desarrollado por Wilson.E - 2024', 'color: #ff6b35; font-size: 12px;');
