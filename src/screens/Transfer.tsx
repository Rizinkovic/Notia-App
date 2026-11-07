import React, { useEffect, useRef, useState } from 'react';
import { View, Text, TextInput, Pressable, Switch, ScrollView, ActivityIndicator, StyleSheet, Alert } from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import * as Crypto from 'expo-crypto';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { useApp } from '../store';
import { L } from '../i18n';
import { PORT, myIp, parseTargets, sendTo, startServer, toPayload, cleanIncoming } from '../net';

export default function Transfer({ route, navigation }: any) {
  const { s, th, importNotes } = useApp();
  const l = s.lang;
  const ins = useSafeAreaInsets();
  const [sel, setSel] = useState<Set<string>>(new Set<string>(route.params?.ids ?? []));
  const [targets, setTargets] = useState('');
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState('');
  const [rx, setRx] = useState(false);
  const [code, setCode] = useState('');
  const [ip, setIp] = useState('');
  const [got, setGot] = useState(0);
  const [err, setErr] = useState('');
  const [scanning, setScanning] = useState(false);
  const [scanBusy, setScanBusy] = useState(false);
  const [cameraPermission, requestCameraPermission] = useCameraPermissions();
  const scanned = useRef(false);
  const srv = useRef<{ stop: () => void } | null>(null);
  const imp = useRef(importNotes);
  imp.current = importNotes;
  useEffect(() => () => srv.current?.stop(), []);

  const toggle = (id: string) => setSel(p => { const n = new Set(p); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const status = (c: number) => c === 200 ? L(l, 'sent ✓', 'envoyé ✓') : c === 401 ? L(l, 'wrong code', 'code incorrect') : c === 0 ? L(l, 'unreachable (same Wi-Fi? app open and receiving?)', 'injoignable (même Wi-Fi ? application ouverte en réception ?)') : `error ${c}`;

  const send = async () => {
    const notes = s.notes.filter(n => sel.has(n.id)), tg = parseTargets(targets);
    if (!notes.length) return setReport(L(l, 'Select at least one note.', 'Sélectionnez au moins une note.'));
    if (!tg.length) return setReport(L(l, 'Enter the receiver address, e.g. 192.168.1.20 4821', 'Entrez l’adresse du destinataire, ex. 192.168.1.20 4821'));
    setBusy(true); setReport('');
    const payload = toPayload(notes, s.cats), lines: string[] = [];
    for (const t of tg) lines.push(`${t.host}:${t.port} — ${status(await sendTo(t, payload))}`);
    setReport(lines.join('\n')); setBusy(false);
  };
  const receive = async (on: boolean) => {
    srv.current?.stop(); srv.current = null; setErr('');
    if (!on) return setRx(false);
    const b = Crypto.getRandomBytes(2), c = String(((b[0] * 256 + b[1]) % 9000) + 1000);
    const h = startServer(c, list => { const n = imp.current(list); setGot(g => g + n); return n; }, setErr);
    if (!h) { setRx(false); return setErr(L(l, 'Receiving needs a development build of Notia (see README). Sending works everywhere.', 'La réception nécessite un build de développement de Notia (voir README). L’envoi fonctionne partout.')); }
    srv.current = h; setCode(c); setIp(await myIp()); setGot(0); setRx(true);
  };

  const startScan = async () => {
    const permission = cameraPermission?.granted ? cameraPermission : await requestCameraPermission();
    if (!permission.granted) return Alert.alert(L(l, 'Camera permission required', 'Autorisation caméra requise'), L(l, 'Allow camera access to scan the desktop QR code.', 'Autorisez l’accès à la caméra pour scanner le QR code du bureau.'));
    scanned.current = false; setScanBusy(false); setScanning(true);
  };
  const onQr = async ({ data }: { data: string }) => {
    if (scanned.current || scanBusy) return;
    scanned.current = true; setScanBusy(true);
    try {
      const url = new URL(data);
      if (url.protocol !== 'http:' || !/^\/[a-f0-9]{32}\/notes$/.test(url.pathname)) throw new Error('This is not a valid Notia sync QR code.');
      const response = await fetch(data);
      if (!response.ok) throw new Error('Desktop not reachable. Check same Wi-Fi and keep desktop sharing open.');
      const incoming = cleanIncoming(await response.json());
      if (!incoming.length) throw new Error('No valid notes found in this sync session.');
      const count = importNotes(incoming);
      setScanning(false);
      Alert.alert(L(l, 'Sync complete', 'Synchronisation terminée'), L(l, `${count} note(s) imported or updated.`, `${count} note(s) importée(s) ou mise(s) à jour.`));
    } catch (e: any) {
      Alert.alert(L(l, 'Sync failed', 'Échec de la synchronisation'), String(e?.message ?? e));
      scanned.current = false;
    } finally { setScanBusy(false); }
  };

  const card = { backgroundColor: th.card, borderColor: th.line, borderWidth: 1, borderRadius: 22, padding: 16, gap: 10 } as const;
  return (
    <View style={{ flex: 1, backgroundColor: th.bg, paddingTop: ins.top }}>
      <View style={st.top}>
        <Pressable onPress={() => navigation.goBack()} hitSlop={12}><Ionicons name="chevron-back" size={26} color={th.ink} /></Pressable>
        <Text style={{ color: th.ink, fontSize: 22, fontWeight: '800' }}>{L(l, 'Wi-Fi transfer', 'Transfert Wi-Fi')}</Text>
      </View>
      {scanning ? <View style={{ position: 'absolute', zIndex: 20, top: 52, bottom: 0, left: 0, right: 0, backgroundColor: '#000' }}>
        <CameraView style={{ flex: 1 }} facing="back" barcodeScannerSettings={{ barcodeTypes: ['qr'] }} onBarcodeScanned={onQr} />
        <View style={{ padding: 18, gap: 12 }}>
          <Text style={{ color: '#fff', textAlign: 'center' }}>{scanBusy ? L(l, 'Connecting and importing notes…', 'Connexion et importation des notes…') : L(l, 'Point your camera at the QR code displayed by Notia Desktop.', 'Pointez la caméra vers le QR code affiché par Notia Desktop.')}</Text>
          <Pressable onPress={() => setScanning(false)} style={{ backgroundColor: '#fff', padding: 14, borderRadius: 14 }}><Text style={{ color: '#111', textAlign: 'center', fontWeight: '800' }}>{L(l, 'Cancel', 'Annuler')}</Text></Pressable>
        </View>
      </View> : null}
      <ScrollView contentContainerStyle={{ padding: 20, paddingBottom: ins.bottom + 40, gap: 14 }} keyboardShouldPersistTaps="handled">
        <View style={card}>
          <Text style={{ color: th.ink, fontWeight: '800', fontSize: 16 }}>{L(l, 'Sync from desktop', 'Synchroniser depuis le bureau')}</Text>
          <Text style={{ color: th.sub }}>{L(l, 'Scan the QR code shown by Notia Desktop. Only one scan is needed. Both devices must be on the same Wi-Fi.', 'Scannez le QR code affiché par Notia Desktop. Un seul scan suffit. Les deux appareils doivent être sur le même Wi-Fi.')}</Text>
          <Pressable onPress={startScan} style={{ height: 46, borderRadius: 23, backgroundColor: th.accent, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: th.accentInk, fontWeight: '800' }}>{L(l, 'Scan desktop QR code', 'Scanner le QR code du bureau')}</Text></Pressable>
        </View>
        <View style={card}>
          <Text style={{ color: th.ink, fontWeight: '800', fontSize: 16 }}>{L(l, 'Send notes', 'Envoyer des notes')}</Text>
          {s.notes.length === 0 ? <Text style={{ color: th.sub }}>{L(l, 'No notes yet.', 'Aucune note.')}</Text> : null}
          {s.notes.map(n => (
            <Pressable key={n.id} onPress={() => toggle(n.id)} style={{ flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 4 }}>
              <Ionicons name={sel.has(n.id) ? 'checkbox' : 'square-outline'} size={22} color={sel.has(n.id) ? th.accent : th.sub} />
              <Text numberOfLines={1} style={{ color: th.ink, flex: 1 }}>{n.title || '—'}</Text>
            </Pressable>
          ))}
          <TextInput value={targets} onChangeText={setTargets} multiline autoCapitalize="none" autoCorrect={false} keyboardType="numbers-and-punctuation"
            placeholder={L(l, 'One receiver per line:\n192.168.1.20 4821', 'Un destinataire par ligne :\n192.168.1.20 4821')} placeholderTextColor={th.sub}
            style={{ borderWidth: 1, borderColor: th.line, borderRadius: 14, padding: 12, color: th.ink, minHeight: 74, textAlignVertical: 'top' }} />
          <Pressable onPress={send} disabled={busy} style={{ height: 46, borderRadius: 23, backgroundColor: th.accent, alignItems: 'center', justifyContent: 'center', opacity: busy ? 0.6 : 1 }}>
            {busy ? <ActivityIndicator color={th.accentInk} /> : <Text style={{ color: th.accentInk, fontWeight: '800' }}>{L(l, 'Send', 'Envoyer')} ({sel.size})</Text>}
          </Pressable>
          {report ? <Text style={{ color: th.sub }}>{report}</Text> : null}
        </View>
        <View style={card}>
          <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
            <Text style={{ color: th.ink, fontWeight: '800', fontSize: 16 }}>{L(l, 'Receive notes', 'Recevoir des notes')}</Text>
            <Switch value={rx} onValueChange={receive} trackColor={{ true: th.accent, false: th.line }} />
          </View>
          {rx ? (<>
            <Text style={{ color: th.sub }}>{L(l, 'On the sender, enter:', 'Sur l’expéditeur, saisissez :')}</Text>
            <Text style={{ color: th.ink, fontSize: 22, fontWeight: '800' }}>{ip || '?'}:{PORT}  {code}</Text>
            <Text style={{ color: th.sub }}>{L(l, 'Received so far', 'Reçues')}: {got}</Text>
          </>) : <Text style={{ color: th.sub }}>{L(l, 'Both devices must be on the same Wi-Fi. Keep this screen open while receiving.', 'Les deux appareils doivent être sur le même Wi-Fi. Gardez cet écran ouvert pendant la réception.')}</Text>}
          {err ? <Text style={{ color: '#E5484D' }}>{err}</Text> : null}
        </View>
      </ScrollView>
    </View>
  );
}
const st = StyleSheet.create({ top: { flexDirection: 'row', alignItems: 'center', gap: 10, paddingHorizontal: 16, height: 52 } });
