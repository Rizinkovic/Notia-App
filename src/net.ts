// Wi-Fi transfer. Protocol (also for the desktop app):
//   POST http://<ip>:8765/notia/v1/notes   header  X-Notia-Code: <4-digit code shown on the receiver>
//   body {"v":1,"notes":[{"id","title","html","category","created","modified"}]}   ->  200 {"ok":true,"count":N}
import * as Network from 'expo-network';
import { sanitize } from './tools';
import type { Note, Cat } from './store';

export const PORT = 8765;
export type Incoming = { id: string; title: string; html: string; category: string | null; created: number; modified: number };
export type Target = { host: string; port: number; code: string };

export const myIp = async () => { try { return await Network.getIpAddressAsync(); } catch { return ''; } };
export const toPayload = (notes: Note[], cats: Cat[]) => ({
  v: 1, notes: notes.map(n => ({ id: n.id, title: n.title, html: n.html, category: cats.find(c => c.id === n.cat)?.name ?? null, created: n.created, modified: n.modified })),
});
export const parseTargets = (t: string): Target[] =>
  t.split('\n').map(l => l.trim()).filter(Boolean).map(l => {
    const [addr, code = ''] = l.split(/\s+/); const [host, port] = addr.split(':');
    return { host, port: Number(port) || PORT, code };
  }).filter(x => /^[\w.\-]+$/.test(x.host));

export function cleanIncoming(raw: any): Incoming[] {
  if (!raw || raw.v !== 1 || !Array.isArray(raw.notes)) return [];
  const now = Date.now();
  return raw.notes.slice(0, 200).map((n: any): Incoming | null => {
    if (!n || typeof n !== 'object') return null;
    const id = String(n.id ?? '').replace(/[^\w\-]/g, '').slice(0, 40);
    if (!id) return null;
    const ts = (v: any) => (Number.isFinite(+v) && +v > 0 ? +v : now);
    return { id, title: String(n.title ?? '').slice(0, 200), html: sanitize(String(n.html ?? '').slice(0, 5_000_000)), category: n.category ? String(n.category).slice(0, 30) : null, created: ts(n.created), modified: ts(n.modified) };
  }).filter(Boolean) as Incoming[];
}

export async function sendTo(t: Target, payload: unknown): Promise<number> {
  const ctl = new AbortController();
  const to = setTimeout(() => ctl.abort(), 10000);
  try {
    const r = await fetch(`http://${t.host}:${t.port}/notia/v1/notes`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Notia-Code': t.code }, body: JSON.stringify(payload), signal: ctl.signal });
    return r.status;
  } catch { return 0; } finally { clearTimeout(to); }
}

const byteLen = (s: string) => { let n = 0; for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i); if (c < 0x80) n += 1; else if (c < 0x800) n += 2; else if (c >= 0xd800 && c <= 0xdbff) { n += 4; i++; } else n += 3; } return n; };
/** Receiving needs a raw TCP server => react-native-tcp-socket, which only exists in a development build (not Expo Go). */
export function startServer(code: string, onNotes: (n: Incoming[]) => number, onErr: (m: string) => void): { stop: () => void } | null {
  let Tcp: any;
  try { Tcp = require('react-native-tcp-socket').default; } catch { return null; }
  if (!Tcp || typeof Tcp.createServer !== 'function') return null;
  try {
    const server = Tcp.createServer((sock: any) => {
      let buf = '', done = false;
      const reply = (status: number, text: string, json: object) => {
        const b = JSON.stringify(json);
        sock.write(`HTTP/1.1 ${status} ${text}\r\nContent-Type: application/json\r\nContent-Length: ${byteLen(b)}\r\nConnection: close\r\n\r\n${b}`);
        sock.end?.(); done = true;
      };
      sock.setEncoding?.('utf8');
      sock.on('error', () => {});
      sock.on('data', (d: any) => {
        if (done) return;
        buf += String(d);
        if (buf.length > 20_000_000) { sock.destroy?.(); done = true; return; }
        const k = buf.indexOf('\r\n\r\n');
        if (k < 0) return;
        const head = buf.slice(0, k), body = buf.slice(k + 4);
        const cl = /content-length:\s*(\d+)/i.exec(head);
        if (byteLen(body) < (cl ? +cl[1] : 0)) return;
        if (!/^POST \/notia\/v1\/notes /.test(head)) return reply(404, 'Not Found', { ok: false });
        if ((/x-notia-code:\s*(\S*)/i.exec(head)?.[1] ?? '') !== code) return reply(401, 'Unauthorized', { ok: false });
        let list: Incoming[] = [];
        try { list = cleanIncoming(JSON.parse(body)); } catch {}
        if (!list.length) return reply(400, 'Bad Request', { ok: false });
        reply(200, 'OK', { ok: true, count: onNotes(list) });
      });
    });
    server.on('error', (e: any) => onErr(String(e?.message ?? e)));
    server.listen({ port: PORT, host: '0.0.0.0' });
    return { stop: () => { try { server.close(); } catch {} } };
  } catch (e: any) { onErr(String(e?.message ?? e)); return null; }
}
