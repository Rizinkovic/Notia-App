// Pure helpers: HTML escaping/sanitising, formula evaluator, table + chart builders.
export const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

export const sanitize = (h: string) =>
  h.replace(/<\s*(script|iframe|object|embed|style|link|meta)\b[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gi, '')
    .replace(/<\s*(script|iframe|object|embed|style|link|meta)\b[^>]*>/gi, '')
    .replace(/\son\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '')
    .replace(/(href|src)\s*=\s*(["'])\s*(javascript|vbscript):[^"']*\2/gi, '$1=$2$2')
    .replace(/(href|src)\s*=\s*(javascript|vbscript):[^\s>]*/gi, '$1=""');

/** Safe arithmetic evaluator (no eval): + - * / % ^ ( ) sqrt sin cos tan log ln abs round pi e */
export function evalMath(src: string): number {
  const s = src.replace(/\s+/g, '').toLowerCase().replace(/×/g, '*').replace(/÷/g, '/').replace(/,/g, '.');
  let i = 0;
  const fn: Record<string, (x: number) => number> = { sqrt: Math.sqrt, sin: Math.sin, cos: Math.cos, tan: Math.tan, log: Math.log10, ln: Math.log, abs: Math.abs, round: Math.round };
  const expr = (): number => { let v = term(); while (s[i] === '+' || s[i] === '-') { const o = s[i++]; const r = term(); v = o === '+' ? v + r : v - r; } return v; };
  const term = (): number => { let v = unary(); while (s[i] === '*' || s[i] === '/' || s[i] === '%') { const o = s[i++]; const r = unary(); v = o === '*' ? v * r : o === '/' ? v / r : v % r; } return v; };
  const unary = (): number => { if (s[i] === '-') { i++; return -unary(); } if (s[i] === '+') { i++; return unary(); } return pow(); };
  const pow = (): number => { const b = atom(); if (s[i] === '^') { i++; return Math.pow(b, unary()); } return b; };
  const close = () => { if (s[i++] !== ')') throw new Error('Missing )'); };
  const atom = (): number => {
    if (s[i] === '(') { i++; const v = expr(); close(); return v; }
    const rest = s.slice(i);
    const n = /^(\d+\.?\d*|\.\d+)/.exec(rest);
    if (n) { i += n[0].length; return parseFloat(n[0]); }
    const w = /^[a-z]+/.exec(rest);
    if (w) {
      i += w[0].length;
      if (w[0] === 'pi') return Math.PI;
      if (w[0] === 'e') return Math.E;
      const f = fn[w[0]];
      if (!f || s[i] !== '(') throw new Error('Unknown: ' + w[0]);
      i++; const v = expr(); close(); return f(v);
    }
    throw new Error(s[i] ? 'Unexpected ' + s[i] : 'Incomplete');
  };
  const v = expr();
  if (i < s.length) throw new Error('Unexpected ' + s[i]);
  if (!isFinite(v)) throw new Error('Not a number');
  return v;
}
export const fmtNum = (v: number) => String(Math.round(v * 1e10) / 1e10);

export const tableHtml = (rows: number, cols: number) => {
  const td = (b: boolean) => `<td style="border:1px solid #888;padding:6px;min-width:48px">${b ? '<b>&nbsp;</b>' : '&nbsp;'}</td>`;
  const tr = (b: boolean) => `<tr>${Array.from({ length: cols }, () => td(b)).join('')}</tr>`;
  return `<table style="border-collapse:collapse;width:100%"><tbody>${Array.from({ length: rows }, (_, r) => tr(r === 0)).join('')}</tbody></table><div><br></div>`;
};

export type Pt = { label: string; value: number };
export const parsePts = (t: string): Pt[] =>
  t.split('\n').map(l => {
    const m = /^(.*?)[:=;\t]\s*(-?\d+(?:[.,]\d+)?)\s*$/.exec(l.trim());
    return m ? { label: (m[1].trim() || '-').replace(/[:;|"<>&]/g, ' ').slice(0, 14), value: Math.max(0, parseFloat(m[2].replace(',', '.'))) } : null;
  }).filter(Boolean) as Pt[];

const PAL = ['#8B6CF6', '#F2B33D', '#3DA5E0', '#EF6B9A', '#3FBF7F', '#E5484D'];
export function chartImg(type: 'bar' | 'line' | 'pie', title: string, pts: Pt[]): string {
  const W = 320, H = 220, x0 = 30, x1 = 310, y0 = 180, hh = 130;
  const T = (x: number, y: number, t: string, o = '') => `<text x="${x}" y="${y}" font-size="10" fill="#333" font-family="sans-serif" ${o}>${esc(t)}</text>`;
  let g = `<rect width="${W}" height="${H}" rx="12" fill="#fff"/>` + T(W / 2, 22, title, 'text-anchor="middle" font-size="13" font-weight="bold"');
  const max = Math.max(...pts.map(p => p.value), 1), bw = (x1 - x0) / pts.length;
  if (type === 'pie') {
    const tot = pts.reduce((a, p) => a + p.value, 0) || 1, cx = 90, cy = 120, r = 70;
    let a0 = -Math.PI / 2;
    pts.forEach((p, i) => {
      const a1 = a0 + (p.value / tot) * 2 * Math.PI, c = PAL[i % PAL.length];
      if (p.value / tot > 0.9999) g += `<circle cx="${cx}" cy="${cy}" r="${r}" fill="${c}"/>`;
      else if (p.value > 0) g += `<path d="M${cx} ${cy}L${cx + r * Math.cos(a0)} ${cy + r * Math.sin(a0)}A${r} ${r} 0 ${a1 - a0 > Math.PI ? 1 : 0} 1 ${cx + r * Math.cos(a1)} ${cy + r * Math.sin(a1)}Z" fill="${c}"/>`;
      g += `<rect x="185" y="${50 + i * 18}" width="10" height="10" fill="${c}"/>` + T(200, 59 + i * 18, `${p.label} (${fmtNum(p.value)})`);
      a0 = a1;
    });
  } else {
    g += `<line x1="${x0}" y1="${y0}" x2="${x1}" y2="${y0}" stroke="#999"/>`;
    const px = (i: number) => x0 + (i + 0.5) * bw, py = (v: number) => y0 - (v / max) * hh;
    if (type === 'bar') pts.forEach((p, i) => { g += `<rect x="${px(i) - bw * 0.35}" y="${py(p.value)}" width="${bw * 0.7}" height="${(p.value / max) * hh}" fill="${PAL[i % PAL.length]}" rx="3"/>`; });
    else g += `<polyline fill="none" stroke="#8B6CF6" stroke-width="2.5" points="${pts.map((p, i) => `${px(i)},${py(p.value)}`).join(' ')}"/>` + pts.map((p, i) => `<circle cx="${px(i)}" cy="${py(p.value)}" r="3.5" fill="#8B6CF6"/>`).join('');
    pts.forEach((p, i) => { g += T(px(i), y0 + 13, p.label.slice(0, Math.max(3, Math.floor(bw / 6))), 'text-anchor="middle"') + T(px(i), py(p.value) - 5, fmtNum(p.value), 'text-anchor="middle"'); });
  }
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">${g}</svg>`;
  const alt = `chart|${esc(title.replace(/[|;]/g, ' '))}|${pts.map(p => `${p.label}:${p.value}`).join(';')}`;
  return `<img alt="${alt}" src="data:image/svg+xml;utf8,${encodeURIComponent(svg)}" style="max-width:100%"/><div><br></div>`;
}

const p2 = (n: number) => String(n).padStart(2, '0');
/** DD/MM/YY (optionally with HH:mm) */
export const fmtDate = (ts: number, time = false) => {
  const d = new Date(ts);
  return `${p2(d.getDate())}/${p2(d.getMonth() + 1)}/${p2(d.getFullYear() % 100)}` + (time ? ` ${p2(d.getHours())}:${p2(d.getMinutes())}` : '');
};
