import React, { useCallback, useEffect, useRef, useState } from 'react';
import { View, Text, TextInput, Pressable, FlatList, Alert, StyleSheet } from 'react-native';
import * as SecureStore from 'expo-secure-store';
import * as Crypto from 'expo-crypto';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { useApp, uid } from '../store';
import { L } from '../i18n';

type Entry = { id: string; site: string; user: string; pass: string };
const HASH = 'notia.vault.hash', SALT = 'notia.vault.salt', IDX = 'notia.vault.idx';
const hash = (salt: string, secret: string) => Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, salt + secret);
const validSecret = (v: string) => /^\d{5}$/.test(v) || (v.length >= 6 && /[a-z]/i.test(v) && /\d/.test(v) && /^[a-z0-9]+$/i.test(v));
const readIdx = async (): Promise<string[]> => { try { const v = JSON.parse((await SecureStore.getItemAsync(IDX)) || '[]'); return Array.isArray(v) ? v : []; } catch { return []; } };

export default function Vault({ navigation }: any) {
  const { s, th } = useApp();
  const l = s.lang;
  const ins = useSafeAreaInsets();
  const [phase, setPhase] = useState<'load' | 'setup' | 'locked' | 'open'>('load');
  const [secret, setSecret] = useState('');
  const [err, setErr] = useState('');
  const [items, setItems] = useState<Entry[]>([]);
  const [shown, setShown] = useState<string | null>(null);
  const fails = useRef({ n: 0, until: 0 });
  const [form, setForm] = useState<{ site: string; user: string; pass: string } | null>(null);

  useEffect(() => { SecureStore.getItemAsync(HASH).then(h => setPhase(h ? 'locked' : 'setup')).catch(() => setPhase('setup')); }, []);
  useEffect(() => navigation.addListener('blur', () => { setPhase(p => (p === 'open' ? 'locked' : p)); setItems([]); setShown(null); setSecret(''); setForm(null); }), [navigation]);

  const load = useCallback(async () => {
    const ids = await readIdx();
    const all = await Promise.all(ids.map(async i => { try { const v = await SecureStore.getItemAsync('notia.vault.' + i); return v ? (JSON.parse(v) as Entry) : null; } catch { return null; } }));
    setItems(all.filter(Boolean) as Entry[]);
  }, []);

  const create = async () => {
    if (!validSecret(secret)) { setErr(L(l, 'Use 5 digits, or 6+ letters and numbers.', 'Utilisez 5 chiffres, ou 6+ lettres et chiffres.')); return; }
    const salt = Crypto.randomUUID();
    await SecureStore.setItemAsync(SALT, salt);
    await SecureStore.setItemAsync(HASH, await hash(salt, secret));
    setSecret(''); setErr(''); setPhase('open'); setItems([]);
  };
  const unlock = async () => {
    const wait = fails.current.until - Date.now();
    if (wait > 0) { setErr(L(l, `Too many attempts. Try again in ${Math.ceil(wait / 1000)} s.`, `Trop d’essais. Réessayez dans ${Math.ceil(wait / 1000)} s.`)); setSecret(''); return; }
    const [salt, h] = await Promise.all([SecureStore.getItemAsync(SALT), SecureStore.getItemAsync(HASH)]);
    if (salt && h && (await hash(salt, secret)) === h) { fails.current = { n: 0, until: 0 }; setSecret(''); setErr(''); await load(); setPhase('open'); }
    else {
      const n = fails.current.n + 1;
      fails.current = { n, until: n >= 5 ? Date.now() + Math.min(300, 15 * 2 ** (n - 5)) * 1000 : 0 };
      setErr(L(l, 'Wrong PIN or password.', 'PIN ou mot de passe incorrect.')); setSecret('');
    }
  };
  const saveEntry = async () => {
    if (!form || !form.site.trim() || !form.pass) return;
    const e: Entry = { id:uid(), ...form, site:form.site.trim() };
    await SecureStore.setItemAsync('notia.vault.' + e.id, JSON.stringify(e));
    await SecureStore.setItemAsync(IDX, JSON.stringify([...(await readIdx()), e.id]));
    setItems(p => [e, ...p]); setForm(null);
  };
  const del = (e: Entry) => Alert.alert(L(l, 'Delete this entry?', 'Supprimer cette entrée ?'), e.site, [
    { text:L(l, 'Cancel', 'Annuler'), style:'cancel' },
    { text:L(l, 'Delete', 'Supprimer'), style:'destructive', onPress:async () => {
      await SecureStore.deleteItemAsync('notia.vault.' + e.id);
      await SecureStore.setItemAsync(IDX, JSON.stringify((await readIdx()).filter(i => i !== e.id)));
      setItems(p => p.filter(x => x.id !== e.id)); } },
  ]);

  const box = { backgroundColor:th.card, borderColor:th.line, color:th.ink } as const;
  if (phase === 'load') return <View style={{ flex:1, backgroundColor:th.bg }} />;

  if (phase !== 'open') return (
    <View style={{ flex:1, backgroundColor:th.bg, alignItems:'center', justifyContent:'center', padding:32, gap:14 }}>
      <Ionicons name="lock-closed" size={44} color={th.accent} />
      <Text style={{ color:th.ink, fontSize:22, fontWeight:'800' }}>{phase === 'setup' ? L(l, 'Protect your vault', 'Protégez votre coffre') : L(l, 'Vault locked', 'Coffre verrouillé')}</Text>
      <Text style={{ color:th.sub, textAlign:'center' }}>{phase === 'setup' ? L(l, 'Choose a 5-digit PIN, or a password with 6+ letters and numbers.', 'Choisissez un PIN à 5 chiffres, ou un mot de passe de 6+ lettres et chiffres.') : L(l, 'Enter your PIN or password.', 'Entrez votre PIN ou mot de passe.')}</Text>
      <TextInput value={secret} onChangeText={v => { setSecret(v); setErr(''); }} secureTextEntry autoCapitalize="none" autoCorrect={false}
        onSubmitEditing={phase === 'setup' ? create : unlock} style={[st.input, box, { width:'100%', textAlign:'center', fontSize:20 }]} />
      {err ? <Text style={{ color:'#E5484D' }}>{err}</Text> : null}
      <Pressable onPress={phase === 'setup' ? create : unlock} style={[st.btn, { backgroundColor:th.accent }]}>
        <Text style={{ color:th.accentInk, fontWeight:'700' }}>{phase === 'setup' ? L(l, 'Create vault', 'Créer le coffre') : L(l, 'Unlock', 'Déverrouiller')}</Text>
      </Pressable>
    </View>
  );

  return (
    <View style={{ flex:1, backgroundColor:th.bg, paddingTop:ins.top + 12 }}>
      <View style={st.head}>
        <Text style={{ color:th.ink, fontSize:32, fontWeight:'800' }}>{L(l, 'Vault', 'Coffre')}</Text>
        <View style={{ flexDirection:'row', gap:16 }}>
          <Pressable onPress={() => setForm({ site:'', user:'', pass:'' })} hitSlop={10}><Ionicons name="add-circle" size={30} color={th.accent} /></Pressable>
          <Pressable onPress={() => { setPhase('locked'); setItems([]); }} hitSlop={10}><Ionicons name="lock-closed-outline" size={26} color={th.ink} /></Pressable>
        </View>
      </View>
      {form ? (
        <View style={{ paddingHorizontal:20, gap:8, marginBottom:8 }}>
          {([['site', L(l, 'Site or app', 'Site ou app')], ['user', L(l, 'Username or email', 'Identifiant ou e-mail')], ['pass', L(l, 'Password', 'Mot de passe')]] as const).map(([k, ph]) => (
            <TextInput key={k} value={form[k]} onChangeText={v => setForm({ ...form, [k]:v })} placeholder={ph} placeholderTextColor={th.sub}
              autoCapitalize="none" autoCorrect={false} secureTextEntry={k === 'pass'} style={[st.input, box]} />
          ))}
          <View style={{ flexDirection:'row', gap:8 }}>
            <Pressable onPress={saveEntry} style={[st.btn, { backgroundColor:th.accent }]}><Text style={{ color:th.accentInk, fontWeight:'700' }}>{L(l, 'Save', 'Enregistrer')}</Text></Pressable>
            <Pressable onPress={() => setForm(null)} style={[st.btn, { borderWidth:1, borderColor:th.line }]}><Text style={{ color:th.ink }}>{L(l, 'Cancel', 'Annuler')}</Text></Pressable>
          </View>
        </View>
      ) : null}
      <FlatList data={items} keyExtractor={e => e.id} contentContainerStyle={{ padding:20, paddingBottom:170, gap:10 }}
        ListEmptyComponent={<Text style={{ color:th.sub, textAlign:'center', marginTop:50 }}>{L(l, 'Nothing saved yet. Tap + to add a login.', 'Rien d’enregistré. Touchez + pour ajouter un accès.')}</Text>}
        renderItem={({ item }) => (
          <Pressable onLongPress={() => del(item)} style={[st.card, { backgroundColor:th.card, borderColor:th.line }]}>
            <View style={{ flex:1 }}>
              <Text style={{ color:th.ink, fontWeight:'700' }}>{item.site}</Text>
              <Text style={{ color:th.sub, marginTop:2 }}>{item.user}</Text>
              <Text style={{ color:th.ink, marginTop:6, letterSpacing:shown === item.id ? 0 : 2 }}>{shown === item.id ? item.pass : '••••••••'}</Text>
            </View>
            <Pressable onPress={() => setShown(shown === item.id ? null : item.id)} hitSlop={10}><Ionicons name={shown === item.id ? 'eye-off' : 'eye'} size={22} color={th.sub} /></Pressable>
          </Pressable>
        )} />
    </View>
  );
}
const st = StyleSheet.create({
  head:{ flexDirection:'row', justifyContent:'space-between', alignItems:'center', paddingHorizontal:20, marginBottom:12 },
  input:{ borderWidth:1, borderRadius:14, paddingHorizontal:14, paddingVertical:12 },
  btn:{ paddingHorizontal:20, height:44, borderRadius:22, alignItems:'center', justifyContent:'center' },
  card:{ flexDirection:'row', alignItems:'center', padding:16, borderRadius:20, borderWidth:1 },
});
