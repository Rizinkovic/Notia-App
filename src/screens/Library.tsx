import React from 'react';
import { View, Text, FlatList, Pressable, Alert, ActivityIndicator, Image, StyleSheet } from 'react-native';
import * as DocumentPicker from 'expo-document-picker';
import * as FS from 'expo-file-system/legacy';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { useApp } from '../store';
import { useLib, fmtTime, MAX_BOOKS, MAX_BOOK_BYTES, Book, bookPath, importBook, ImportError } from '../library';
import { L } from '../i18n';

const COVERS = ['#5B47E0', '#7C3AED', '#0F766E', '#B45309', '#BE185D'];
export default function Library({ navigation }: any) {
  const { s, th } = useApp();
  const l = s.lang;
  const { books, add, remove, purged, ackPurged } = useLib();
  const ins = useSafeAreaInsets();
  React.useEffect(() => {
    if (purged > 0) {
      Alert.alert(L(l, 'Library cleaned up', 'Bibliothèque nettoyée'), L(l, `${purged} book(s) from an older version had no file on this device and were removed. Please add them again.`, `${purged} livre(s) d’une ancienne version n’avaient plus de fichier et ont été retirés. Ajoutez-les de nouveau.`));
      ackPurged();
    }
  }, [purged, l, ackPurged]);
  const total = books.reduce((a, b) => a + b.time, 0);

  const [busy, setBusy] = React.useState(false);
  const pick = async () => {
    if (busy) return;
    if (books.length >= MAX_BOOKS) return Alert.alert(L(l, 'Library full', 'Bibliothèque pleine'), L(l, `The library holds ${MAX_BOOKS} books. Remove one first.`, `La bibliothèque contient ${MAX_BOOKS} livres. Supprimez-en un d’abord.`));
    // copyToCacheDirectory:false -> we get the original content:// URI. The picker's own cache copy lives in Expo Go's shared cache,
    // which Expo's file system is not allowed to read ("Location ... isn't readable").
    let r: DocumentPicker.DocumentPickerResult;
    try { r = await DocumentPicker.getDocumentAsync({ type:'*/*', copyToCacheDirectory:false, multiple:false }); }
    catch (e: any) { return Alert.alert(L(l, 'Could not open the file picker', 'Impossible d’ouvrir le sélecteur'), String(e?.message ?? e)); }
    const a = r.assets?.[0];
    if (r.canceled || !a) return;
    setBusy(true);
    try {
      const f = await importBook(a);
      const id = add({ title:f.title, kind:f.kind, uri:f.uri });
      if (a.uri.includes('/cache/')) FS.deleteAsync(a.uri, { idempotent:true }).catch(() => {});
      navigation.navigate('Reader', { id });
    } catch (e: any) {
      const code = e instanceof ImportError ? e.code : 'copy';
      if (code === 'unsupported') Alert.alert(L(l, 'Format not supported', 'Format non pris en charge'), L(l, `“.${e.message}” files can’t be opened. Supported: PDF, EPUB, MOBI/AZW, DOCX, TXT, HTML. Convert the book to EPUB (for example with Calibre) and add it again.`, `Les fichiers « .${e.message} » ne sont pas pris en charge. Formats : PDF, EPUB, MOBI/AZW, DOCX, TXT, HTML. Convertissez le livre en EPUB (par exemple avec Calibre) puis ajoutez-le.`));
      else if (code === 'big') Alert.alert(L(l, 'File too large', 'Fichier trop volumineux'), L(l, `Books up to ${MAX_BOOK_BYTES / 1048576} MB are supported.`, `Les livres jusqu’à ${MAX_BOOK_BYTES / 1048576} Mo sont pris en charge.`));
      else if (code === 'invalid') Alert.alert(L(l, 'This file looks damaged', 'Ce fichier semble endommagé'), L(l, `It is not a valid ${String(e.message).toUpperCase()} file. Try another copy of the book.`, `Ce n’est pas un fichier ${String(e.message).toUpperCase()} valide. Essayez une autre copie du livre.`));
      else Alert.alert(L(l, 'Could not add the book', 'Impossible d’ajouter le livre'), L(l, 'Try again, or pick another file.', 'Réessayez, ou choisissez un autre fichier.') + '\n\n' + String(e?.message ?? e).slice(0, 700));
    } finally { setBusy(false); }
  };
  const del = (b: Book) => Alert.alert(L(l, 'Remove this book?', 'Retirer ce livre ?'), b.title, [
    { text:L(l, 'Cancel', 'Annuler'), style:'cancel' },
    { text:L(l, 'Remove', 'Retirer'), style:'destructive', onPress:() => { FS.deleteAsync(bookPath(b.uri), { idempotent:true }).catch(() => {}); remove(b.id); } },
  ]);

  return (
    <View style={{ flex:1, backgroundColor:th.bg, paddingTop:ins.top + 12 }}>
      <Text style={[st.h1, { color:th.ink }]}>{L(l, 'Library', 'Bibliothèque')}</Text>
      <View style={[st.stats, { backgroundColor:th.card, borderColor:th.line }]}>
        <View><Text style={{ color:th.ink, fontSize:24, fontWeight:'800' }}>{fmtTime(total)}</Text><Text style={{ color:th.sub }}>{L(l, 'Total reading time', 'Temps de lecture total')}</Text></View>
        <View style={{ alignItems:'flex-end' }}><Text style={{ color:th.ink, fontSize:24, fontWeight:'800' }}>{books.length}/{MAX_BOOKS}</Text><Text style={{ color:th.sub }}>{L(l, 'Books', 'Livres')}</Text></View>
      </View>
      <FlatList data={books} keyExtractor={b => b.id} contentContainerStyle={{ padding:20, paddingBottom:170, gap:12 }}
        ListEmptyComponent={<Text style={{ color:th.sub, textAlign:'center', marginTop:40 }}>{L(l, 'No books yet. Tap + to add a PDF, EPUB, MOBI, DOCX, TXT or HTML.', 'Aucun livre. Touchez + pour ajouter un PDF, EPUB, MOBI, DOCX, TXT ou HTML.')}</Text>}
        renderItem={({ item }) => (
          <Pressable onPress={() => navigation.navigate('Reader', { id:item.id })} onLongPress={() => del(item)} style={[st.card, { backgroundColor:th.card, borderColor:th.line }]}>
            {item.cover
              ? <Image source={{ uri:item.cover }} style={[st.cover, { backgroundColor:th.line }]} resizeMode="cover" fadeDuration={0} />
              : <View style={[st.cover, { backgroundColor:COVERS[item.title.length % COVERS.length], padding:6, justifyContent:'space-between' }]}>
                  <Text numberOfLines={3} style={{ color:'#fff', fontWeight:'800', fontSize:11 }}>{item.title}</Text>
                  <Text style={{ color:'#ffffffcc', fontWeight:'700', fontSize:10 }}>{item.kind.toUpperCase()}</Text>
                </View>}
            <View style={{ flex:1 }}>
              <Text numberOfLines={2} style={{ color:th.ink, fontWeight:'700', fontSize:16 }}>{item.title}</Text>
              <View style={[st.track, { backgroundColor:th.line }]}><View style={{ width:`${Math.round(item.pct * 100)}%`, height:6, borderRadius:3, backgroundColor:th.accent }} /></View>
              <Text style={{ color:th.sub, fontSize:12 }}>{Math.round(item.pct * 100)}%  •  {fmtTime(item.time)}{item.total ? `  •  p. ${item.page}/${item.total}` : ''}</Text>
            </View>
          </Pressable>
        )} />
      <Pressable accessibilityLabel="Add book" onPress={pick} style={[st.fab, { backgroundColor:th.accent, bottom:ins.bottom + 84 }]}>
        {busy ? <ActivityIndicator color={th.accentInk} /> : <Ionicons name="add" size={28} color={th.accentInk} />}
      </Pressable>
    </View>
  );
}
const st = StyleSheet.create({
  h1:{ fontSize:32, fontWeight:'800', paddingHorizontal:20, marginBottom:12 },
  stats:{ flexDirection:'row', justifyContent:'space-between', marginHorizontal:20, padding:16, borderRadius:22, borderWidth:1 },
  card:{ flexDirection:'row', gap:14, padding:14, borderRadius:22, borderWidth:1, alignItems:'center' },
  cover:{ width:56, height:76, borderRadius:10, alignItems:'center', justifyContent:'center' },
  track:{ height:6, borderRadius:3, marginVertical:8 },
  fab:{ position:'absolute', right:20, width:56, height:56, borderRadius:28, alignItems:'center', justifyContent:'center', elevation:6 },
});
