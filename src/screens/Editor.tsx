import React, { useEffect, useMemo, useRef, useState } from 'react';
import { View, Text, TextInput, Pressable, Alert, ScrollView, KeyboardAvoidingView, Platform, StyleSheet } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { RichEditor, RichToolbar, actions } from 'react-native-pell-rich-editor';
import * as ImagePicker from 'expo-image-picker';
import { useApp, plain } from '../store';
import { sharePdf, shareDocx, shareText } from '../exporter';
import { InsertSheet, ShareSheet, Kind } from './EditorTools';
import { L } from '../i18n';
import { fmtDate } from '../tools';

const INK = ['#1B1633', '#E5484D', '#F2B33D', '#3FBF7F', '#3DA5E0', '#8B6CF6'];
const ICON = { link:'link', table:'grid', formula:'calculator', chart:'bar-chart' } as const;
const HL = ['#FFF3A3', '#FFD6E5', '#CDEEFF', '#D5F5E3'];

export default function Editor({ route, navigation }: any) {
  const id: string = route.params.id;
  const { s, th, t, saveNote, removeNote } = useApp();
  const ins = useSafeAreaInsets();
  const rich = useRef<RichEditor>(null);
  const note = s.notes.find(n => n.id === id);
  const first = useRef(note?.html ?? '');
  const latest = useRef(note);
  latest.current = note;

  // Drop notes the user opened but never wrote in.
  useEffect(() => () => {
    const n = latest.current;
    if (n && !n.title.trim() && !plain(n.html)) removeNote(id);
  }, [id, removeNote]);

  const [sheet, setSheet] = useState<Kind | null>(null);
  const [shareOpen, setShareOpen] = useState(false);
  const chars = useMemo(() => plain(note?.html ?? '').length, [note?.html]);
  if (!note) return null;

  const pickImage = async () => {
    try {
      const r = await ImagePicker.launchImageLibraryAsync({ mediaTypes:['images'], base64:true, quality:0.5 });
      const a = r.assets?.[0];
      if (r.canceled || !a?.base64) return;
      rich.current?.focusContentEditor();
      rich.current?.insertImage(`data:${a.mimeType ?? 'image/jpeg'};base64,${a.base64}`, 'max-width:100%;height:auto;border-radius:8px;');
    } catch (e: any) { Alert.alert(L(s.lang, 'Could not add the image', 'Impossible d’ajouter l’image'), String(e?.message ?? e)); }
  };
  const insert = (html: string) => { rich.current?.focusContentEditor(); rich.current?.insertHTML(html); setSheet(null); };
  const items = [
    { icon: 'document-outline' as const, label: L(s.lang, 'Export as PDF', 'Exporter en PDF'), run: () => sharePdf(note) },
    { icon: 'document-text-outline' as const, label: L(s.lang, 'Export as Word (.docx)', 'Exporter en Word (.docx)'), run: () => shareDocx(note) },
    { icon: 'chatbubble-outline' as const, label: t('shareText'), run: () => shareText(note) },
    { icon: 'wifi' as const, label: L(s.lang, 'Send over Wi-Fi', 'Envoyer par Wi-Fi'), run: () => navigation.navigate('Transfer', { ids: [id] }) },
  ];
  const stamp = (n:number) => fmtDate(n, true);

  return (
    <KeyboardAvoidingView style={{ flex:1, backgroundColor:th.bg, paddingTop:ins.top }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <View style={st.top}>
        <Pressable onPress={() => navigation.goBack()} hitSlop={12}><Ionicons name="chevron-back" size={26} color={th.ink} /></Pressable>
        <View style={{ flexDirection:'row', gap:18 }}>
          <Pressable onPress={() => setShareOpen(true)} hitSlop={12}><Ionicons name="share-outline" size={24} color={th.ink} /></Pressable>
          <Pressable onPress={() => { Alert.alert(t('delNote'), undefined, [{ text:t('cancel'), style:'cancel' }, { text:t('del'), style:'destructive', onPress:() => { navigation.goBack(); setTimeout(() => removeNote(id), 50); } }]); }} hitSlop={12}>
            <Ionicons name="trash-outline" size={24} color={th.ink} />
          </Pressable>
        </View>
      </View>

      <TextInput value={note.title} onChangeText={v => saveNote(id, { title:v })} placeholder={t('title')} placeholderTextColor={th.sub}
        style={{ color:th.ink, fontSize:26, fontWeight:'800', paddingHorizontal:20 }} />
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal:20, gap:8, paddingVertical:10 }} style={{ flexGrow:0 }}>
        {[{ id:null, name:t('noCat'), color:th.sub }, ...s.cats].map(c => (
          <Pressable key={String(c.id)} onPress={() => saveNote(id, { cat:c.id as string | null })}
            style={[st.chip, { borderColor:th.line, backgroundColor:note.cat === c.id ? th.accent : th.card }]}>
            <View style={[st.dot, { backgroundColor:c.color }]} />
            <Text style={{ color:note.cat === c.id ? th.accentInk : th.ink, fontWeight:'600' }}>{c.name}</Text>
          </Pressable>
        ))}
      </ScrollView>

      <ScrollView style={{ flex:1 }} keyboardShouldPersistTaps="handled" contentContainerStyle={{ paddingHorizontal:12 }}>
        <RichEditor ref={rich} initialContentHTML={first.current} placeholder={t('body')} useContainer={true}
          onChange={html => { if (html !== latest.current?.html) saveNote(id, { html }); }} initialHeight={320}
          editorStyle={{ backgroundColor:th.bg, color:th.ink, placeholderColor:th.sub, caretColor:th.accent,
            contentCSSText:'font-size:17px;line-height:1.55;padding:4px 8px;' }} />
      </ScrollView>

      <Text style={[st.meta, { color:th.sub }]}>{chars} {t('chars')}  •  {t('created')} {stamp(note.created)}  •  {t('modified')} {stamp(note.modified)}</Text>

      <View style={{ backgroundColor:th.card, borderTopWidth:1, borderColor:th.line, paddingBottom:ins.bottom }}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ alignItems:'center', paddingHorizontal:12, gap:10, height:34 }}>
          <Pressable onPress={pickImage} hitSlop={6} accessibilityLabel="Add image"><Ionicons name="image" size={22} color={th.accent} /></Pressable>
          {(['link', 'table', 'formula', 'chart'] as const).map(k => (
            <Pressable key={k} onPress={() => setSheet(k)} hitSlop={6}><Ionicons name={ICON[k]} size={22} color={th.accent} /></Pressable>
          ))}
          <View style={{ width:1, height:20, backgroundColor:th.line }} />
          {INK.map(c => <Pressable key={c} onPress={() => rich.current?.setForeColor(c)} style={[st.swatch, { backgroundColor:c, borderColor:th.line }]} />)}
          <View style={{ width:1, height:20, backgroundColor:th.line }} />
          {HL.map(c => <Pressable key={c} onPress={() => rich.current?.setHiliteColor(c)} style={[st.swatch, { backgroundColor:c, borderColor:th.line, borderRadius:6 }]} />)}
        </ScrollView>
        <RichToolbar editor={rich} style={{ backgroundColor:th.card }} iconTint={th.sub} selectedIconTint={th.accent}
          actions={[actions.undo, actions.redo, actions.heading1, actions.heading2, actions.setBold, actions.setItalic, actions.setUnderline,
            actions.insertBulletsList, actions.insertOrderedList, actions.code, actions.insertLine, actions.insertImage]}
          onPressAddImage={pickImage}
          iconMap={{
            [actions.heading1]: ({ tintColor }: any) => <Text style={{ color:tintColor, fontWeight:'800' }}>H1</Text>,
            [actions.heading2]: ({ tintColor }: any) => <Text style={{ color:tintColor, fontWeight:'800' }}>H2</Text>,
          }} />
      </View>
      <InsertSheet key={sheet ?? 'none'} kind={sheet} onClose={() => setSheet(null)} onInsert={insert} />
      <ShareSheet open={shareOpen} onClose={() => setShareOpen(false)} items={items} />
    </KeyboardAvoidingView>
  );
}

const st = StyleSheet.create({
  top:{ flexDirection:'row', justifyContent:'space-between', alignItems:'center', paddingHorizontal:16, height:48 },
  chip:{ flexDirection:'row', alignItems:'center', gap:6, paddingHorizontal:12, height:32, borderRadius:16, borderWidth:1 },
  dot:{ width:8, height:8, borderRadius:4 },
  swatch:{ width:24, height:24, borderRadius:12, borderWidth:1 },
  meta:{ fontSize:11, paddingHorizontal:20, paddingVertical:6 },
});
