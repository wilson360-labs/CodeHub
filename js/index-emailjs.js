/* ═══════════════════════════════════════
   index — EmailJS Contact Form
   CodeHub by Wilson.E
═══════════════════════════════════════ */

// ═══════════════════════════════════════
//  EMAILJS — CONFIGURA AQUÍ TUS CLAVES
//  Sigue los 3 pasos abajo para obtenerlas
// ═══════════════════════════════════════
const EMAILJS_PUBLIC_KEY  = 'bjCD9ENp8Zj3nlpSA';   // Paso 1
const EMAILJS_SERVICE_ID  = 'service_gnm5lec';   // Paso 2
const EMAILJS_TEMPLATE_ID = 'template_o2pks0d';  // Paso 3

(function() {
    if (EMAILJS_PUBLIC_KEY !== 'TU_PUBLIC_KEY') {
        emailjs.init({ publicKey: EMAILJS_PUBLIC_KEY });
    }
})();

// ── FILTROS DE PROYECTOS ──────────────
function filterProjects(cat, btn) {
    document.querySelectorAll('.pf-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    document.querySelectorAll('.proj-card').forEach(card => {
        const match = cat === 'all' || card.dataset.cat === cat;
        card.style.transition = 'opacity .25s ease, transform .25s ease';
        if (match) {
            card.style.opacity = '1';
            card.style.transform = '';
            card.style.pointerEvents = '';
            card.style.display = '';
        } else {
            card.style.opacity = '0';
            card.style.transform = 'scale(0.95)';
            card.style.pointerEvents = 'none';
            setTimeout(() => { if (card.style.opacity === '0') card.style.display = 'none'; }, 250);
        }
    });
}

// ── FORMULARIO DE CONTACTO ────────────
const msgArea = document.getElementById('cf-msg');
if (msgArea) {
    msgArea.addEventListener('input', () => {
        const len = msgArea.value.length;
        document.getElementById('char-count').textContent = len;
        if (len > 500) msgArea.value = msgArea.value.slice(0, 500);
    });
}

function validateForm() {
    let valid = true;
    const name  = document.getElementById('cf-name');
    const email = document.getElementById('cf-email');
    const msg   = document.getElementById('cf-msg');

    // Reset errores
    ['err-name','err-email','err-msg'].forEach(id => document.getElementById(id).textContent = '');
    [name, email, msg].forEach(el => el.classList.remove('error'));

    if (!name.value.trim()) {
        document.getElementById('err-name').textContent = 'El nombre es requerido';
        name.classList.add('error'); valid = false;
    }
    const emailRx = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRx.test(email.value)) {
        document.getElementById('err-email').textContent = 'Email inválido';
        email.classList.add('error'); valid = false;
    }
    if (msg.value.trim().length < 10) {
        document.getElementById('err-msg').textContent = 'El mensaje debe tener al menos 10 caracteres';
        msg.classList.add('error'); valid = false;
    }
    return valid;
}

async function sendEmail(e) {
    e.preventDefault();
    if (!validateForm()) return;
    // Verificar Turnstile (solo bloquea si el widget cargó y no fue completado)
    const tsWidget = document.getElementById('turnstile-contact');
    const tsLoaded = tsWidget && tsWidget.querySelector('iframe');
    const tsToken  = document.querySelector('#turnstile-contact [name="cf-turnstile-response"]')?.value
                   || document.querySelector('.cf-turnstile input[name="cf-turnstile-response"]')?.value || '';
    if (tsLoaded && !tsToken) {
        const fb = document.getElementById('form-feedback');
        fb.textContent = '⚠️ Completa la verificación (no soy un robot) antes de enviar.';
        fb.className = 'form-feedback error';
        return;
    }

    const btn      = document.getElementById('form-submit');
    const textEl   = document.getElementById('submit-text');
    const loadEl   = document.getElementById('submit-loading');
    const feedback = document.getElementById('form-feedback');

    // Estado cargando
    btn.disabled = true;
    textEl.style.display = 'none';
    loadEl.style.display = 'inline';
    feedback.className = 'form-feedback';

    // Si las claves no están configuradas, mostramos aviso
    if (EMAILJS_PUBLIC_KEY === 'TU_PUBLIC_KEY') {
        await new Promise(r => setTimeout(r, 1200)); // simula espera
        btn.disabled = false;
        textEl.style.display = 'inline';
        loadEl.style.display = 'none';
        feedback.textContent = '⚙️ Configura tus claves EmailJS para activar el envío. Ver instrucciones en el código.';
        feedback.className = 'form-feedback error';
        return;
    }

    try {
        // Adjuntar token Turnstile al form como campo oculto
        let tsField = document.getElementById('ts-token-field');
        if (!tsField) {
            tsField = document.createElement('input');
            tsField.type = 'hidden'; tsField.name = 'turnstile_token'; tsField.id = 'ts-token-field';
            document.getElementById('contact-form').appendChild(tsField);
        }
        tsField.value = document.querySelector('input[name="cf-turnstile-response"]')?.value || '';
        await emailjs.sendForm(EMAILJS_SERVICE_ID, EMAILJS_TEMPLATE_ID, '#contact-form');
        feedback.textContent = '✅ ¡Mensaje enviado! Te respondo en menos de 24h.';
        feedback.className = 'form-feedback success';
        document.getElementById('contact-form').reset();
        document.getElementById('char-count').textContent = '0';
    } catch (err) {
        feedback.textContent = '❌ Error al enviar. Intenta por WhatsApp o email directo.';
        feedback.className = 'form-feedback error';
        console.error('EmailJS error:', err);
    } finally {
        btn.disabled = false;
        textEl.style.display = 'inline';
        loadEl.style.display = 'none';
    }
}