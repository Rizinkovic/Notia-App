import React, { createContext, useContext, useEffect, useRef, useState, useCallback, useMemo } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as N from 'expo-notifications';
import { Platform } from 'react-native';
import * as FS from 'expo-file-system/legacy';
import { uid } from './store';

export type Mark = { id: string; pct: number; page: number; text?: string };
export type Kind = 'pdf' | 'epub' | 'docx' | 'mobi' | 'txt' | 'html';
export type Book = { id: string; title: string; kind: Kind; uri: string; cover?: string; cg?: boolean; pct: number; page: number; total: number; time: number; last: number; bm: Mark[]; hl: Mark[] };
export const MAX_BOOKS = 10;
/** Books are stored by file name only; the absolute path is rebuilt each run (the app folder path can change). */
export const bookPath = (uri: string) => (FS.documentDirectory ?? '') + (uri.split('/').pop() ?? '');
const KEY = 'notia:lib', RKEY = 'notia:lib.remind';

type Ctx = { books: Book[]; ready: boolean; purged: number; ackPurged: () => void; add: (b: Omit<Book, 'cover' | 'cg' | 'id' | 'pct' | 'page' | 'total' | 'time' | 'last' | 'bm' | 'hl'>) => string; patch: (id: string, p: Partial<Book> | ((b: Book) => Partial<Book>)) => void; remove: (id: string) => void };
const C = createContext<Ctx>(null as any);
export const useLib = () => useContext(C);
export const fmtTime = (s: number) => { const m = Math.floor(s / 60); return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`; };

export function LibProvider({ children, lang }: { children: React.ReactNode; lang: 'en' | 'fr' }) {
  const [books, setBooks] = useState<Book[]>([]);
  const [ready, setReady] = useState(false);
  const [purged, setPurged] = useState(0);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    (async () => {
      try {
        const v = await AsyncStorage.getItem(KEY);
        const list: Book[] = v ? JSON.parse(v) : [];
        const ok = await Promise.all(list.map(b => FS.getInfoAsync(bookPath(b.uri)).then(i => i.exists).catch(() => false)));
        setBooks(list.filter((_, i) => ok[i]));
        setPurged(ok.filter(x => !x).length);
      } catch {} finally { setReady(true); }
    })();
  }, []);
  useEffect(() => {
    if (!ready) return;
    clearTimeout(timer.current);
    timer.current = setTimeout(() => AsyncStorage.setItem(KEY, JSON.stringify(books)).catch(() => {}), 500);
  }, [books, ready]);

  // Daily 7pm reminder to finish the most-read unfinished book.
  const topTitle = useMemo(() => books.filter(b => b.pct < 0.97 && b.time > 0).sort((a, b) => b.time - a.time)[0]?.title ?? '', [books]);
  useEffect(() => {
    if (!ready) return;
    (async () => {
      try {
        const old = await AsyncStorage.getItem(RKEY);
        if (old) await N.cancelScheduledNotificationAsync(old).catch(() => {});
        await AsyncStorage.removeItem(RKEY);
        if (!topTitle) return;
        if (Platform.OS === 'android') await N.setNotificationChannelAsync('reading', { name:'Reading', importance:N.AndroidImportance.DEFAULT });
        let p = await N.getPermissionsAsync();
        if (!p.granted) p = await N.requestPermissionsAsync();
        if (!p.granted) return;
        const id = await N.scheduleNotificationAsync({
          content:{ title:'Notia', body: lang === 'fr' ? `Terminez « ${topTitle} » ce soir ?` : `Finish “${topTitle}” tonight?` },
          trigger:{ type:N.SchedulableTriggerInputTypes.DAILY, hour:19, minute:0, channelId:'reading' } as any,
        });
        await AsyncStorage.setItem(RKEY, id);
      } catch {}
    })();
  }, [topTitle, ready, lang]);

  const add = useCallback<Ctx['add']>(b => {
    const id = uid();
    setBooks(p => [{ ...b, id, pct:0, page:0, total:0, time:0, last:Date.now(), bm:[], hl:[] }, ...p]);
    return id;
  }, []);
  const patch = useCallback<Ctx['patch']>((id, p) => setBooks(bs => bs.map(b => b.id === id ? { ...b, ...(typeof p === 'function' ? p(b) : p) } : b)), []);
  const remove = useCallback((id: string) => setBooks(p => p.filter(b => b.id !== id)), []);
  const ackPurged = useCallback(() => setPurged(0), []);
  const value = useMemo(() => ({ books, ready, purged, ackPurged, add, patch, remove }), [books, ready, purged, ackPurged, add, patch, remove]);
  return <C.Provider value={value}>{children}</C.Provider>;
}

/* ---------------- importing a book file ---------------- */
const EXT: Record<string, Kind> = { pdf: 'pdf', epub: 'epub', docx: 'docx', mobi: 'mobi', azw: 'mobi', azw3: 'mobi', prc: 'mobi', txt: 'txt', text: 'txt', md: 'txt', html: 'html', htm: 'html', xhtml: 'html' };
const MIME: Record<string, Kind> = { 'application/pdf': 'pdf', 'application/epub+zip': 'epub', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'docx', 'application/x-mobipocket-ebook': 'mobi', 'application/vnd.amazon.ebook': 'mobi', 'text/plain': 'txt', 'text/html': 'html' };
export const NOT_SUPPORTED = ['fb2', 'cbz', 'cbr', 'djvu', 'doc', 'rtf', 'odt', 'lit', 'ibooks'];
export const MAX_BOOK_BYTES = 60 * 1024 * 1024;
export class ImportError extends Error { constructor(public code: 'unsupported' | 'drm' | 'invalid' | 'big' | 'copy', msg: string) { super(msg); } }

const msg = (e: unknown) => String((e as any)?.message ?? e);
const decode = (u: string) => { try { return decodeURI(u); } catch { return u; } };
const fileSize = async (u: string) => { const i: any = await FS.getInfoAsync(u); return i?.exists ? Number(i.size ?? 0) : -1; };

/** Copies the picked file into the app folder. Tries several strategies, because content:// and cache URIs behave differently across devices. */
/** Reads a file through React Native's own fetch (works for file:// and content:// even when Expo's file system refuses with "isn't readable"). */
async function viaFetch(from: string, to: string) {
  const blob = await (await fetch(from)).blob();
  const b64: string = await new Promise((res, rej) => {
    const fr = new FileReader();
    fr.onerror = () => rej(fr.error ?? new Error('FileReader failed'));
    fr.onloadend = () => res(String(fr.result ?? '').split(',')[1] ?? '');
    fr.readAsDataURL(blob);
  });
  if (!b64) throw new Error('fetch returned no data');
  await FS.writeAsStringAsync(to, b64, { encoding: FS.EncodingType.Base64 });
}

/** Copies the picked file into the app folder. Tries several strategies, because content:// and cache URIs behave differently across devices and in Expo Go. */
async function copyIn(from: string, to: string) {
  const errs: string[] = [];
  const tries: [string, () => Promise<unknown>][] = [
    ['copy', () => FS.copyAsync({ from, to })],
    ['fetch', () => viaFetch(from, to)],
    ['copy2', () => FS.copyAsync({ from: decode(from), to })],
    ['read', async () => { const b = await FS.readAsStringAsync(from, { encoding: FS.EncodingType.Base64 }); await FS.writeAsStringAsync(to, b, { encoding: FS.EncodingType.Base64 }); }],
    ['file', async () => { const { File } = require('expo-file-system'); await new File(from).copy(new File(to)); }],
  ];
  for (const [name, t] of tries) {
    try { await t(); if ((await fileSize(to)) > 0) return; errs.push(name + ': empty copy'); } catch (e) { errs.push(name + ': ' + msg(e).replace(/\s+/g, ' ').slice(0, 90)); }
    await FS.deleteAsync(to, { idempotent: true }).catch(() => {});
  }
  throw new ImportError('copy', errs.join('\n'));
}
const head = async (p: string, position: number, length: number) => {
  try { return atob(await FS.readAsStringAsync(p, { encoding: FS.EncodingType.Base64, position, length })); } catch { return ''; }
};

export async function importBook(a: { uri: string; name?: string | null; mimeType?: string | null; size?: number | null }): Promise<{ title: string; kind: Kind; uri: string }> {
  const name = a.name || decode(a.uri.split('/').pop() || 'book');
  const ext = (name.includes('.') ? name.split('.').pop()! : '').toLowerCase();
  if (NOT_SUPPORTED.includes(ext)) throw new ImportError('unsupported', ext);
  if (a.size && a.size > MAX_BOOK_BYTES) throw new ImportError('big', String(a.size));
  let kind: Kind | undefined = EXT[ext] ?? (a.mimeType ? MIME[a.mimeType] : undefined);
  const dir = FS.documentDirectory ?? FS.cacheDirectory ?? '';
  const file = `${Date.now()}.${kind ?? 'bin'}`;
  const to = dir + file;
  await copyIn(a.uri, to);
  try {
    if ((await fileSize(to)) > MAX_BOOK_BYTES) throw new ImportError('big', 'size');
    const h = await head(to, 0, 1024);
    const pdf = h.includes('%PDF'), zip = h.startsWith('PK');
    const mobi = (await head(to, 60, 8)) === 'BOOKMOBI' || (await head(to, 60, 8)) === 'TEXtREAd';
    if (!kind) kind = pdf ? 'pdf' : mobi ? 'mobi' : zip ? 'epub' : undefined;
    if (!kind) throw new ImportError('unsupported', ext || 'unknown');
    if ((kind === 'pdf' && !pdf) || ((kind === 'epub' || kind === 'docx') && !zip) || (kind === 'mobi' && !mobi))
      throw new ImportError('invalid', kind);
    // a file stored under a generic name must keep its real extension so the reader gets the right kind
    const title = name.replace(/\.[^.]+$/, '') || name;
    if (!file.endsWith('.' + kind)) { const final = `${Date.now()}.${kind}`; await FS.moveAsync({ from: to, to: dir + final }); return { title, kind, uri: final }; }
    return { title, kind, uri: file };
  } catch (e) {
    await FS.deleteAsync(to, { idempotent: true }).catch(() => {});
    throw e;
  }
}
