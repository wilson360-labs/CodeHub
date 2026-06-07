/**
 * CodeHub — Renovar Blogger access_token con refresh_token
 * Uso: node refresh-token.js
 */

require('dotenv').config();
const fs   = require('fs');
const path = require('path');

const CLIENT_ID     = process.env.BLOGGER_CLIENT_ID;
const CLIENT_SECRET = process.env.BLOGGER_CLIENT_SECRET;
const REFRESH_TOKEN = process.env.BLOGGER_REFRESH_TOKEN;

if (!CLIENT_ID || !CLIENT_SECRET || !REFRESH_TOKEN) {
  console.error('\n❌ Faltan variables en .env:');
  if (!CLIENT_ID)     console.error('   BLOGGER_CLIENT_ID');
  if (!CLIENT_SECRET) console.error('   BLOGGER_CLIENT_SECRET');
  if (!REFRESH_TOKEN) console.error('   BLOGGER_REFRESH_TOKEN (ejecuta get-token.js primero)');
  process.exit(1);
}

async function refreshAccessToken() {
  console.log('\n🔄 Renovando access token...\n');

  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type:    'refresh_token',
      refresh_token: REFRESH_TOKEN,
      client_id:     CLIENT_ID,
      client_secret: CLIENT_SECRET,
    }),
  });

  const data = await res.json();

  if (data.error) {
    console.error(`❌ Error: ${data.error_description || data.error}`);
    console.log('\nEl refresh_token puede haber expirado.');
    console.log('Ejecuta get-token.js para obtener uno nuevo.\n');
    process.exit(1);
  }

  const { access_token, expires_in } = data;

  // Actualizar .env
  const envPath = path.join(__dirname, '.env');
  if (fs.existsSync(envPath)) {
    let envContent = fs.readFileSync(envPath, 'utf8');
    if (envContent.includes('BLOGGER_ACCESS_TOKEN=')) {
      envContent = envContent.replace(
        /BLOGGER_ACCESS_TOKEN=.*/,
        `BLOGGER_ACCESS_TOKEN=${access_token}`
      );
    } else {
      envContent += `\nBLOGGER_ACCESS_TOKEN=${access_token}`;
    }
    fs.writeFileSync(envPath, envContent);
    console.log('✅ .env actualizado automáticamente');
  }

  console.log(`✅ Nuevo access_token obtenido`);
  console.log(`⏱  Válido por: ${Math.round(expires_in / 3600)} horas`);
  console.log(`\nBLOGGER_ACCESS_TOKEN=${access_token}\n`);

  return access_token;
}

refreshAccessToken().catch(err => {
  console.error('Error inesperado:', err.message);
  process.exit(1);
});
