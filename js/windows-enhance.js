/* ═══════════════════════════════════════════════════════════════
   WINDOWS ENHANCE — drawer de recuperación y optimización de Windows
   CodeHub by Wilson.E

   Ventana deslizante (lateral en desktop / bottom-sheet en móvil)
   con herramientas curadas, enlaces directos y guías de uso paso a
   paso. Todo es estático/local: no consume API y no toca el catálogo
   de apps Android (/api/apps).
   ═══════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var drawer = document.getElementById('we-drawer');
  var backdrop = document.getElementById('we-backdrop');
  var trigger = document.getElementById('we-trigger');
  if (!drawer || !backdrop || !trigger) return;

  /* ── DATOS ─────────────────────────────────────────────────── */
  // links: [{label, url}] — url directa (web oficial o GitHub).
  // guide: pasos de uso. badge: 'os' (open source) o 'gratis'.
  var WE = {
    recuperacion: {
      intro: 'Windows no arranca o falla a medio camino. Estas herramientas crean USB de arranque y reparan el sistema; los comandos son el plan B directo desde la consola.',
      tools: [
        {
          icon: 'fa-solid fa-compact-disc',
          name: 'Rufus',
          badge: 'os',
          desc: 'Crea un USB de arranque desde cualquier ISO en tres clics. Detecta si tu equipo es UEFI o BIOS y prepara el USB con la configuración correcta.',
          links: [
            { label: 'Sitio oficial', url: 'https://rufus.ie' },
            { label: 'GitHub', url: 'https://github.com/pbatard/rufus' }
          ],
          guide: [
            'Descarga la ISO oficial de Windows 10/11 desde Microsoft (o usa MediaCreationTool.bat, más abajo).',
            'Conecta un USB de 8 GB o más: Rufus lo formateará y borrará todo su contenido.',
            'Abre Rufus y en <strong>Dispositivo</strong> selecciona tu USB.',
            'Pulsa <strong>Seleccionar</strong> y elige la ISO descargada.',
            'Deja <strong>Tipo de partición</strong> en GPT (UEFI) salvo que tu PC sea antiguo (BIOS/MBR).',
            'Pulsa <strong>Empezar</strong>. Si pregunta, elige <code>Escribir en modo imagen ISO</code>.',
            'Arranca el equipo desde el USB (menú de arranque: F12 / Esc / F9 según marca).'
          ]
        },
        {
          icon: 'fa-solid fa-layer-group',
          name: 'Ventoy',
          badge: 'os',
          desc: 'Convierte un USB en un arranque múltiple: pegas varias ISOs y eliges cuál arrancar. Ideal para tener Windows + Linux + herramientas de rescate en un solo pendrive.',
          links: [
            { label: 'Sitio oficial', url: 'https://www.ventoy.net' },
            { label: 'GitHub', url: 'https://github.com/ventoy/Ventoy' }
          ],
          guide: [
            'Descarga Ventoy y ejecuta <code>Ventoy2Disk.exe</code>.',
            'Selecciona el USB y pulsa <strong>Instalar</strong> (borra el USB).',
            'El USB queda con dos particiones: copia tus ISOs a la partición grande (formato NTFS/exFAT).',
            'Arranca desde el USB y verás un menú con todas las ISOs disponibles para elegir.'
          ]
        },
        {
          icon: 'fa-solid fa-download',
          name: 'MediaCreationTool.bat',
          badge: 'os',
          desc: 'Descarga la ISO oficial de Windows 10/11 sin el asistente de Microsoft lleno de promociones. Incluye el bypass de requisitos TPM/RAM para 11 en equipos no soportados.',
          links: [
            { label: 'GitHub', url: 'https://github.com/AveYo/MediaCreationTool.bat' }
          ],
          guide: [
            'Descarga el ZIP del release y extráelo.',
            'Ejecuta <code>MediaCreationTool.bat</code> como administrador.',
            'Elige la edición y versión de Windows que necesitas (10, 11, Server).',
            'Se descarga la ISO oficial; úsala después con Rufus o Ventoy.'
          ]
        },
        {
          icon: 'fa-solid fa-kit-medical',
          name: "Hiren's BootCD PE",
          badge: 'gratis',
          desc: 'Kit de rescate completo en un USB/CD de arranque: recupera archivos, arregla particiones, repara el arranque o extrae datos de un equipo que ya no inicia.',
          links: [
            { label: 'Sitio oficial', url: 'https://www.hirensbootcd.org/' }
          ],
          guide: [
            'Descarga la ISO desde el sitio oficial (pesa ~2 GB).',
            'Grábala en un USB con Rufus o Ventoy y arranca el equipo desde ahí.',
            'Entra en el entorno <strong>Mini Windows PE</strong> (tardará un par de minutos).',
            'Ahí tienes Minitool Partition Wizard, herramientas de datos y edición de registro para recuperar el sistema.'
          ]
        }
      ],
      commands: {
        title: 'Comandos de reparación',
        note: 'Abre el Símbolo del sistema (CMD) como administrador y ejecuta en orden. Espera a que cada uno termine antes del siguiente.',
        items: [
          { cmd: 'sfc /scannow', desc: 'Comprueba y repara archivos de sistema dañados.' },
          { cmd: 'DISM /Online /Cleanup-Image /RestoreHealth', desc: 'Repara la imagen de Windows. Debe correr antes que sfc si el sistema está muy dañado.' },
          { cmd: 'chkdsk C: /f /r', desc: 'Busca y corrige errores del disco. Pedirá reiniciar el equipo.' },
          { cmd: 'bootrec /fixmbr', desc: 'Reconstruye el arranque: /fixmbr, luego /fixboot y /rebuildbcd.' }
        ]
      },
      steps: {
        title: 'Si Windows no inicia',
        items: [
          'Arranca desde el USB de instalación (Rufus/Ventoy) y elige <strong>Reparar el equipo</strong> → <strong>Reparación de inicio</strong>.',
          'Si no funciona, abre CMD desde el USB (Mayús+F10) y ejecuta <code>bootrec /rebuildbcd</code>.',
          'Restaura un <strong>punto de restauración</strong> anterior si el equipo arrancó bien antes.',
          'Como último recurso, <strong>Restablecer este PC</strong> conservando tus archivos.'
        ]
      }
    },
    optimizacion: {
      intro: 'Windows se siente lento o lleno de bloatware. Estas herramientas lo limpian, quitan lo que no pediste y liberan espacio — todas con enlaces directos y su guía de uso.',
      tools: [
        {
          icon: 'fa-solid fa-wand-magic-sparkles',
          name: 'Chris Titus Windows Utility',
          badge: 'os',
          desc: 'Todo-en-uno para configurar Windows 10/11 con un solo script: quita bloatware, ajusta privacidad y rendimiento, y ejecuta mantenimiento.',
          links: [
            { label: 'Sitio oficial', url: 'https://christitus.com/windows-tool/' },
            { label: 'GitHub', url: 'https://github.com/ChrisTitusTech/winutil' }
          ],
          guide: [
            'Abre PowerShell como administrador.',
            'Ejecuta: <code>irm https://christitus.com/win | iex</code>',
            'Ve a <strong>Tweaks</strong> → selecciona las cajas de privacidad/rendimiento que quieras.',
            'Pulsa <strong>Run Tweaks</strong> y reinicia. Hay presets recomendados si no quieres elegir manualmente.'
          ]
        },
        {
          icon: 'fa-solid fa-trash-can',
          name: 'W11Debloat',
          badge: 'os',
          desc: 'Quita el bloatware, la publicidad y el ruido de Windows 11 de forma sencilla, con opciones visuales y reversibles.',
          links: [
            { label: 'GitHub', url: 'https://github.com/builtbybel/W11Debloat' }
          ],
          guide: [
            'Descarga el ejecutable del release y ejecútalo como administrador.',
            'Marca las acciones que quieres (desinstalar apps, quitar OneDrive, silenciar notificaciones).',
            'Pulsa <strong>Apply</strong> y espera a que termine. Muchos cambios se pueden revertir desde la misma app.'
          ]
        },
        {
          icon: 'fa-solid fa-gamepad',
          name: 'Atlas OS',
          badge: 'os',
          desc: 'Versión modificada y optimizada de Windows para juegos y rendimiento. Quita todo lo que consume recursos en segundo plano.',
          links: [
            { label: 'Sitio oficial', url: 'https://atlasos.net' },
            { label: 'GitHub', url: 'https://github.com/Atlas-OS/Atlas' }
          ],
          guide: [
            'Requiere una instalación de Windows 10/11 ya funcionando: es un script de optimización, no una ISO por defecto.',
            'Descarga Atlas (Amano) y sigue la guía oficial del sitio para generar tu ISO optimizada.',
            'Es avanzado: haz una copia de seguridad antes. Ideal si tienes experiencia reinstalando sistemas.',
            'Para uso diario sin complicaciones, Chris Titus WinUtil cubre el 90% de lo mismo.'
          ]
        },
        {
          icon: 'fa-solid fa-broom',
          name: 'BCUninstaller',
          badge: 'os',
          desc: 'Desinstalador masivo que encuentra apps ocultas y restos que el Panel de control no muestra, y los borra de raíz.',
          links: [
            { label: 'GitHub', url: 'https://github.com/Klocman/Bulk-Crap-Uninstaller' },
            { label: 'Descarga directa', url: 'https://www.bcuninstaller.com/' }
          ],
          guide: [
            'Descarga la versión portable (no requiere instalación).',
            'Abre y pulsa <strong>Escáner</strong> para listar todo lo instalado, incluidas apps ocultas.',
            'Filtra por estado (escondida, inválida) y selecciona varias a la vez.',
            'Usa <strong>Advanced Uninstall</strong> para borrar también los restos de registro y carpetas.'
          ]
        },
        {
          icon: 'fa-solid fa-broom',
          name: 'BleachBit',
          badge: 'os',
          desc: 'Limpia cachés, temporales y restos de cientos de programas para liberar espacio sin borrar tus datos personales.',
          links: [
            { label: 'Sitio oficial', url: 'https://www.bleachbit.org' },
            { label: 'GitHub', url: 'https://github.com/bleachbit/bleachbit' }
          ],
          guide: [
            'Instala BleachBit y ábrelo.',
            'Marca las secciones que quieres limpiar (navegador, cachés, temporales).',
            'Pulsa <strong>Vista previa</strong> para ver qué se borrará, luego <strong>Limpiar</strong>.'
          ]
        },
        {
          icon: 'fa-solid fa-chart-pie',
          name: 'WinDirStat',
          badge: 'os',
          desc: 'Mapa visual del disco: te muestra en un gráfico de colores qué archivos y carpetas se están comiendo el espacio.',
          links: [
            { label: 'Sitio oficial', url: 'https://windirstat.net' },
            { label: 'GitHub', url: 'https://github.com/windirstat/windirstat' }
          ],
          guide: [
            'Abre WinDirStat y selecciona la unidad que quieres analizar (C: normalmente).',
            'Espera al escaneo: cada archivo es un rectángulo proporcional a su tamaño.',
            'Clic en un bloque grande para localizar y borrar el archivo que ocupa de más.'
          ]
        },
        {
          icon: 'fa-solid fa-window-restore',
          name: 'Open-Shell',
          badge: 'os',
          desc: 'Devuelve el menú Inicio clásico y añade mejoras a la barra de tareas. Perfecto si odias el menú de Windows 11.',
          links: [
            { label: 'GitHub', url: 'https://github.com/Open-Shell/Open-Shell-Menu' }
          ],
          guide: [
            'Descarga el instalador del release y ejecútalo.',
            'Elige el estilo de menú (Clásico o estilo Windows 7) y personaliza.',
            'Si quieres desinstalarlo, abre Open-Shell y usa su opción de desinstalación propia.'
          ]
        },
        {
          icon: 'fa-solid fa-box-open',
          name: 'PowerToys',
          badge: 'os',
          desc: 'Utilidades oficiales de Microsoft para usuarios avanzados: FancyZones, PowerRename, AlwaysOnTop, resaltado de teclas y más.',
          links: [
            { label: 'Sitio oficial', url: 'https://learn.microsoft.com/es-es/windows/powertoys/' },
            { label: 'GitHub', url: 'https://github.com/microsoft/PowerToys' }
          ],
          guide: [
            'Instala PowerToys desde Microsoft Store o GitHub.',
            'Activa FancyZones para organizar ventanas con regiones personalizadas.',
            'Usa PowerRename para renombrar archivos en lote y AlwaysOnTop para fijar ventanas encima de otras.'
          ]
        }
      ],
      tweaks: {
        title: 'Ajustes rápidos',
        items: [
          'Desactiva apps de inicio: <code>Ctrl+Mayús+Esc</code> → pestaña <strong>Inicio</strong>.',
          'Libera espacio: <strong>Configuración → Sistema → Almacenamiento</strong> y ejecuta Limpieza; o escribe <code>cleanmgr</code> en Inicio.',
          'Plan de energía en <strong>Alto rendimiento</strong> para equipos de escritorio.',
          'Optimiza el disco: escribe <code>dfrgui</code> (HDD desfragmenta, SSD ejecuta TRIM).'
        ]
      }
    }
  };

  /* ── ESCAPE HTML ───────────────────────────────────────────── */
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  /* ── RENDER ────────────────────────────────────────────────── */
  function renderCommands(cmds) {
    return cmds.items.map(function (c) {
      return '<div class="we-cmd">' +
        '<code>' + esc(c.cmd) + '</code>' +
        '<button type="button" class="we-copy" data-copy="' + esc(c.cmd) + '" aria-label="Copiar comando" title="Copiar"><i class="fa-regular fa-copy"></i></button>' +
        '</div>' +
        '<p class="we-block-note" style="margin-top:-.2rem">' + esc(c.desc) + '</p>';
    }).join('');
  }

  function renderSteps(steps) {
    return '<ol>' + steps.items.map(function (s) {
      return '<li>' + s + '</li>';
    }).join('') + '</ol>';
  }

  function renderTool(t) {
    var links = t.links.map(function (l, i) {
      return '<a class="we-tool-link' + (i === 0 ? ' primary' : '') + '" href="' + esc(l.url) + '" target="_blank" rel="noopener">' +
        '<i class="fa-solid fa-arrow-up-right-from-square"></i> ' + esc(l.label) + '</a>';
    }).join('');
    var badge = t.badge === 'os'
      ? '<span class="we-tool-badge os">Open Source</span>'
      : '<span class="we-tool-badge gratis">Gratis</span>';
    return '<div class="we-tool">' +
      '<div class="we-tool-top">' +
        '<div class="we-tool-icon"><i class="' + t.icon + '"></i></div>' +
        '<div style="min-width:0;flex:1">' +
          '<div class="we-tool-name">' + esc(t.name) + '</div>' +
          '<div class="we-tool-badges">' + badge + '</div>' +
        '</div>' +
      '</div>' +
      '<p class="we-tool-desc">' + esc(t.desc) + '</p>' +
      '<div class="we-tool-links">' + links + '</div>' +
      '<button type="button" class="we-guide-toggle" aria-expanded="false">' +
        '<i class="fa-solid fa-book-open"></i> Ver guía de uso</button>' +
      '<div class="we-guide">' +
        '<ol>' + t.guide.map(function (g) { return '<li>' + g + '</li>'; }).join('') + '</ol>' +
      '</div>' +
    '</div>';
  }

  function renderPanel(sec, data) {
    var html = '<p class="we-block-note">' + esc(data.intro) + '</p>';
    if (data.commands) {
      html += '<div class="we-block-title"><i class="fa-solid fa-terminal"></i> ' + esc(data.commands.title) + '</div>' +
        '<p class="we-block-note">' + esc(data.commands.note) + '</p>' + renderCommands(data.commands);
    }
    if (data.steps) {
      html += '<div class="we-block-title"><i class="fa-solid fa-triangle-exclamation"></i> ' + esc(data.steps.title) + '</div>' +
        renderSteps(data.steps);
    }
    html += '<div class="we-block-title"><i class="fa-solid fa-screwdriver-wrench"></i> Herramientas</div>';
    html += data.tools.map(renderTool).join('');
    if (data.tweaks) {
      html += '<div class="we-block-title"><i class="fa-solid fa-gauge-high"></i> ' + esc(data.tweaks.title) + '</div>' +
        renderSteps(data.tweaks);
    }
    sec.innerHTML = html;
  }

  renderPanel(document.getElementById('we-panel-recuperacion'), WE.recuperacion);
  renderPanel(document.getElementById('we-panel-optimizacion'), WE.optimizacion);

  /* ── ABRIR / CERRAR ────────────────────────────────────────── */
  function openDrawer() {
    drawer.classList.add('open');
    backdrop.classList.add('show');
    drawer.removeAttribute('inert');
    drawer.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';
    trigger.setAttribute('aria-expanded', 'true');
    var closeBtn = document.getElementById('we-close');
    if (closeBtn) closeBtn.focus();
  }

  function closeDrawer() {
    drawer.classList.remove('open');
    backdrop.classList.remove('show');
    drawer.setAttribute('inert', '');
    drawer.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
    trigger.setAttribute('aria-expanded', 'false');
    trigger.focus();
  }

  trigger.addEventListener('click', openDrawer);
  backdrop.addEventListener('click', closeDrawer);
  document.getElementById('we-close').addEventListener('click', closeDrawer);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && drawer.classList.contains('open')) closeDrawer();
  });

  // Evita deslizar la página de fondo mientras el bottom-sheet está abierto.
  drawer.addEventListener('touchmove', function (e) {
    var body = drawer.querySelector('.we-drawer-body');
    if (body && !body.contains(e.target)) e.preventDefault();
  }, { passive: false });

  /* ── TABS ──────────────────────────────────────────────────── */
  var tabs = document.querySelectorAll('.we-tab');
  tabs.forEach(function (tab) {
    tab.addEventListener('click', function () {
      var name = tab.dataset.tab;
      tabs.forEach(function (t) {
        t.classList.toggle('on', t === tab);
        t.setAttribute('aria-selected', t === tab ? 'true' : 'false');
      });
      document.querySelectorAll('.we-panel').forEach(function (p) {
        p.classList.toggle('on', p.dataset.panel === name);
      });
    });
  });

  /* ── DELEGACIÓN: copiar y guías ────────────────────────────── */
  function copyText(txt, btn) {
    var ok = function () {
      btn.classList.add('ok');
      var i = btn.querySelector('i');
      if (i) i.className = 'fa-solid fa-check';
      setTimeout(function () {
        btn.classList.remove('ok');
        if (i) i.className = 'fa-regular fa-copy';
      }, 1600);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(txt).then(ok).catch(function () { fallbackCopy(txt, ok); });
    } else {
      fallbackCopy(txt, ok);
    }
  }

  function fallbackCopy(txt, ok) {
    try {
      var ta = document.createElement('textarea');
      ta.value = txt;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      ok();
    } catch (e) { /* sin soporte de copia */ }
  }

  drawer.addEventListener('click', function (e) {
    var copyBtn = e.target.closest('.we-copy');
    if (copyBtn) {
      copyText(copyBtn.dataset.copy, copyBtn);
      return;
    }
    var toggle = e.target.closest('.we-guide-toggle');
    if (toggle) {
      var guide = toggle.parentElement.querySelector('.we-guide');
      var isOpen = guide && guide.classList.contains('open');
      if (guide) guide.classList.toggle('open', !isOpen);
      toggle.setAttribute('aria-expanded', String(!isOpen));
      var i = toggle.querySelector('i');
      if (i) i.className = isOpen ? 'fa-solid fa-book-open' : 'fa-solid fa-book-open-reader';
      toggle.childNodes[1].nodeValue = isOpen ? ' Ver guía de uso' : ' Ocultar guía';
      return;
    }
  });

  // Estado inicial: drawer cerrado y bloqueado al foco.
  drawer.setAttribute('inert', '');
  drawer.setAttribute('aria-hidden', 'true');
})();
