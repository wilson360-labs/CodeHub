#!/usr/bin/env node
/**
 * Servidor estático mínimo para tests de Playwright (sin dependencias).
 * Sirve el repo tal cual: '/' → index.html, rutas → archivos.
 * No aplica rewrites de vercel.json (los tests navegan a rutas reales).
 * Uso: node scripts/serve.js [puerto]
 */
const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = process.cwd();
const PORT = Number(process.argv[2]) || 4173;

const MIME = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.gif': 'image/gif',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.pdf': 'application/pdf',
  '.mp3': 'audio/mpeg', '.mp4': 'video/mp4', '.txt': 'text/plain; charset=utf-8', '.webmanifest': 'application/manifest+json',
};

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  let rel = decodeURIComponent(url.pathname).replace(/^\/+/, '').split('/');
  let filePath = path.join(ROOT, ...rel);
  if (!filePath.startsWith(ROOT)) { res.writeHead(403); res.end('Forbidden'); return; }
  if (url.pathname === '/' || rel[rel.length - 1].indexOf('.') === -1) {
    const idx = fs.existsSync(filePath) && fs.statSync(filePath).isDirectory() ? path.join(filePath, 'index.html') : filePath;
    filePath = fs.existsSync(idx) ? idx : filePath;
  }
  fs.readFile(filePath, (err, data) => {
    if (err) { res.writeHead(404); res.end('Not found'); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(filePath)] || 'application/octet-stream' });
    res.end(data);
  });
});

server.listen(PORT, () => console.log(`[serve] http://localhost:${PORT} (${ROOT})`));