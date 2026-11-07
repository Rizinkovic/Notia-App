import React, { useMemo, useState } from 'react';
import { View, Text, TextInput, Pressable, Modal, KeyboardAvoidingView, Platform, ActivityIndicator, Alert, StyleSheet } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useApp } from '../store';
import { L } from '../i18n';
import { esc, evalMath, fmtNum, tableHtml, chartImg, parsePts } from '../tools';

export type Kind = 'link' | 'table' | 'formula' | 'chart';

function Sheet({ open, onClose, children }: { open: boolean; onClose: () => void; children: React.ReactNode }) {
  const { th } = useApp();
  return (
    <Modal visible={open} transparent animationType="slide" onRequestClose={onClose}>
      <KeyboardAvoidingView style={{ flex: 1, justifyContent: 'flex-end' }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable style={StyleSheet.absoluteFill} onPress={onClose} accessibilityLabel="Close" />
        <View style={{ backgroundColor: th.card, padding: 20, paddingBottom: 28, borderTopLeftRadius: 28, borderTopRightRadius: 28, gap: 10, borderWidth: 1, borderColor: th.line }}>{children}</View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

function Stepper({ label, v, set, min, max }: { label: string; v: number; set: (n: number) => void; min: number; max: number }) {
  const { th } = useApp();
  return (
    <View style={{ flex: 1, gap: 6 }}>
      <Text style={{ color: th.sub }}>{label}</Text>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 14 }}>
        <Pressable onPress={() => set(Math.max(min, v - 1))} hitSlop={8}><Ionicons name="remove-circle-outline" size={30} color={th.accent} /></Pressable>
        <Text style={{ color: th.ink, fontSize: 20, fontWeight: '800', minWidth: 24, textAlign: 'center' }}>{v}</Text>
        <Pressable onPress={() => set(Math.min(max, v + 1))} hitSlop={8}><Ionicons name="add-circle-outline" size={30} color={th.accent} /></Pressable>
      </View>
    </View>
  );
}

export function InsertSheet({ kind, onClose, onInsert }: { kind: Kind | null; onClose: () => void; onInsert: (html: string) => void }) {
  const { s, th } = useApp();
  const l = s.lang;
  const [text, setText] = useState('');
  const [url, setUrl] = useState('');
  const [rows, setRows] = useState(3);
  const [cols, setCols] = useState(3);
  const [expr, setExpr] = useState('');
  const [ctype, setCtype] = useState<'bar' | 'line' | 'pie'>('bar');
  const [ctitle, setCtitle] = useState('');
  const [data, setData] = useState('Jan: 12\nFeb: 19\nMar: 8');

  const calc = useMemo(() => {
    if (!expr.trim()) return null;
    try { return { v: fmtNum(evalMath(expr)) }; } catch (e: any) { return { e: String(e.message) }; }
  }, [expr]);
  const pts = useMemo(() => parsePts(data), [data]);

  const input = { borderWidth: 1, borderColor: th.line, borderRadius: 14, paddingHorizontal: 14, paddingVertical: 11, color: th.ink } as const;
  const ok = () => {
    if (kind === 'link') {
      let u = url.trim();
      if (!u) return;
      if (!/^[a-z][a-z0-9+.-]*:/i.test(u)) u = 'https://' + u;
      if (/^(javascript|data|vbscript):/i.test(u)) return Alert.alert(L(l, 'Invalid link', 'Lien invalide'));
      onInsert(`<a href="${esc(u)}">${esc(text.trim() || url.trim())}</a>&nbsp;`);
    } else if (kind === 'table') onInsert(tableHtml(rows, cols));
    else if (kind === 'formula') { if (calc && 'v' in calc) onInsert(`<code>${esc(expr.trim())} = ${calc.v}</code>&nbsp;`); }
    else if (kind === 'chart') {
      if (!pts.length) return Alert.alert(L(l, 'No data', 'Aucune donnée'), L(l, 'Write one "Label: value" per line.', 'Écrivez une ligne « Libellé : valeur » par donnée.'));
      onInsert(chartImg(ctype, ctitle.trim() || L(l, 'Chart', 'Graphique'), pts));
    }
  };
  const titles: Record<Kind, string> = { link: L(l, 'Insert link', 'Insérer un lien'), table: L(l, 'Insert table', 'Insérer un tableau'), formula: L(l, 'Insert formula', 'Insérer une formule'), chart: L(l, 'Insert chart', 'Insérer un graphique') };

  return (
    <Sheet open={!!kind} onClose={onClose}>
      <Text style={{ color: th.ink, fontSize: 20, fontWeight: '800' }}>{kind ? titles[kind] : ''}</Text>
      {kind === 'link' ? (<>
        <TextInput value={text} onChangeText={setText} placeholder={L(l, 'Text to show', 'Texte affiché')} placeholderTextColor={th.sub} style={input} />
        <TextInput value={url} onChangeText={setUrl} placeholder="https://" placeholderTextColor={th.sub} autoCapitalize="none" keyboardType="url" autoCorrect={false} style={input} />
      </>) : null}
      {kind === 'table' ? (
        <View style={{ flexDirection: 'row', gap: 20 }}>
          <Stepper label={L(l, 'Rows', 'Lignes')} v={rows} set={setRows} min={1} max={20} />
          <Stepper label={L(l, 'Columns', 'Colonnes')} v={cols} set={setCols} min={1} max={6} />
        </View>
      ) : null}
      {kind === 'formula' ? (<>
        <TextInput value={expr} onChangeText={setExpr} placeholder="(12 + 8) * 3 / sqrt(16)" placeholderTextColor={th.sub} autoCapitalize="none" autoCorrect={false} style={input} />
        <Text style={{ color: calc && 'e' in calc ? '#E5484D' : th.accent, fontWeight: '700', minHeight: 20 }}>{calc ? ('v' in calc ? `= ${calc.v}` : calc.e) : ''}</Text>
        <Text style={{ color: th.sub, fontSize: 12 }}>+ − × ÷ % ^ ( ) sqrt sin cos tan log ln abs round pi e</Text>
      </>) : null}
      {kind === 'chart' ? (<>
        <View style={{ flexDirection: 'row', gap: 8 }}>
          {(['bar', 'line', 'pie'] as const).map(c => (
            <Pressable key={c} onPress={() => setCtype(c)} style={{ paddingHorizontal: 16, height: 36, borderRadius: 18, borderWidth: 1, borderColor: th.line, justifyContent: 'center', backgroundColor: ctype === c ? th.accent : 'transparent' }}>
              <Text style={{ color: ctype === c ? th.accentInk : th.ink, fontWeight: '700' }}>{c === 'bar' ? L(l, 'Bar', 'Barres') : c === 'line' ? L(l, 'Line', 'Ligne') : L(l, 'Pie', 'Camembert')}</Text>
            </Pressable>
          ))}
        </View>
        <TextInput value={ctitle} onChangeText={setCtitle} placeholder={L(l, 'Title', 'Titre')} placeholderTextColor={th.sub} style={input} />
        <TextInput value={data} onChangeText={setData} multiline placeholder="Label: value" placeholderTextColor={th.sub} autoCapitalize="none" style={[input, { minHeight: 90, textAlignVertical: 'top' }]} />
        <Text style={{ color: th.sub, fontSize: 12 }}>{pts.length} {L(l, 'data points', 'données')}</Text>
      </>) : null}
      <View style={{ flexDirection: 'row', gap: 10, marginTop: 4 }}>
        <Pressable onPress={ok} style={{ flex: 1, height: 46, borderRadius: 23, backgroundColor: th.accent, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: th.accentInk, fontWeight: '800' }}>{L(l, 'Insert', 'Insérer')}</Text></Pressable>
        <Pressable onPress={onClose} style={{ paddingHorizontal: 20, height: 46, borderRadius: 23, borderWidth: 1, borderColor: th.line, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: th.ink }}>{L(l, 'Cancel', 'Annuler')}</Text></Pressable>
      </View>
    </Sheet>
  );
}

export type ShareItem = { icon: keyof typeof Ionicons.glyphMap; label: string; run: () => Promise<unknown> | void };
export function ShareSheet({ open, onClose, items }: { open: boolean; onClose: () => void; items: ShareItem[] }) {
  const { s, th } = useApp();
  const [busy, setBusy] = useState(false);
  const go = async (it: ShareItem) => {
    if (busy) return;
    setBusy(true);
    try { await it.run(); } catch (e: any) { Alert.alert(L(s.lang, 'Export failed', 'Échec de l’export'), String(e?.message ?? e)); } finally { setBusy(false); onClose(); }
  };
  return (
    <Sheet open={open} onClose={() => !busy && onClose()}>
      <Text style={{ color: th.ink, fontSize: 20, fontWeight: '800' }}>{L(s.lang, 'Share or export', 'Partager ou exporter')}</Text>
      {items.map(it => (
        <Pressable key={it.label} onPress={() => go(it)} style={{ flexDirection: 'row', alignItems: 'center', gap: 14, padding: 14, borderRadius: 16, borderWidth: 1, borderColor: th.line }}>
          <Ionicons name={it.icon} size={22} color={th.accent} /><Text style={{ color: th.ink, fontWeight: '600', flex: 1 }}>{it.label}</Text>
        </Pressable>
      ))}
      {busy ? <ActivityIndicator color={th.accent} /> : null}
    </Sheet>
  );
}
