// Minimal HTML -> DOCX converter (pure JS, no native modules). Handles what the Notia editor produces.
import JSZip from 'jszip';

type Fmt = { b?: boolean; i?: boolean; u?: boolean; code?: boolean; color?: string; bg?: string; size?: number; link?: string };
const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const dec = (s: string) => s.replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&');
const attr = (a: string, n: string) => { const m = new RegExp('\\b' + n + '\\s*=\\s*"([^"]*)"', 'i').exec(a); return m ? dec(m[1]) : ''; };
const styleVal = (st: string, p: string) => { const m = new RegExp('(?:^|;)\\s*' + p + '\\s*:\\s*([^;]+)', 'i').exec(st); return m ? m[1].trim() : ''; };
const hex = (v: string): string | undefined => {
  let m = /^#([0-9a-f]{6})$/i.exec(v.trim()); if (m) return m[1].toUpperCase();
  m = /^#([0-9a-f]{3})$/i.exec(v.trim()); if (m) return m[1].split('').map(c => c + c).join('').toUpperCase();
  m = /rgba?\(\s*(\d+)\D+(\d+)\D+(\d+)/i.exec(v); if (m) return [m[1], m[2], m[3]].map(n => Math.min(255, +n).toString(16).padStart(2, '0')).join('').toUpperCase();
  return undefined;
};
const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function b64ToBytes(b: string): Uint8Array {
  const out = new Uint8Array(Math.floor((b.length * 3) / 4)); let bits = 0, acc = 0, o = 0;
  for (const ch of b) { const v = B64.indexOf(ch); if (v < 0) continue; acc = (acc << 6) | v; bits += 6; if (bits >= 8) { bits -= 8; out[o++] = (acc >> bits) & 255; acc &= (1 << bits) - 1; } }
  return out.subarray(0, o);
}
function imgSize(b: Uint8Array): [number, number] | null {
  if (b[0] === 0x89 && b[1] === 0x50) return [((b[16] << 24) | (b[17] << 16) | (b[18] << 8) | b[19]) >>> 0, ((b[20] << 24) | (b[21] << 16) | (b[22] << 8) | b[23]) >>> 0];
  let i = 2;
  while (i + 9 < b.length) {
    if (b[i] !== 0xff) { i++; continue; }
    const m = b[i + 1];
    if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) return [(b[i + 7] << 8) | b[i + 8], (b[i + 5] << 8) | b[i + 6]];
    i += 2 + ((b[i + 2] << 8) | b[i + 3]);
  }
  return null;
}
const BLOCK = new Set(['div', 'p', 'h1', 'h2', 'h3', 'pre', 'blockquote']);
const VOID = new Set(['br', 'hr', 'img', 'input', 'meta']);
const NS = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"';

export async function buildDocx(title: string, html: string): Promise<string> {
  const zip = new JSZip();
  const put = (n: string, d: string | Uint8Array) => zip.file(n, d, { createFolders: false });
  const rels: string[] = [];
  let rid = 0, mid = 0;
  const addRel = (type: string, target: string, ext = false) => { const id = 'rId' + ++rid; rels.push(`<Relationship Id="${id}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/${type}" Target="${esc(target)}"${ext ? ' TargetMode="External"' : ''}/>`); return id; };

  const body: string[] = [];
  let out = body, runs: string[] = [], ppr = '', pre = '';
  const fm: Fmt[] = [{}], open: string[] = [], lists: { ord: boolean; n: number }[] = [];
  let tbl: string[][] | null = null, row: string[] = [], cell: string[] = [];

  const runXml = (t: string, f: Fmt) => {
    const rpr = (f.code ? '<w:rFonts w:ascii="Courier New" w:hAnsi="Courier New"/>' : '') + (f.b ? '<w:b/>' : '') + (f.i ? '<w:i/>' : '') +
      (f.color ? `<w:color w:val="${f.color}"/>` : '') + (f.size ? `<w:sz w:val="${f.size}"/>` : '') + (f.u ? '<w:u w:val="single"/>' : '') +
      (f.bg ? `<w:shd w:val="clear" w:color="auto" w:fill="${f.bg}"/>` : '');
    const r = `<w:r><w:rPr>${rpr}</w:rPr>${t.split('\n').map((p, k) => (k ? '<w:br/>' : '') + `<w:t xml:space="preserve">${esc(p)}</w:t>`).join('')}</w:r>`;
    return f.link ? `<w:hyperlink r:id="${f.link}" w:history="1">${r}</w:hyperlink>` : r;
  };
  const para = (t: string, f: Fmt = {}) => `<w:p>${runXml(t, f)}</w:p>`;
  const flush = (force = false) => {
    if (runs.length || pre || force) out.push(`<w:p><w:pPr>${ppr}</w:pPr>${pre ? runXml(pre, {}) : ''}${runs.join('')}</w:p>`);
    runs = []; ppr = ''; pre = '';
  };
  const tableXml = (rows: string[][]) => {
    const cols = Math.max(1, ...rows.map(r => r.length));
    const bd = ['top', 'left', 'bottom', 'right', 'insideH', 'insideV'].map(s => `<w:${s} w:val="single" w:sz="4" w:space="0" w:color="999999"/>`).join('');
    return `<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="pct"/><w:tblBorders>${bd}</w:tblBorders></w:tblPr><w:tblGrid>${`<w:gridCol w:w="${Math.floor(9000 / cols)}"/>`.repeat(cols)}</w:tblGrid>` +
      rows.map(r => { const c = [...r]; while (c.length < cols) c.push('<w:p/>'); return '<w:tr>' + c.map(x => `<w:tc><w:tcPr><w:tcW w:w="${Math.floor(5000 / cols)}" w:type="pct"/></w:tcPr>${x}</w:tc>`).join('') + '</w:tr>'; }).join('') + '</w:tbl><w:p/>';
  };

  body.push(para(title || 'Untitled', { b: true, size: 44 }));
  const re = /<(\/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|([^<]+)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(html))) {
    if (m[4] !== undefined) {
      const inPre = open.includes('pre');
      let t = dec(m[4]);
      if (!inPre) { t = t.replace(/\s+/g, ' '); if (!t.trim() && !runs.length) continue; }
      runs.push(runXml(t, fm[fm.length - 1]));
      continue;
    }
    const closing = m[1] === '/', tag = m[2].toLowerCase(), a = m[3] || '';
    if (closing) {
      const k = open.lastIndexOf(tag);
      if (k < 0) continue;
      open.length = k; fm.length = k + 1;
      if (BLOCK.has(tag) || tag === 'li') flush();
      else if (tag === 'ul' || tag === 'ol') { flush(); lists.pop(); }
      else if (tag === 'td' || tag === 'th') { flush(); if (!cell.length) cell.push('<w:p/>'); row.push(cell.join('')); out = body; }
      else if (tag === 'tr' && tbl) tbl.push(row);
      else if (tag === 'table' && tbl) { body.push(tableXml(tbl)); tbl = null; }
      continue;
    }
    if (VOID.has(tag)) {
      if (tag === 'br') runs.push('<w:r><w:br/></w:r>');
      else if (tag === 'hr') { flush(); out.push('<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="888888"/></w:pBdr></w:pPr></w:p>'); }
      else if (tag === 'img') {
        const alt = attr(a, 'alt'), src = attr(a, 'src');
        if (alt.startsWith('chart|')) {
          const [, t, d] = alt.split('|');
          flush(); out.push(para('Chart: ' + t, { b: true }));
          out.push(tableXml((d || '').split(';').filter(Boolean).map(p => { const k = p.lastIndexOf(':'); return [para(p.slice(0, k)), para(p.slice(k + 1))]; })));
          continue;
        }
        const im = /^data:image\/(png|jpe?g);base64,(.+)$/is.exec(src);
        if (!im) continue;
        const ext = im[1].toLowerCase() === 'png' ? 'png' : 'jpeg', bytes = b64ToBytes(im[2]), sz = imgSize(bytes);
        const cx = sz ? Math.min(sz[0] * 9525, 5400000) : 4000000, cy = sz ? Math.round((cx * sz[1]) / sz[0]) : 3000000, n = ++mid, name = `img${n}.${ext}`;
        put('word/media/' + name, bytes);
        const id = addRel('image', 'media/' + name);
        runs.push(`<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="${cx}" cy="${cy}"/><wp:docPr id="${n}" name="Image ${n}"/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="${n}" name="${name}"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="${id}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${cx}" cy="${cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>`);
      }
      continue;
    }
    // opening tag: compute inherited format
    const f: Fmt = { ...fm[fm.length - 1] };
    if (tag === 'b' || tag === 'strong') f.b = true;
    if (tag === 'i' || tag === 'em') f.i = true;
    if (tag === 'u') f.u = true;
    if (tag === 'code' || tag === 'pre') f.code = true;
    if (tag === 'th') f.b = true;
    if (tag === 'h1') { f.b = true; f.size = 36; } else if (tag === 'h2') { f.b = true; f.size = 30; } else if (tag === 'h3') { f.b = true; f.size = 26; }
    if (tag === 'a') { const h = attr(a, 'href'); if (h) { f.link = addRel('hyperlink', h, true); f.color = '0563C1'; f.u = true; } }
    if (tag === 'font') { const c = hex(attr(a, 'color')); if (c) f.color = c; }
    const st = attr(a, 'style');
    if (st) {
      const c = hex(styleVal(st, 'color')), bg = hex(styleVal(st, 'background-color') || styleVal(st, 'background'));
      if (c) f.color = c; if (bg) f.bg = bg;
      if (/bold|[6-9]00/.test(styleVal(st, 'font-weight'))) f.b = true;
      if (/italic/.test(styleVal(st, 'font-style'))) f.i = true;
      if (/underline/.test(styleVal(st, 'text-decoration'))) f.u = true;
    }
    if (BLOCK.has(tag)) { flush(); if (tag === 'blockquote') ppr = '<w:ind w:left="720"/>'; }
    else if (tag === 'ul' || tag === 'ol') { flush(); lists.push({ ord: tag === 'ol', n: 0 }); }
    else if (tag === 'li') { flush(); const L = lists[lists.length - 1]; pre = L && L.ord ? `${++L.n}. ` : '• '; ppr = `<w:ind w:left="${360 * Math.max(1, lists.length)}" w:hanging="260"/>`; }
    else if (tag === 'table') { flush(); tbl = []; }
    else if (tag === 'tr') row = [];
    else if (tag === 'td' || tag === 'th') { flush(); cell = []; out = cell; }
    open.push(tag); fm.push(f);
  }
  flush();
  if (tbl) body.push(tableXml(tbl));

  const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
  const styleRel = `<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>`;
  const X = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
  put('[Content_Types].xml', `${X}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>`);
  put('_rels/.rels', `${X}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>`);
  put('word/_rels/document.xml.rels', `${X}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${styleRel}${rels.join('')}</Relationships>`);
  put('word/styles.xml', `${X}<w:styles xmlns:w="${W}"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults></w:styles>`);
  put('word/document.xml', `${X}<w:document ${NS}><w:body>${body.join('')}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>`);
  return zip.generateAsync({ type: 'base64', compression: 'DEFLATE' });
}
