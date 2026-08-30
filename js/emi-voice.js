/* ═══════════════════════════════════════════════════════════
   WIL.E VOICE — Web Speech API Module
   CodeHub by Wilson.E
   
   INTEGRACIÓN:
   1. Copiar este archivo a /js/emi-voice.js
   2. Agregar en index.html ANTES del </body>:
      <script src="js/emi-voice.js" defer></script>
   3. Agregar el botón de micrófono en el ai-input-row (ver instrucciones abajo)
   4. Agregar el CSS de este archivo al bloque <style> de index.html
   
   BOTÓN A INSERTAR en index.html — dentro de .ai-input-row,
   entre el <textarea> y el <button id="ai-send-btn">:
   
    <button id="emi-mic-btn" type="button" aria-label="Hablar con WIL.E" title="Hablar con WIL.E (voz a texto)" onclick="emiVoice.toggle()">
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
  // APK nativo: si el puente expone STT/TTS, se usa por encima del Web Speech
  // porque en WebView Android el reconocimiento de voz del navegador no existe.
  const HAS_NATIVE_STT = !!(window.CodeHubNative && typeof window.CodeHubNative.sttStart === 'function');
  const HAS_NATIVE_TTS = !!(window.CodeHubNative && typeof window.CodeHubNative.ttsSpeak === 'function');

  /* ── Estado global del módulo ── */
  const state = {
    listening    : false,
    speaking     : false,
    recognition  : null,
    lastTranscript: '',
    speakQueue   : [],
    lang         : localStorage.getItem('ch_lang') === 'en' ? 'en-US' : 'es-GT',
    autoSpeak    : JSON.parse(localStorage.getItem('emi_auto_speak') ?? 'false'),
    jarvis       : localStorage.getItem('emi_jarvis_voice') === '1',
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

    /* Mostrar tooltip si no hay soporte STT web ni nativo */
    if (!HAS_RECOGNITION && !HAS_NATIVE_STT) {
      micBtn.title = 'Tu navegador no soporta voz. Prueba un navegador moderno.';
      micBtn.classList.add('emi-mic-unsupported');
      micBtn.onclick = () => showVoiceToast('Tu navegador no soporta reconocimiento de voz. Usa un navegador moderno.', 'warn');
    } else if (HAS_RECOGNITION) {
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
    } // end HAS_RECOGNITION else

    /* ── Botón de auto-speak (siempre, incluso sin STT) ── */
    injectAutoSpeakToggle();

    /* ── Exponer en window para uso externo ── */
    window.emiVoice = publicAPI;

    /* ── Hook: interceptar respuestas de WIL.E para auto-speak ── */
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
    if (state.listening) return;
    /* Parar síntesis si estaba hablando */
    if (state.speaking) stopSpeaking();
    /* APK nativo: STT por puente Java (fiable en WebView) */
    if (HAS_NATIVE_STT) {
      state.listening = true;
      if (micBtn) micBtn.classList.add('emi-mic-active');
      if (statusEl) statusEl.textContent = '🎙️ Escuchando…';
      if (inputEl)  inputEl.placeholder  = 'Habla ahora…';
      window._sttCb = {
        onStart: () => {},
        onResult: (txt) => { finishNativeStt(); if (inputEl) inputEl.value = (inputEl.value.trim() ? inputEl.value.trim() + ' ' : '') + txt; },
        onError: (msg) => { finishNativeStt(); showVoiceToast(String(msg).replace(/error_nativo_(\d+)/, ''), 'error'); }
      };
      try { CodeHubNative.sttStart('_sttCb'); } catch (e) { finishNativeStt(); showVoiceToast('Error al iniciar micrófono.', 'error'); }
      return;
    }
    if (!HAS_RECOGNITION) return;
    try {
      state.recognition.lang = state.lang;
      state.recognition.start();
    } catch (err) {
      showVoiceToast('Error al iniciar micrófono: ' + err.message, 'error');
    }
  }

  function finishNativeStt() {
    state.listening = false;
    if (micBtn) micBtn.classList.remove('emi-mic-active');
    if (statusEl) statusEl.textContent = 'Online · Listo para ayudarte';
    if (inputEl)  inputEl.placeholder  = 'Pregúntame algo, genera una imagen, depura código…';
    if (HAS_NATIVE_STT) { try { CodeHubNative.sttStop(); } catch (e) {} }
  }

  function stopListening() {
    if (HAS_NATIVE_STT) { finishNativeStt(); return; }
    state.listening = false;
    if (micBtn) micBtn.classList.remove('emi-mic-active');
    if (statusEl) statusEl.textContent = 'Online · Listo para ayudarte';
    if (inputEl)  inputEl.placeholder  = 'Pregúntame algo, genera una imagen, depura código…';
  }

  function toggle() {
    if (state.listening) {
      stopListening();
    } else {
      startListening();
    }
  }

  /* ════════════════════════════════════════════
     SÍNTESIS DE VOZ (WIL.E habla)
  ════════════════════════════════════════════ */
  // Intenta TTS premium (ElevenLabs) vía backend; si no hay key, cae en la
  // voz del navegador de forma transparente (speechSynthesis).
  let ttsAvailable = null; // null = sin comprobar, true/false tras info
  async function refreshTtsInfo() {
    try {
      const base = (typeof window._CH_BACKEND !== 'undefined' && window._CH_BACKEND) ? window._CH_BACKEND : 'https://codehub-98s6.onrender.com';
      const ctrl = new AbortController();
      const to = setTimeout(() => ctrl.abort(), 5000);
      const r = await fetch(base + '/api/tts/info', { signal: ctrl.signal });
      clearTimeout(to);
      const d = await r.json().catch(() => ({}));
      ttsAvailable = !!(d && d.available);
    } catch (e) {
      ttsAvailable = false;
    }
  }

  async function ttsSpeak(text) {
    try {
      const base = (typeof window._CH_BACKEND !== 'undefined' && window._CH_BACKEND) ? window._CH_BACKEND : 'https://codehub-98s6.onrender.com';
      const ctrl = new AbortController();
      const to = setTimeout(() => ctrl.abort(), 30000);
      const r = await fetch(base + '/api/tts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: text.slice(0, 550), voice: state.lang.indexOf('en') === 0 ? 'pNInz6obpgDQGcFmaJgB' : (state.jarvis ? 'TxGEqnHWrfWFTfGW9XjX' : 'N2lD1ixsuvnrwL7fM2Yv') }),
        signal: ctrl.signal,
      });
      clearTimeout(to);
      if (!r.ok) return false;
      const d = await r.json().catch(() => ({}));
      if (!d || !d.audio) return false;
      const audio = new Audio();
      // para el botón de interrumpir
      audio.src = d.audio;
      state.speaking = true;
      if (micBtn) micBtn.title = 'WIL.E está hablando… (clic para interrumpir)';
      const done = new Promise((res) => {
        audio.onended = () => { state.speaking = false; if (micBtn) micBtn.title = 'Hablar con WIL.E (voz a texto)'; res(true); };
        audio.onerror = () => { state.speaking = false; if (micBtn) micBtn.title = 'Hablar con WIL.E (voz a texto)'; res(false); };
      });
      audio.play();
      await done;
      return true;
    } catch (e) {
      state.speaking = false;
      return false;
    }
  }

  function speak(text) {
    if (!text) return;
    /* Limpiar markdown/HTML básico del texto */
    const clean = String(text)
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

    // 1) Intentar voz premium (Jarvis) si hay TTS disponible
    if (ttsAvailable) {
      ttsSpeak(clean).then((ok) => { if (!ok) fallbackSpeak(clean); });
      return;
    }
    // 2) Comprobar disponibilidad TTS una vez (asíncrono) y usarla si está
    if (ttsAvailable === null) {
      refreshTtsInfo().then(() => {
        if (ttsAvailable) { ttsSpeak(clean); return; }
        fallbackSpeak(clean);
      });
      return;
    }
    // 3) Fallback: voz del navegador en español
    fallbackSpeak(clean);
  }

  function fallbackSpeak(clean) {
    /* APK nativo: TTS de Android en español, fiable incluso sin Internet */
    if (HAS_NATIVE_TTS) {
      try {
        state.speaking = true;
        if (micBtn) micBtn.title = 'WIL.E está hablando… (clic para interrumpir)';
        CodeHubNative.ttsSpeak(clean);
        setTimeout(() => {
          state.speaking = false;
          if (micBtn) micBtn.title = 'Hablar con WIL.E (voz a texto)';
        }, 8000);
        return;
      } catch (e) { state.speaking = false; }
    }
    if (!HAS_SYNTHESIS) return;
    const utt = new SpeechSynthesisUtterance(clean);
    utt.lang  = state.lang;
    utt.rate  = state.jarvis ? 0.92 : 1.05;
    utt.pitch = state.jarvis ? 0.6 : 1.1;

    /* Elegir voz en español si disponible (prioridad latinoamérica/es) */
    const voices = SS.getVoices();
    const langCode = state.lang.split('-')[0];
    const preferred =
      voices.find(v => v.lang.indexOf('es-41') === 0 && (v.name.includes('Google') || v.name.includes('Microsoft') || v.localService)) ||
      voices.find(v => v.lang.indexOf('es') === 0 && (v.name.includes('Google') || v.name.includes('Microsoft') || v.localService)) ||
      (state.jarvis && voices.find(v => /pablo|andres|jorge|hugo|javier|diego|raul|gonzalo/i.test(v.name))) ||
      voices.find(v => v.lang.indexOf('es') === 0);
    if (preferred) utt.voice = preferred;

    utt.onstart = () => {
      state.speaking = true;
      if (micBtn) micBtn.title = 'WIL.E está hablando… (clic para interrumpir)';
    };
    utt.onend = utt.onerror = () => {
      state.speaking = false;
      if (micBtn) micBtn.title = 'Hablar con WIL.E (voz a texto)';
    };

    SS.speak(utt);
  }

  function stopSpeaking() {
    if (HAS_NATIVE_TTS) { try { CodeHubNative.ttsStop(); } catch (e) {} }
    if (HAS_SYNTHESIS) SS.cancel();
    state.speaking = false;
    if (window.ttsAudio) { try { window.ttsAudio.pause(); } catch (e) {} }
  }

  /* ════════════════════════════════════════════
     HOOK: capturar respuestas de WIL.E para auto-speak
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
          /* Mensaje de la IA: tiene clase .ai-msg-wrap.ai pero NO .user ni .ai-typing */
          const wrap = node.classList?.contains('ai-msg-wrap') && node.classList?.contains('ai') && !node.classList?.contains('user') && !node.classList?.contains('ai-typing')
            ? node
            : node.querySelector?.('.ai-msg-wrap.ai:not(.user):not(.ai-typing)');
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
    btn.title       = 'WIL.E te responde en voz alta';
    btn.setAttribute('aria-label', 'Toggle respuesta por voz');
    btn.innerHTML   = `
      <div class="ai-circle-icon"><i class="fas fa-volume-${state.autoSpeak ? 'high' : 'xmark'}"></i></div>
      <span class="ai-circle-label" data-subtitle="Respuesta por voz">Voz WIL.E</span>
    `;
    btn.onclick = () => {
      state.autoSpeak = !state.autoSpeak;
      localStorage.setItem('emi_auto_speak', JSON.stringify(state.autoSpeak));
      btn.querySelector('i').className = `fas fa-volume-${state.autoSpeak ? 'high' : 'xmark'}`;
      showVoiceToast(state.autoSpeak ? '🔊 WIL.E responderá en voz alta' : '🔇 Voz de WIL.E desactivada', 'info');
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
    get jarvis()      { return state.jarvis; },
    setJarvis(on) {
      state.jarvis = !!on;
      try { localStorage.setItem('emi_jarvis_voice', state.jarvis ? '1' : '0'); } catch (e) {}
    },
    setLang(lang) {
      state.lang = lang;
      if (state.recognition) state.recognition.lang = lang;
    },
    onAssistantDone(text) {
      if (state.autoSpeak && text) speak(text);
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
  color: #2f80ed;
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
  background: radial-gradient(circle, rgba(47,128,237,.3) 0%, transparent 70%);
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
.emi-voice-toast.error  { border-color: rgba(47,128,237,.5); color: #ff9a7a; }
.emi-voice-toast.warn   { border-color: rgba(56,189,248,.5); color: var(--a, #38bdf8); }

═══════════════════════════════════════════════════════════ */
