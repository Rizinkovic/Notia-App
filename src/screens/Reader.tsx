import React, { useEffect, useMemo, useRef, useState } from 'react';
import { View, Text, Pressable, Modal, FlatList, ActivityIndicator, AppState, Alert, Linking, StyleSheet } from 'react-native';
import { WebView } from 'react-native-webview';
import * as FS from 'expo-file-system/legacy';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { useApp, uid } from '../store';
import { useLib, fmtTime, bookPath } from '../library';
import { readerHtml } from '../readerHtml';
import { L } from '../i18n';

export default function Reader({ route, navigation }: any) {
  const id: string = route.params.id;
  const { s, th } = useApp();
  const l = s.lang;
  const { books, patch } = useLib();
  const ins = useSafeAreaInsets();
  const book = books.find(b => b.id === id);
  const web = useRef<any>(null);
  const cur = useRef({ pct:book?.pct ?? 0, page:book?.page ?? 0 });
  const startPct = useRef(book?.pct ?? 0);
  const sent = useRef(false);
  const [loading, setLoading] = useState(true);
  const [panel, setPanel] = useState(false);
  const html = useMemo(() => readerHtml(th), [th]);
  const loaded = useRef(false);
  const fail = (title: string, msg: string) => { setLoading(false); Alert.alert(title, msg); };

  // Reading time only counts while the book is open on screen and the app is in the foreground.
  useEffect(() => {
    const t = setInterval(() => { if (loaded.current && AppState.currentState === 'active') patch(id, b => ({ time:b.time + 5, last:Date.now() })); }, 5000);
    return () => clearInterval(t);
  }, [id, patch]);
  // If the page never reports back (old WebView, crash), stop the spinner and say so.
  useEffect(() => {
    const t = setTimeout(() => { if (!loaded.current) fail(L(l, 'Taking too long', 'Trop long'), L(l, 'The book did not open. Update “Android System WebView” in the Play Store, or try a smaller file.', 'Le livre ne s’est pas ouvert. Mettez à jour « Android System WebView » dans le Play Store, ou essayez un fichier plus petit.')); }, 90000);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!book) return null;

  const onMessage = async (e: any) => {
    let m: any;
    try { m = JSON.parse(e.nativeEvent.data); } catch { return; }
    if (m.type === 'ready') {
      if (sent.current) return;
      sent.current = true;
      try {
        const b64 = await FS.readAsStringAsync(bookPath(book.uri), { encoding:FS.EncodingType.Base64 });
        if (!b64) throw new Error('empty file');
        const CH = 250000;
        for (let i = 0; i < b64.length; i += CH) web.current?.injectJavaScript(`cp(${JSON.stringify(b64.slice(i, i + CH))});true;`);
        web.current?.injectJavaScript(`window.load(${JSON.stringify(book.kind)},${startPct.current},${JSON.stringify(book.title)});true;`);
      } catch (e: any) { fail(L(l, 'Book not found', 'Livre introuvable'), L(l, 'Remove it from the library and add it again.', 'Retirez-le de la bibliothèque et ajoutez-le de nouveau.') + '\n\n' + String(e?.message ?? e)); }
    } else if (m.type === 'loaded') {
      loaded.current = true; setLoading(false);
      if (book.hl.length) web.current?.injectJavaScript(`window.applyHl(${JSON.stringify(book.hl.map(h => h.text).filter(Boolean))});true;`);
    } else if (m.type === 'err') fail(L(l, 'Could not open the book', 'Impossible d’ouvrir le livre'), String(m.msg));
    else if (m.type === 'link' && typeof m.url === 'string' && /^(https?:|mailto:)/i.test(m.url)) Linking.openURL(m.url).catch(() => {});
    else if (m.type === 'p') { cur.current = { pct:m.pct, page:m.page }; patch(id, { pct:m.pct, page:m.page, total:m.total }); }
    else if (m.type === 'cover' && typeof m.data === 'string' && m.data.startsWith('data:image/')) patch(id, b => (b.cover && (!b.cg || m.gen) ? {} : { cover:m.data, cg:!!m.gen }));
    else if (m.type === 'hl' && m.text) patch(id, b => ({ hl:[{ id:uid(), text:String(m.text).slice(0, 300), ...cur.current }, ...b.hl] }));
  };
  const bookmark = () => { patch(id, b => ({ bm:[{ id:uid(), ...cur.current }, ...b.bm] })); Alert.alert(L(l, 'Bookmark added', 'Marque-page ajouté')); };
  const jump = (p: number) => { web.current?.injectJavaScript(`window.jump(${p});true;`); setPanel(false); };
  const label = (m: { page: number; pct: number }) => m.page ? `${L(l, 'Page', 'Page')} ${m.page}` : `${Math.round(m.pct * 100)}%`;
  const items = [...book.bm.map(m => ({ ...m, kind:'bm' as const })), ...book.hl.map(m => ({ ...m, kind:'hl' as const }))];

  return (
    <View style={{ flex:1, backgroundColor:th.bg, paddingTop:ins.top }}>
      <View style={st.top}>
        <Pressable onPress={() => navigation.goBack()} hitSlop={12}><Ionicons name="chevron-back" size={26} color={th.ink} /></Pressable>
        <View style={{ flex:1 }}>
          <Text numberOfLines={1} style={{ color:th.ink, fontWeight:'700' }}>{book.title}</Text>
          <Text style={{ color:th.sub, fontSize:12 }}>{Math.round(book.pct * 100)}%{book.total ? `  •  ${book.page}/${book.total}` : ''}  •  {fmtTime(book.time)}</Text>
        </View>
      </View>
      <View style={{ height:3, backgroundColor:th.line }}><View style={{ width:`${Math.round(book.pct * 100)}%`, height:3, backgroundColor:th.accent }} /></View>
      <View style={{ flex:1 }}>
        <WebView ref={web} originWhitelist={['*']} source={{ html, baseUrl:'https://notia.local' }} onMessage={onMessage} style={{ backgroundColor:th.bg }} allowsInlineMediaPlayback
          setSupportMultipleWindows={false} allowFileAccess={false} mixedContentMode="never"
          onShouldStartLoadWithRequest={r => r.url === 'about:blank' || r.url.startsWith('https://notia.local') || r.url.startsWith('data:')}
          onError={e => fail(L(l, 'Could not open the book', 'Impossible d’ouvrir le livre'), e.nativeEvent.description)}
          onRenderProcessGone={() => fail(L(l, 'The reader ran out of memory', 'Le lecteur a manqué de mémoire'), L(l, 'This book is too heavy for the reader. Try a smaller file.', 'Ce livre est trop lourd pour le lecteur. Essayez un fichier plus petit.'))} />
        {loading ? <View style={[StyleSheet.absoluteFill, { alignItems:'center', justifyContent:'center' }]} pointerEvents="none"><ActivityIndicator color={th.accent} /></View> : null}
      </View>
      <View style={[st.bar, { backgroundColor:th.nav, bottom:ins.bottom + 12 }]}>
        <Pressable onPress={() => web.current?.injectJavaScript('window.hl();true;')} hitSlop={10}><Ionicons name="color-fill" size={22} color={th.accent} /></Pressable>
        <Pressable onPress={bookmark} hitSlop={10}><Ionicons name="bookmark" size={22} color={th.accent} /></Pressable>
        <Pressable onPress={() => setPanel(true)} hitSlop={10}><Ionicons name="list" size={24} color={th.accent} /></Pressable>
      </View>
      <Modal visible={panel} transparent animationType="slide" onRequestClose={() => setPanel(false)}>
        <Pressable style={{ flex:1, backgroundColor:'#0008' }} onPress={() => setPanel(false)} />
        <View style={{ maxHeight:'60%', backgroundColor:th.card, padding:20, paddingBottom:ins.bottom + 20, borderTopLeftRadius:28, borderTopRightRadius:28 }}>
          <Text style={{ color:th.ink, fontWeight:'800', fontSize:20, marginBottom:10 }}>{L(l, 'Bookmarks & highlights', 'Marque-pages et surlignages')}</Text>
          <FlatList data={items} keyExtractor={i => i.id} ItemSeparatorComponent={() => <View style={{ height:8 }} />}
            ListEmptyComponent={<Text style={{ color:th.sub }}>{L(l, 'Select text and tap the highlighter, or tap the bookmark.', 'Sélectionnez du texte puis touchez le surligneur, ou touchez le marque-page.')}</Text>}
            renderItem={({ item }) => (
              <Pressable onPress={() => jump(item.pct)} onLongPress={() => patch(id, b => item.kind === 'bm' ? { bm:b.bm.filter(x => x.id !== item.id) } : { hl:b.hl.filter(x => x.id !== item.id) })}
                style={{ flexDirection:'row', gap:10, padding:12, borderRadius:14, borderWidth:1, borderColor:th.line }}>
                <Ionicons name={item.kind === 'bm' ? 'bookmark' : 'color-fill'} size={18} color={th.accent} />
                <View style={{ flex:1 }}><Text style={{ color:th.ink, fontWeight:'600' }}>{label(item)}</Text>{item.text ? <Text numberOfLines={3} style={{ color:th.sub, marginTop:2 }}>{item.text}</Text> : null}</View>
              </Pressable>
            )} />
        </View>
      </Modal>
    </View>
  );
}
const st = StyleSheet.create({
  top:{ flexDirection:'row', alignItems:'center', gap:10, paddingHorizontal:16, height:52 },
  bar:{ position:'absolute', alignSelf:'center', flexDirection:'row', gap:34, paddingHorizontal:30, height:52, borderRadius:26, alignItems:'center', elevation:8 },
});
