import React, { createContext, useContext, useEffect, useRef, useState, useCallback, useMemo } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { palettes, Theme, catColors } from './theme';
import { Lang, Key, tr } from './i18n';
import type { Incoming } from './net';

export type Note = { id:string; title:string; html:string; cat:string|null; created:number; modified:number };
export type Cat = { id:string; name:string; color:string };
type State = { notes:Note[]; cats:Cat[]; lang:Lang; mode:'light'|'dark' };
const KEY = 'notia:v1';
const init: State = { notes:[], cats:[{id:'c1',name:'Personal',color:catColors[0]},{id:'c2',name:'Work',color:catColors[2]}], lang:'en', mode:'dark' };
export const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
export const plain = (h:string) => h.replace(/<[^>]*>/g,' ').replace(/&nbsp;/g,' ').replace(/\s+/g,' ').trim();

type Ctx = {
  s:State; th:Theme; t:(k:Key)=>string; ready:boolean;
  addNote:()=>string; saveNote:(id:string,p:Partial<Note>)=>void; removeNote:(id:string)=>void;
  addCat:(name:string)=>void; importNotes:(l:Incoming[])=>number; removeCat:(id:string)=>void; setLang:(l:Lang)=>void; setMode:(m:'light'|'dark')=>void;
};
const C = createContext<Ctx>(null as any);
export const useApp = () => useContext(C);

export function Provider({ children }: { children: React.ReactNode }) {
  const [s, setS] = useState<State>(init);
  const [ready, setReady] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    AsyncStorage.getItem(KEY).then(v => { if (v) setS({ ...init, ...JSON.parse(v) }); }).catch(() => {}).finally(() => setReady(true));
  }, []);
  useEffect(() => {
    if (!ready) return;
    clearTimeout(timer.current);
    timer.current = setTimeout(() => { AsyncStorage.setItem(KEY, JSON.stringify(s)).catch(() => {}); }, 400);
  }, [s, ready]);

  const addNote = useCallback(() => {
    const id = uid(), now = Date.now();
    setS(p => ({ ...p, notes:[{ id, title:'', html:'', cat:null, created:now, modified:now }, ...p.notes] }));
    return id;
  }, []);
  const saveNote = useCallback((id:string, patch:Partial<Note>) =>
    setS(p => ({ ...p, notes:p.notes.map(n => n.id === id ? { ...n, ...patch, modified:Date.now() } : n) })), []);
  const removeNote = useCallback((id:string) => setS(p => ({ ...p, notes:p.notes.filter(n => n.id !== id) })), []);
  const addCat = useCallback((name:string) => setS(p => ({ ...p, cats:[...p.cats, { id:uid(), name, color:catColors[p.cats.length % catColors.length] }] })), []);
  const removeCat = useCallback((id:string) => setS(p => ({ ...p, cats:p.cats.filter(c => c.id !== id), notes:p.notes.map(n => n.cat === id ? { ...n, cat:null } : n) })), []);
  const importNotes = useCallback((list: Incoming[]) => {
    setS(p => {
      const cats = [...p.cats], notes = [...p.notes];
      for (const n of list) {
        let cid: string | null = null;
        if (n.category) {
          let c = cats.find(x => x.name.toLowerCase() === n.category!.toLowerCase());
          if (!c) { c = { id: uid(), name: n.category, color: catColors[cats.length % catColors.length] }; cats.push(c); }
          cid = c.id;
        }
        const note: Note = { id: n.id, title: n.title, html: n.html, cat: cid, created: n.created, modified: n.modified };
        const k = notes.findIndex(x => x.id === n.id);
        if (k < 0) notes.unshift(note); else if (n.modified > notes[k].modified) notes[k] = note;
      }
      return { ...p, cats, notes };
    });
    return list.length;
  }, []);
  const setLang = useCallback((lang:Lang) => setS(p => ({ ...p, lang })), []);
  const setMode = useCallback((mode:'light'|'dark') => setS(p => ({ ...p, mode })), []);

  const value = useMemo<Ctx>(() => ({
    s, ready, th:palettes[s.mode], t:(k) => tr(s.lang, k),
    addNote, saveNote, removeNote, addCat, importNotes, removeCat, setLang, setMode,
  }), [s, ready, addNote, saveNote, removeNote, addCat, importNotes, removeCat, setLang, setMode]);
  return <C.Provider value={value}>{children}</C.Provider>;
}
