/* ═══════════════════════════════════════════════════════════
   EMI VOICE — Web Speech API Module
   CodeHub by Wilson.E
   
   INTEGRACIÓN:
   1. Copiar este archivo a /js/emi-voice.js
   2. Agregar en index.html ANTES del </body>:
      <script src="js/emi-voice.js" defer></script>
   3. Agregar el botón de micrófono en el ai-input-row (ver instrucciones abajo)
   4. Agregar el CSS de este archivo al bloque <style> de index.html
   
   BOTÓN A INSERTAR en index.html — dentro de .ai-input-row,
   entre el <textarea> y el <button id="ai-send-btn">:
   
   <button id="emi-mic-btn" type="button" aria-label="Hablar con EMI" title="Hablar con EMI (voz a texto)" onclick="emiVoice.toggle()">
     <i class="fas fa-microphone"></i>
   </button>
   
   CSS A AGREGAR al bloque <style> de index.html:
   (busca la sección de .ai-send-btn y agrega debajo)
   
   #emi-mic-btn { ... }   ← ver sección CSS al final de este archivo
═══════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  /* ── Detección de soporte ── */
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  const SS = window.speechSynthesis;
  const HAS_RECOGNITION = !!SR;
  const HAS_SYNTHESIS   = !!SS;

  /* ── Estado global del módulo ── */
  const state = {
    listening    : false,
    speaking     : false,
    recognition  : null,
    lastTranscript: '',
    speakQueue   : [],
    lang         : localStorage.getItem('ch_lang') === 'en' ? 'en-US' : 'es-GT',
    autoSpeak    : JSON.parse(localStorage.getItem('emi_auto_speak') ?? 'false'),
  };

  /* ── Referencias DOM (se resuelven al init) ── */
  let micBtn, inputEl, statusEl, micRipple;

  /* ════════════════════════════════════════════
     INICIALIZACIÓN
  ════════════════════════════════════════════ */
  function init() {
    micBtn   = document.getElementById('emi-mic-btn');
    inputEl  = document.getElementById('ai-input');
    statusEl = document.getElementById('ai-status');

    if (!micBtn) return; // el botón aún no está en el DOM

    /* Crear el ripple visual de escucha */
    micRipple = document.createElement('span');
    micRipple.className = 'emi-mic-ripple';
    micBtn.appendChild(micRipple);

    /* Mostrar tooltip si no hay soporte */
    if (!HAS_RECOGNITION) {
      micBtn.title = 'Tu navegador no soporta voz. Prueba Chrome o Edge.';
      micBtn.classList.add('emi-mic-unsupported');
      micBtn.onclick = () => showVoiceToast('⚠️ Tu navegador no soporta reconocimiento de voz. Usa Chrome o Edge.', 'warn');
      return;
    }

    /* Configurar SpeechRecognition */
    state.recognition = new SR();
    const rec = state.recognition;
    rec.continuous     = false;
    rec.interimResults = true;
    rec.maxAlternatives= 1;
    rec.lang           = state.lang;

    rec.onstart = () => {
      state.listening = true;
      micBtn.classList.add('emi-mic-active');
      if (statusEl) statusEl.textContent = '🎙️ Escuchando…';
      if (inputEl)  inputEl.placeholder  = 'Habla ahora…';
    };

    rec.onresult = (e) => {
      let interim = '';
      let final   = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const t = e.results[i][0].transcript;
        if (e.results[i].isFinal) final += t;
        else interim += t;
      }
      /* Mostrar resultado interim en placeholder, final en el input */
      if (inputEl) {
        if (interim) inputEl.placeholder = interim;
        if (final) {
          const current = inputEl.value.trim();
          inputEl.value = current ? current + ' ' + final : final;
          inputEl.style.height = '48px';
          inputEl.style.height = Math.min(inputEl.scrollHeight, 140) + 'px';
          state.lastTranscript = final;
          /* Actualizar contador de chars si existe */
          if (typeof updateCharCount === 'function') updateCharCount();
        }
      }
    };

    rec.onerror = (e) => {
      stopListening();
      const msgs = {
        'no-speech'          : '🤫 No te escuché. Intenta de nuevo.',
        'audio-capture'      : '🎤 Sin acceso al micrófono.',
        'not-allowed'        : '🔒 Permiso de micrófono denegado. Actívalo en la barra del navegador.',
        'network'            : '🌐 Error de red al procesar voz.',
        'aborted'            : null,
        'service-not-allowed': '🔒 El servicio de voz no está disponible.',
      };
      const msg = msgs[e.error];
      if (msg) showVoiceToast(msg, 'error');
    };

    rec.onend = () => {
      stopListening();
    };

    /* ── Botón de auto-speak ── */
    injectAutoSpeakToggle();

    /* ── Exponer en window para uso externo ── */
    window.emiVoice = publicAPI;

    /* ── Hook: interceptar respuestas de EMI para auto-speak ── */
    hookEmiResponses();

    /* Sincronizar idioma cuando cambia */
    document.addEventListener('ch:langchange', (e) => {
      state.lang = e.detail === 'en' ? 'en-US' : 'es-GT';
      if (state.recognition) state.recognition.lang = state.lang;
    });
  }

  /* ════════════════════════════════════════════
     CONTROL DE ESCUCHA
  ════════════════════════════════════════════ */
  function startListening() {
    if (!HAS_RECOGNITION || state.listening) return;
    /* Parar síntesis si estaba hablando */
    if (state.speaking) stopSpeaking();
    try {
      state.recognition.lang = state.lang;
      state.recognition.start();
    } catch (err) {
      showVoiceToast('Error al iniciar micrófono: ' + err.message, 'error');
    }
  }

  function stopListening() {
    state.listening = false;
    if (micBtn) micBtn.classList.remove('emi-mic-active');
    if (statusEl) statusEl.textContent = 'Online · Listo para ayudarte';
    if (inputEl)  inputEl.placeholder  = 'Pregúntame algo, genera una imagen, depura código…';
  }

  function toggle() {
    if (state.listening) {
      state.recognition.stop();
    } else {
      startListening();
    }
  }

  /* ════════════════════════════════════════════
     SÍNTESIS DE VOZ (EMI habla)
  ════════════════════════════════════════════ */
  function speak(text) {
    if (!HAS_SYNTHESIS || !text) return;
    /* Limpiar markdown/HTML básico del texto */
    const clean = text
      .replace(/<[^>]+>/g, '')           // quitar HTML
      .replace(/```[\s\S]*?```/g, 'bloque de código') // code blocks
      .replace(/`([^`]+)`/g, '$1')       // inline code
      .replace(/\*\*([^*]+)\*\*/g, '$1') // bold
      .replace(/\*([^*]+)\*/g, '$1')     // italic
      .replace(/#{1,6}\s/g, '')          // headings
      .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1') // links
      .trim();

    if (!clean) return;

    stopSpeaking();
    const utt = new SpeechSynthesisUtterance(clean);
    utt.lang  = state.lang;
    utt.rate  = 1.05;
    utt.pitch = 1.1;

    /* Elegir voz en español si disponible */
    const voices = SS.getVoices();
    const langCode = state.lang.split('-')[0];
    const preferred = voices.find(v =>
      v.lang.startsWith(langCode) && (v.name.includes('Google') || v.name.includes('Microsoft') || v.localService)
    ) || voices.find(v => v.lang.startsWith(langCode));
    if (preferred) utt.voice = preferred;

    utt.onstart = () => {
      state.speaking = true;
      if (micBtn) micBtn.title = 'EMI está hablando… (clic para interrumpir)';
    };
    utt.onend = utt.onerror = () => {
      state.speaking = false;
      if (micBtn) micBtn.title = 'Hablar con EMI (voz a texto)';
    };

    SS.speak(utt);
  }

  function stopSpeaking() {
    if (HAS_SYNTHESIS) SS.cancel();
    state.speaking = false;
  }

  /* ════════════════════════════════════════════
     HOOK: capturar respuestas de EMI para auto-speak
  ════════════════════════════════════════════ */
  function hookEmiResponses() {
    /* Observar mutaciones en #ai-msgs para detectar nuevos mensajes de la IA */
    const msgs = document.getElementById('ai-msgs');
    if (!msgs) return;

    const observer = new MutationObserver((mutations) => {
      if (!state.autoSpeak) return;
      for (const mut of mutations) {
        for (const node of mut.addedNodes) {
          if (node.nodeType !== 1) continue;
          /* Mensaje de la IA: tiene clase .ai-msg-wrap.ai pero NO .user */
          const wrap = node.classList?.contains('ai-msg-wrap') && node.classList?.contains('ai') && !node.classList?.contains('user')
            ? node
            : node.querySelector?.('.ai-msg-wrap.ai:not(.user)');
          if (wrap) {
            const msgEl = wrap.querySelector('.ai-msg');
            if (msgEl) {
              /* Esperar a que el texto esté completo (streaming puede tardar) */
              setTimeout(() => speak(msgEl.innerText || msgEl.textContent), 400);
            }
          }
        }
      }
    });
    observer.observe(msgs, { childList: true, subtree: false });
  }

  /* ════════════════════════════════════════════
     TOGGLE AUTO-SPEAK
  ════════════════════════════════════════════ */
  function injectAutoSpeakToggle() {
    const bottomBar = document.querySelector('.ai-bottom-bar');
    if (!bottomBar || document.getElementById('emi-speak-toggle')) return;

    const btn = document.createElement('button');
    btn.className   = 'ai-circle-btn';
    btn.id          = 'emi-speak-toggle';
    btn.title       = 'EMI te responde en voz alta';
    btn.setAttribute('aria-label', 'Toggle respuesta por voz');
    btn.innerHTML   = `
      <div class="ai-circle-icon"><i class="fas fa-volume-${state.autoSpeak ? 'high' : 'xmark'}"></i></div>
      <span class="ai-circle-label" data-subtitle="Respuesta por voz">Voz EMI</span>
    `;
    btn.onclick = () => {
      state.autoSpeak = !state.autoSpeak;
      localStorage.setItem('emi_auto_speak', JSON.stringify(state.autoSpeak));
      btn.querySelector('i').className = `fas fa-volume-${state.autoSpeak ? 'high' : 'xmark'}`;
      showVoiceToast(state.autoSpeak ? '🔊 EMI responderá en voz alta' : '🔇 Voz de EMI desactivada', 'info');
    };
    /* Insertar como primer elemento */
    bottomBar.insertBefore(btn, bottomBar.firstChild);
  }

  /* ════════════════════════════════════════════
     TOAST DE NOTIFICACIÓN
  ════════════════════════════════════════════ */
  function showVoiceToast(msg, type = 'info') {
    /* Reusar el toast existente de CodeHub si existe */
    const existing = document.getElementById('toast');
    if (existing) {
      existing.textContent = msg;
      existing.className   = 'show ' + type;
      clearTimeout(existing._t);
      existing._t = setTimeout(() => existing.className = '', 3000);
      return;
    }
    /* Fallback: toast propio */
    let t = document.getElementById('emi-voice-toast');
    if (!t) {
      t = document.createElement('div');
      t.id = 'emi-voice-toast';
      document.body.appendChild(t);
    }
    t.textContent = msg;
    t.className   = 'emi-voice-toast show ' + type;
    clearTimeout(t._t);
    t._t = setTimeout(() => { t.className = 'emi-voice-toast'; }, 3000);
  }

  /* ════════════════════════════════════════════
     API PÚBLICA
  ════════════════════════════════════════════ */
  const publicAPI = {
    toggle,
    startListening,
    stopListening,
    speak,
    stopSpeaking,
    get isListening() { return state.listening; },
    get isSpeaking()  { return state.speaking; },
    get autoSpeak()   { return state.autoSpeak; },
    setLang(lang) {
      state.lang = lang;
      if (state.recognition) state.recognition.lang = lang;
    },
  };

  /* ── Inicializar cuando el DOM esté listo ── */
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

  /* ── Re-intentar init si el panel se abre después ── */
  document.addEventListener('click', (e) => {
    if (e.target.closest('#ai-fab') && !window.emiVoice) {
      setTimeout(init, 300);
    }
  });

})();


/* ═══════════════════════════════════════════════════════════
   CSS — PEGAR EN EL BLOQUE <style> DE index.html
   Busca: #ai-send-btn y agrega debajo de ese bloque
═══════════════════════════════════════════════════════════

#emi-mic-btn {
  flex-shrink: 0;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  border: 1px solid rgba(0, 229, 255, .25);
  background: rgba(0, 229, 255, .08);
  color: var(--c, #00e5ff);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: .9rem;
  transition: background .2s, border-color .2s, transform .15s, box-shadow .2s;
  position: relative;
  overflow: hidden;
}
#emi-mic-btn:hover {
  background: rgba(0, 229, 255, .16);
  border-color: rgba(0, 229, 255, .5);
  transform: scale(1.06);
}
#emi-mic-btn.emi-mic-active {
  background: rgba(255, 69, 0, .18);
  border-color: rgba(255, 69, 0, .7);
  color: #ff4500;
  box-shadow: 0 0 16px rgba(255, 69, 0, .35);
  animation: emi-mic-pulse 1.4s ease-in-out infinite;
}
#emi-mic-btn.emi-mic-unsupported {
  opacity: .38;
  cursor: not-allowed;
}
@keyframes emi-mic-pulse {
  0%, 100% { box-shadow: 0 0 10px rgba(255, 69, 0, .3); }
  50%       { box-shadow: 0 0 22px rgba(255, 69, 0, .65); }
}
.emi-mic-ripple {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255,69,0,.3) 0%, transparent 70%);
  opacity: 0;
  transform: scale(0);
  transition: none;
}
#emi-mic-btn.emi-mic-active .emi-mic-ripple {
  animation: emi-ripple-expand 1.4s ease-out infinite;
}
@keyframes emi-ripple-expand {
  0%   { opacity: .5; transform: scale(0); }
  100% { opacity: 0;  transform: scale(2); }
}

// Toast fallback (solo si no existe #toast en CodeHub)
.emi-voice-toast {
  position: fixed;
  bottom: 5.5rem;
  left: 50%;
  transform: translateX(-50%) translateY(12px);
  background: rgba(13, 13, 26, .96);
  border: 1px solid rgba(255, 255, 255, .1);
  color: #f0f0fa;
  padding: .55rem 1.1rem;
  border-radius: 99px;
  font-size: .75rem;
  font-family: var(--mono, monospace);
  opacity: 0;
  pointer-events: none;
  z-index: 9999;
  transition: opacity .25s, transform .25s;
  white-space: nowrap;
  max-width: 90vw;
  text-align: center;
}
.emi-voice-toast.show {
  opacity: 1;
  transform: translateX(-50%) translateY(0);
}
.emi-voice-toast.error  { border-color: rgba(255,69,0,.5); color: #ff9a7a; }
.emi-voice-toast.warn   { border-color: rgba(255,189,105,.5); color: var(--a, #ffbd69); }

═══════════════════════════════════════════════════════════ */
