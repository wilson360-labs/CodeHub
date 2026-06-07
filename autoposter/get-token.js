/**
 * ╔═══════════════════════════════════════════════════════════════╗
 * ║  CodeHub — Blogger OAuth2 Token Helper                        ║
 * ║                                                               ║
 * ║  Pasos:                                                       ║
 * ║  1. Crea un proyecto en Google Cloud Console                  ║
 * ║  2. Activa Blogger API v3                                     ║
 * ║  3. Crea credenciales OAuth2 (Desktop App)                    ║
 * ║  4. Guarda CLIENT_ID y CLIENT_SECRET en .env                  ║
 * ║  5. node get-token.js  → sigue las instrucciones              ║
 * ╚═══════════════════════════════════════════════════════════════╝
 */

require('dotenv').config();
const http     = require('http');
const { exec } = require('child_process');

const CLIENT_ID     = process.env.BLOGGER_CLIENT_ID;
const CLIENT_SECRET = process.env.BLOGGER_CLIENT_SECRET;
const REDIRECT_URI  = 'http://localhost:8085/callback';
const SCOPE         = 'https://www.googleapis.com/auth/blogger';

if (!CLIENT_ID || !CLIENT_SECRET) {
  console.error('\n❌ Faltan BLOGGER_CLIENT_ID y BLOGGER_CLIENT_SECRET en .env\n');
  console.log('Pasos para obtenerlos:');
  console.log('  1. Ir a https://console.cloud.google.com');
  console.log('  2. Crear proyecto → API Library → buscar "Blogger API v3" → Habilitar');
  console.log('  3. Credentials → Create credentials → OAuth 2.0 Client IDs');
  console.log('  4. Application type: Desktop App');
  console.log('  5. Descargar el JSON y copiar client_id y client_secret a .env\n');
  process.exit(1);
}

// Construir URL de autorización
const authUrl = new URL('https://accounts.google.com/o/oauth2/v2/auth');
authUrl.searchParams.set('client_id',     CLIENT_ID);
authUrl.searchParams.set('redirect_uri',  REDIRECT_URI);
authUrl.searchParams.set('response_type', 'code');
authUrl.searchParams.set('scope',         SCOPE);
authUrl.searchParams.set('access_type',   'offline');  // para refresh_token
authUrl.searchParams.set('prompt',        'consent');   // fuerza nuevo refresh_token

console.log('\n╔═══════════════════════════════════════════╗');
console.log('║  CodeHub — Blogger OAuth2 Token           ║');
console.log('╚═══════════════════════════════════════════╝\n');
console.log('🌐 Abriendo el navegador para autenticación...');
console.log('   Si no abre automáticamente, copia esta URL:\n');
console.log(`   ${authUrl.toString()}\n`);

// Intentar abrir el navegador
const openCmd = process.platform === 'win32' ? 'start' : process.platform === 'darwin' ? 'open' : 'xdg-open';
exec(`${openCmd} "${authUrl.toString()}"`);

// Servidor local para recibir el callback
const server = http.createServer(async (req, res) => {
  const url    = new URL(req.url, `http://localhost:8085`);
  const code   = url.searchParams.get('code');
  const error  = url.searchParams.get('error');

  if (error) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(`<h2>❌ Error: ${error}</h2><p>Cierra esta ventana e intenta de nuevo.</p>`);
    server.close();
    return;
  }

  if (!code) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end('<h2>Esperando código...</h2>');
    return;
  }

  try {
    // Intercambiar code por tokens
    const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        code,
        client_id:     CLIENT_ID,
        client_secret: CLIENT_SECRET,
        redirect_uri:  REDIRECT_URI,
        grant_type:    'authorization_code',
      }),
    });

    const tokens = await tokenRes.json();

    if (tokens.error) throw new Error(tokens.error_description || tokens.error);

    const { access_token, refresh_token, expires_in } = tokens;

    // Respuesta visual en el navegador
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(`
      <!DOCTYPE html>
      <html>
      <head><style>
        body { font-family: monospace; background: #050510; color: #e8e8f0; padding: 2rem; max-width: 700px; margin: 0 auto; }
        h2   { color: #00e676; }
        .box { background: #0d0d1e; border: 1px solid rgba(255,69,0,.3); border-radius: 8px; padding: 1rem; margin: 1rem 0; word-break: break-all; font-size: .8rem; }
        .lbl { color: #ff4500; font-size: .75rem; margin-bottom: .3rem; }
        .ok  { color: #00e676; }
        .warn{ color: #ffbd69; }
      </style></head>
      <body>
        <h2>✅ Autenticación exitosa</h2>
        <p>Copia estos valores en tu archivo <code>.env</code>:</p>
        <div class="lbl">BLOGGER_ACCESS_TOKEN</div>
        <div class="box">${access_token}</div>
        <div class="lbl">BLOGGER_REFRESH_TOKEN ${refresh_token ? '<span class="ok">(¡guárdalo, solo aparece una vez!)</span>' : '<span class="warn">(no disponible, ya tenías uno)</span>'}</div>
        <div class="box">${refresh_token || '(sin cambios — el anterior sigue vigente)'}</div>
        <p class="warn">⚠️ El access_token expira en ${Math.round(expires_in/3600)}h. Usa el refresh_token para renovarlo.</p>
        <p>Cierra esta ventana y vuelve a la terminal.</p>
      </body>
      </html>
    `);

    // Imprimir en la terminal también
    console.log('\n╔══════════════════════════════════════════════════╗');
    console.log('║  ✅ AUTENTICACIÓN EXITOSA                         ║');
    console.log('╚══════════════════════════════════════════════════╝\n');
    console.log('Agrega estas líneas a tu archivo .env:\n');
    console.log(`BLOGGER_ACCESS_TOKEN=${access_token}`);
    if (refresh_token) {
      console.log(`BLOGGER_REFRESH_TOKEN=${refresh_token}`);
    }
    console.log(`\n⏱  Expira en: ${Math.round(expires_in/3600)} horas`);
    if (refresh_token) {
      console.log('💡 Guarda el REFRESH_TOKEN — solo aparece la primera vez');
      console.log('   Para renovar el access_token: node refresh-token.js\n');
    }

    server.close();

  } catch (err) {
    res.writeHead(500, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(`<h2>❌ Error al obtener tokens</h2><pre>${err.message}</pre>`);
    console.error('\n❌ Error al obtener tokens:', err.message);
    server.close();
  }
});

server.listen(8085, () => {
  console.log('⏳ Servidor escuchando en localhost:8085...');
  console.log('   Autentícate en el navegador y vuelve aquí.\n');
});
