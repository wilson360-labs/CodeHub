/* ═══════════════════════════════════════════════════════════════
   office-generator.js — Generación de documentos Office/PDF
   Requiere: _loadDocLib() (definida en index.html) para cargar
   docx, XLSX, PptxGenJS y jsPDF bajo demanda.
   ═══════════════════════════════════════════════════════════════ */
'use strict';

const OfficeGen = (() => {
  const _libCache = {};

  function _load(name) {
    if (window._loadDocLib) return window._loadDocLib(name);
    return Promise.reject(new Error('_loadDocLib no disponible'));
  }

  function _safeName(raw) {
    return (raw || 'Documento').replace(/[\\/:*?"<>|]+/g, '-').substring(0, 60);
  }

  function _download(blob, name) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 3000);
  }

  function _toast(msg, type) {
    if (typeof toast === 'function') toast(msg, type);
  }

  function _textToLines(text) {
    return text.replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '').split('\n').filter(l => l.trim());
  }

  function _extractTitle(text) {
    const m = text.match(/^[\s]*[#!]+\s*(.+)/);
    if (m) return m[1].trim().substring(0, 80);
    const first = text.split('\n').find(l => l.trim());
    return first ? first.trim().substring(0, 80) : 'Documento';
  }

  /* ── DOCX ── */
  async function downloadDOCX(title, content) {
    try { await _load('docx'); }
    catch (e) { _toast('⏳ Librerías Office cargando, intenta en 2 segundos', 'info'); return; }
    try {
      const { Document: DocxDoc, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType } = docx;
      const lines = _textToLines(content);
      const docTitle = _extractTitle(content);
      const children = [];

      children.push(new Paragraph({
        children: [new TextRun({ text: title || docTitle, bold: true, size: 32, color: '2f80ed' })],
        heading: HeadingLevel.HEADING_1,
        alignment: AlignmentType.LEFT,
        spacing: { after: 200 }
      }));

      for (const line of lines) {
        const isHeading2 = /^##\s/.test(line);
        const isHeading3 = /^###\s/.test(line);
        const clean = line.replace(/^#{1,3}\s*/, '').trim();
        if (!clean) { children.push(new Paragraph({ text: '', spacing: { after: 100 } })); continue; }

        const heading = isHeading2 ? HeadingLevel.HEADING_2 : isHeading3 ? HeadingLevel.HEADING_3 : undefined;
        const bold = isHeading2 || isHeading3;

        children.push(new Paragraph({
          children: [new TextRun({ text: clean, bold, size: heading ? 28 : 24 })],
          heading,
          spacing: { after: 120 }
        }));
      }

      const doc = new DocxDoc({ sections: [{ children }] });
      const blob = await Packer.toBlob(doc);
      _download(blob, _safeName(title || docTitle) + '.docx');
      _toast('📄 Documento Word descargado', 'success');
    } catch (e) {
      console.error('DOCX error:', e);
      _toast('❌ Error al generar Word: ' + e.message, 'error');
    }
  }

  /* ── XLSX ── */
  async function downloadXLSX(title, content) {
    try { await _load('xlsx'); }
    catch (e) { _toast('⏳ Librerías Office cargando, intenta en 2 segundos', 'info'); return; }
    try {
      const docTitle = _extractTitle(content);
      const lines = _textToLines(content);
      const rows = [];

      for (const line of lines) {
        const clean = line.replace(/^#{1,3}\s*/, '').replace(/\*\*/g, '').trim();
        if (!clean) continue;
        if (clean.includes('|') && clean.split('|').length > 2) {
          const cells = clean.split('|').map(c => c.trim()).filter(Boolean);
          rows.push(cells);
        } else {
          rows.push([clean]);
        }
      }

      if (rows.length === 0) rows.push([content.replace(/<[^>]+>/g, '').substring(0, 100)]);

      const ws = XLSX.utils.aoa_to_sheet(rows);
      ws['!cols'] = rows[0].map(() => ({ wch: 25 }));
      const wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, ws, 'Datos');
      XLSX.writeFile(wb, _safeName(title || docTitle) + '.xlsx');
      _toast('📊 Hoja de cálculo descargada', 'success');
    } catch (e) {
      console.error('XLSX error:', e);
      _toast('❌ Error al generar Excel: ' + e.message, 'error');
    }
  }

  /* ── PPTX ── */
  async function downloadPPTX(title, content) {
    try { await _load('pptxgen'); }
    catch (e) { _toast('⏳ Librerías Office cargando, intenta en 2 segundos', 'info'); return; }
    try {
      const pres = new PptxGenJS();
      const docTitle = _extractTitle(content);
      pres.layout = 'LAYOUT_WIDE';
      pres.author = 'Wilson.E — CodeHub';
      pres.title = title || docTitle;

      const lines = _textToLines(content);
      const chunks = [];
      let current = [];

      for (const line of lines) {
        const clean = line.replace(/^#{1,3}\s*/, '').replace(/\*\*/g, '').trim();
        if (!clean) { if (current.length) chunks.push(current); current = []; continue; }
        current.push(clean);
      }
      if (current.length) chunks.push(current);

      if (chunks.length === 0) chunks.push([docTitle, content.replace(/<[^>]+>/g, '').substring(0, 200)]);

      const slideTitle = pres.addSlide();
      slideTitle.addText(title || docTitle, { x: 0.8, y: 1.5, w: 8.4, h: 1.5, fontSize: 32, bold: true, color: '2f80ed', align: 'center' });
      slideTitle.addText('Generado por WIL.E COPILOT — CodeHub', { x: 0.8, y: 3.2, w: 8.4, h: 0.6, fontSize: 12, color: '999999', align: 'center' });

      for (let i = 0; i < Math.min(chunks.length, 15); i++) {
        const slide = pres.addSlide();
        const firstLine = chunks[i][0];
        const isTitle = chunks[i].length > 1 && firstLine.length < 60;
        const bulletText = isTitle ? chunks[i].slice(1) : chunks[i];

        if (isTitle) {
          slide.addText(firstLine, { x: 0.5, y: 0.3, w: 9, h: 0.8, fontSize: 22, bold: true, color: '2f80ed' });
        }
        slide.addText(bulletText.map(t => ({ text: t, options: { bullet: true, breakLine: true, fontSize: 14, color: '333333' } })), {
          x: 0.5, y: isTitle ? 1.3 : 0.5, w: 9, h: 4.5, valign: 'top'
        });
      }

      await pres.writeFile({ fileName: _safeName(title || docTitle) + '.pptx' });
      _toast('📑 Presentación PowerPoint descargada', 'success');
    } catch (e) {
      console.error('PPTX error:', e);
      _toast('❌ Error al generar PowerPoint: ' + e.message, 'error');
    }
  }

  /* ── PDF ── */
  async function downloadPDF(title, content) {
    try { await _load('jspdf'); }
    catch (e) { _toast('⏳ Librerías Office cargando, intenta en 2 segundos', 'info'); return; }
    try {
      const { jsPDF } = window.jspdf;
      const doc = new jsPDF({ unit: 'mm', format: 'a4' });
      const docTitle = _extractTitle(content);
      const lines = _textToLines(content);
      const pageW = 210, margin = 18, maxW = pageW - margin * 2;
      let y = 25;

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(20);
      doc.setTextColor(47, 128, 237);
      const titleLines = doc.splitTextToSize(title || docTitle, maxW);
      doc.text(titleLines, margin, y);
      y += titleLines.length * 8 + 6;

      doc.setFont('helvetica', 'normal');
      doc.setFontSize(10);
      doc.setTextColor(120, 120, 120);
      doc.text('Generado por WIL.E COPILOT — CodeHub', margin, y);
      y += 10;

      doc.setDrawColor(47, 128, 237);
      doc.setLineWidth(0.3);
      doc.line(margin, y, pageW - margin, y);
      y += 8;

      for (const line of lines) {
        const clean = line.replace(/^#{1,3}\s*/, '').replace(/\*\*/g, '').trim();
        if (!clean) { y += 4; continue; }
        if (y > 270) { doc.addPage(); y = 20; }

        const isH2 = /^##\s/.test(line);
        const isH3 = /^###\s/.test(line);

        if (isH2 || isH3) {
          doc.setFont('helvetica', 'bold');
          doc.setFontSize(isH2 ? 14 : 12);
          doc.setTextColor(47, 128, 237);
        } else {
          doc.setFont('helvetica', 'normal');
          doc.setFontSize(10);
          doc.setTextColor(40, 40, 40);
        }

        const wrapped = doc.splitTextToSize(clean, maxW);
        doc.text(wrapped, margin, y);
        y += wrapped.length * (isH2 ? 7 : isH3 ? 6 : 5) + 3;
      }

      doc.save(_safeName(title || docTitle) + '.pdf');
      _toast('📕 Documento PDF descargado', 'success');
    } catch (e) {
      console.error('PDF error:', e);
      _toast('❌ Error al generar PDF: ' + e.message, 'error');
    }
  }

  /* ── Barra de botones ── */
  function createDownloadBar(title, content) {
    const bar = document.createElement('div');
    bar.className = 'office-dl-bar';
    bar.innerHTML = `
      <span class="office-dl-label">Descargar como:</span>
      <button class="office-dl-btn" data-fmt="docx" title="Word"><i class="fas fa-file-word"></i> Word</button>
      <button class="office-dl-btn" data-fmt="xlsx" title="Excel"><i class="fas fa-file-excel"></i> Excel</button>
      <button class="office-dl-btn" data-fmt="pptx" title="PowerPoint"><i class="fas fa-file-powerpoint"></i> PPT</button>
      <button class="office-dl-btn" data-fmt="pdf" title="PDF"><i class="fas fa-file-pdf"></i> PDF</button>`;
    bar.querySelectorAll('.office-dl-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const fmt = btn.dataset.fmt;
        btn.disabled = true;
        btn.style.opacity = '.5';
        setTimeout(() => { btn.disabled = false; btn.style.opacity = ''; }, 3000);
        if (fmt === 'docx') downloadDOCX(title, content);
        else if (fmt === 'xlsx') downloadXLSX(title, content);
        else if (fmt === 'pptx') downloadPPTX(title, content);
        else if (fmt === 'pdf') downloadPDF(title, content);
      });
    });
    return bar;
  }

  return { downloadDOCX, downloadXLSX, downloadPPTX, downloadPDF, createDownloadBar };
})();
