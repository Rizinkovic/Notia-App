import React, { useMemo, useRef, useState } from 'react';
import { View, Text, TextInput, FlatList, Pressable, Alert, StyleSheet } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { useApp, plain } from '../store';
import { fmtDate } from '../tools';

export default function Notes({ navigation }: any) {
  const { s, th, t, addNote, removeNote, addCat, removeCat } = useApp();
  const ins = useSafeAreaInsets();
  const [q, setQ] = useState('');
  const [cat, setCat] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const done = useRef(true);

  const texts = useMemo(() => new Map(s.notes.map(n => [n.id, plain(n.html.replace(/<img\b[^>]*>/gi, ' '))])), [s.notes]);
  const list = useMemo(() => {
    const k = q.trim().toLowerCase();
    return s.notes
      .filter(n => (!cat || n.cat === cat) && (!k || (n.title + ' ' + (texts.get(n.id) ?? '')).toLowerCase().includes(k)))
      .sort((a, b) => b.modified - a.modified);
  }, [s.notes, texts, q, cat]);

  const open = (id: string) => navigation.navigate('Editor', { id });
  const confirmDel = (id: string) =>
    Alert.alert(t('delNote'), undefined, [{ text:t('cancel'), style:'cancel' }, { text:t('del'), style:'destructive', onPress:() => removeNote(id) }]);
  const confirmCat = (id: string) =>
    Alert.alert(t('delCat'), undefined, [{ text:t('cancel'), style:'cancel' }, { text:t('del'), style:'destructive', onPress:() => { if (cat === id) setCat(null); removeCat(id); } }]);
  const submitCat = () => { if (done.current) return; done.current = true; const n = name.trim(); if (n) addCat(n); setName(''); setAdding(false); };

  const chip = (label: string, active: boolean, onPress: () => void, onLong?: () => void, color?: string) => (
    <Pressable key={label} onPress={onPress} onLongPress={onLong}
      style={[st.chip, { backgroundColor: active ? th.accent : th.card, borderColor: th.line }]}>
      {color ? <View style={[st.dot, { backgroundColor: color }]} /> : null}
      <Text style={{ color: active ? th.accentInk : th.ink, fontWeight:'600' }}>{label}</Text>
    </Pressable>
  );

  return (
    <View style={{ flex:1, backgroundColor:th.bg, paddingTop:ins.top + 12 }}>
      <View style={{ flexDirection:'row', justifyContent:'space-between', alignItems:'center', paddingRight:20 }}>
        <Text style={[st.h1, { color:th.ink }]}>{t('notes')}</Text>
        <Pressable onPress={() => navigation.navigate('Transfer', {})} hitSlop={10} accessibilityLabel="Wi-Fi transfer"><Ionicons name="swap-horizontal" size={26} color={th.accent} /></Pressable>
      </View>
      <View style={[st.search, { backgroundColor:th.card, borderColor:th.line }]}>
        <Ionicons name="search" size={18} color={th.sub} />
        <TextInput value={q} onChangeText={setQ} placeholder={t('search')} placeholderTextColor={th.sub} style={{ flex:1, color:th.ink, paddingVertical:10 }} />
      </View>
      <View style={{ height:48 }}>
        <FlatList horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal:20, gap:8, alignItems:'center' }}
          data={[{ id:'__all' }, ...s.cats, { id:'__add' }] as any[]} keyExtractor={i => i.id}
          renderItem={({ item }) =>
            item.id === '__all' ? chip(t('all'), cat === null, () => setCat(null))
            : item.id === '__add' ? (adding
                ? <TextInput key="in" autoFocus value={name} onChangeText={setName} onSubmitEditing={submitCat} onBlur={submitCat}
                    placeholder={t('newCat')} placeholderTextColor={th.sub} style={[st.chip, { color:th.ink, borderColor:th.accent, minWidth:130 }]} />
                : <Pressable key="add" onPress={() => { done.current = false; setAdding(true); }} style={[st.chip, { borderColor:th.line }]}><Ionicons name="add" size={18} color={th.ink} /></Pressable>)
            : chip(item.name, cat === item.id, () => setCat(cat === item.id ? null : item.id), () => confirmCat(item.id), item.color)} />
      </View>
      <FlatList data={list} keyExtractor={n => n.id} contentContainerStyle={{ padding:20, paddingBottom:170, gap:12 }}
        ListEmptyComponent={<Text style={{ color:th.sub, textAlign:'center', marginTop:60 }}>{t('empty')}</Text>}
        renderItem={({ item }) => {
          const c = s.cats.find(x => x.id === item.cat);
          return (
            <Pressable onPress={() => open(item.id)} onLongPress={() => confirmDel(item.id)} style={[st.card, { backgroundColor:th.card, borderColor:th.line }]}>
              {c ? <View style={[st.bar, { backgroundColor:c.color }]} /> : null}
              <Text numberOfLines={1} style={{ color:th.ink, fontSize:17, fontWeight:'700' }}>{item.title || '—'}</Text>
              <Text numberOfLines={2} style={{ color:th.sub, marginTop:4, lineHeight:19 }}>{(texts.get(item.id) ?? '').slice(0, 200)}</Text>
              <Text style={{ color:th.sub, fontSize:12, marginTop:8 }}>{fmtDate(item.modified)}{c ? `  •  ${c.name}` : ''}</Text>
            </Pressable>
          );
        }} />
      <Pressable accessibilityLabel="New note" onPress={() => open(addNote())}
        style={[st.fab, { backgroundColor:th.accent, bottom:ins.bottom + 84 }]}>
        <Ionicons name="add" size={28} color={th.accentInk} />
      </Pressable>
    </View>
  );
}

const st = StyleSheet.create({
  h1:{ fontSize:32, fontWeight:'800', paddingHorizontal:20, marginBottom:12 },
  search:{ flexDirection:'row', alignItems:'center', gap:8, marginHorizontal:20, paddingHorizontal:14, borderRadius:16, borderWidth:1, marginBottom:12 },
  chip:{ flexDirection:'row', alignItems:'center', gap:6, paddingHorizontal:14, height:36, borderRadius:18, borderWidth:1 },
  dot:{ width:8, height:8, borderRadius:4 },
  card:{ padding:16, borderRadius:22, borderWidth:1, overflow:'hidden' },
  bar:{ position:'absolute', left:0, top:0, bottom:0, width:5 },
  fab:{ position:'absolute', right:20, width:56, height:56, borderRadius:28, alignItems:'center', justifyContent:'center', elevation:6 },
});
