/* ═══════════════════════════════════════════════════════════════
   deep-search.js — Búsqueda profunda dual (Google + Tavily)
   Usa el backend Render como proxy para evitar CORS y exponer keys.
   ═══════════════════════════════════════════════════════════════ */
'use strict';

const DeepSearch = (() => {
  const BACKEND = 'https://codehub-98s6.onrender.com';

  /* ── Google Custom Search via proxy ── */
  async function _googleSearch(query) {
    try {
      const res = await fetch(`${BACKEND}/api/search/google?q=${encodeURIComponent(query)}`, {
        signal: AbortSignal.timeout(8000)
      });
      if (!res.ok) return null;
      const data = await res.json();
      if (!data.items || data.items.length === 0) return null;
      return {
        source: 'Google',
        items: data.items.slice(0, 5).map(item => ({
          title: item.title,
          snippet: item.snippet,
          url: item.link
        }))
      };
    } catch { return null; }
  }

  /* ── Tavily via proxy ── */
  async function _tavilySearch(query) {
    try {
      const res = await fetch(`${BACKEND}/api/search/tavily?q=${encodeURIComponent(query)}`, {
        signal: AbortSignal.timeout(8000)
      });
      if (!res.ok) return null;
      const data = await res.json();
      if (!data.results || data.results.length === 0) return null;
      return {
        source: 'Tavily',
        answer: data.answer || null,
        items: data.results.slice(0, 5).map(item => ({
          title: item.title,
          snippet: item.content || item.snippet || '',
          url: item.url
        }))
      };
    } catch { return null; }
  }

  /* ── Búsqueda dual: Google primero, Tavily como fallback/suplemento ── */
  async function search(query) {
    const google = await _googleSearch(query);
    const tavily = await _tavilySearch(query);

    const results = { sources: [], answer: null, items: [] };

    if (tavily && tavily.answer) {
      results.answer = tavily.answer;
    }

    if (google && google.items.length > 0) {
      results.sources.push('Google');
      results.items.push(...google.items);
    }

    if (tavily && tavily.items.length > 0) {
      results.sources.push('Tavily');
      for (const item of tavily.items) {
        if (!results.items.find(r => r.url === item.url)) {
          results.items.push(item);
        }
      }
    }

    return results;
  }

  /* ── Formatear resultados para el chat ── */
  function formatResults(results) {
    if (!results.items || results.items.length === 0) return null;

    let html = '';
    if (results.answer) {
      html += `<div class="deep-search-answer"><strong>Resumen:</strong> ${results.answer}</div>`;
    }

    html += '<div class="deep-search-sources">';
    html += `<span class="deep-search-label">Fuentes (${results.sources.join(' + ')}):</span>`;
    results.items.forEach((item, i) => {
      const domain = item.url ? new URL(item.url).hostname.replace('www.', '') : '';
      html += `<a href="${item.url}" target="_blank" rel="noopener" class="deep-search-source">[${i + 1}] ${domain}</a>`;
    });
    html += '</div>';

    return html;
  }

  /* ── Detectar si una consulta necesita búsqueda web ── */
  function needsSearch(text) {
    const lower = text.toLowerCase();
    const searchTriggers = [
      'cuál es', 'cuales son', 'qué es', 'que es', 'quién es', 'quien es',
      'cuánto vale', 'cuanto vale', 'precio', 'noticias', 'últimas noticias',
      'último', 'actualidad', 'hoy en día', 'en este momento', '2024', '2025', '2026',
      'busca', 'buscar', 'investiga', 'encuentra', 'sobre', 'acerca de',
      'opinión', 'opinion', 'reseña', 'comparar', 'vs', 'diferencia entre',
      'cómo se hace', 'como se hace', 'tutorial', 'paso a paso',
      'dónde', 'donde', 'cuándo', 'cuando', 'cuánto', 'cuanto',
      'cuáles', 'cuales', 'cuál', 'cual'
    ];
    return searchTriggers.some(t => lower.includes(t));
  }

  return { search, formatResults, needsSearch };
})();
