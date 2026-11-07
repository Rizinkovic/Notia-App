import React, { useEffect, useRef, useState } from 'react';
import { View, Text, TextInput, FlatList, Pressable, Alert, Linking, StyleSheet } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { DateTimePickerAndroid } from '@react-native-community/datetimepicker';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { useApp, uid } from '../store';
import { L } from '../i18n';
import { fmtDate } from '../tools';
import { Task, TASKS_KEY, schedule, cancel } from '../tasks';

const at = (daysAhead: number, h: number) => { const d = new Date(); d.setDate(d.getDate() + daysAhead); d.setHours(h, 0, 0, 0); return d.getTime(); };

export default function Tasks() {
  const { s, th } = useApp();
  const l = s.lang;
  const ins = useSafeAreaInsets();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [ready, setReady] = useState(false);
  const [text, setText] = useState('');
  const [due, setDue] = useState<number | null>(null);
  const [quickIdx, setQuickIdx] = useState(-1);
  const [warn, setWarn] = useState('');
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => { AsyncStorage.getItem(TASKS_KEY).then(v => v && setTasks(JSON.parse(v))).catch(() => {}).finally(() => setReady(true)); }, []);
  useEffect(() => {
    if (!ready) return;
    clearTimeout(timer.current);
    timer.current = setTimeout(() => AsyncStorage.setItem(TASKS_KEY, JSON.stringify(tasks)).catch(() => {}), 300);
  }, [tasks, ready]);

  const explain = (err: string) => {
    if (err === 'denied') {
      setWarn(L(l, 'Notifications are blocked, so there will be no reminder.', 'Les notifications sont bloquées : aucun rappel ne sera envoyé.'));
      Alert.alert(L(l, 'Allow notifications', 'Autoriser les notifications'), L(l, 'Turn notifications on for this app in Settings to get reminders.', 'Activez les notifications pour cette application dans les Réglages pour recevoir les rappels.'),
        [{ text: L(l, 'Not now', 'Plus tard'), style: 'cancel' }, { text: L(l, 'Open settings', 'Ouvrir les réglages'), onPress: () => Linking.openSettings() }]);
    } else setWarn(L(l, 'Reminder failed: ', 'Rappel échoué : ') + err);
  };
  const add = () => {
    const title = text.trim();
    if (!title) return;
    const id = uid(), when = due;
    setTasks(p => [{ id, title, due: when, done: false }, ...p]); // always show the task at once
    setText(''); setDue(null); setQuickIdx(-1); setWarn('');
    if (!when) return;
    if (when <= Date.now() + 2000) return setWarn(L(l, 'That time has already passed, so no reminder was set.', 'Cette heure est déjà passée : aucun rappel programmé.'));
    schedule(title, when).then(r => { if (r.nid) setTasks(p => p.map(x => (x.id === id ? { ...x, nid: r.nid } : x))); else explain(r.err ?? 'unknown'); });
  };
  const testReminder = async () => {
    const r = await schedule(L(l, 'Test reminder — notifications work!', 'Rappel test — les notifications fonctionnent !'), Date.now() + 5000);
    if (r.nid) Alert.alert(L(l, 'Test scheduled', 'Test programmé'), L(l, 'A notification will appear in 5 seconds. Pull down the status bar if you don’t see it.', 'Une notification apparaîtra dans 5 secondes. Ouvrez la barre d’état si vous ne la voyez pas.'));
    else explain(r.err ?? 'unknown');
  };
  const pick = () => {
    DateTimePickerAndroid.open({
      value: new Date(due ?? Date.now() + 3600000), mode: 'date', minimumDate: new Date(),
      onChange: (e, d) => {
        if (e.type !== 'set' || !d) return;
        DateTimePickerAndroid.open({
          value: d, mode: 'time', is24Hour: true,
          onChange: (e2, t) => {
            if (e2.type !== 'set' || !t) return;
            const x = new Date(d); x.setHours(t.getHours(), t.getMinutes(), 0, 0); setDue(x.getTime()); setQuickIdx(-1);
          },
        });
      },
    });
  };
  const toggle = (tk: Task) => {
    if (!tk.done) cancel(tk.nid);
    setTasks(p => p.map(x => (x.id === tk.id ? { ...x, done: !x.done, nid: undefined } : x)));
    if (tk.done && tk.due && tk.due > Date.now() + 2000) schedule(tk.title, tk.due).then(r => { if (r.nid) setTasks(p => p.map(x => (x.id === tk.id && !x.done ? { ...x, nid: r.nid } : x))); });
  };
  const remove = (tk: Task) => { cancel(tk.nid); setTasks(p => p.filter(x => x.id !== tk.id)); };

  const quick: [string, number][] = [
    [L(l, 'In 1 min', 'Dans 1 min'), Date.now() + 60000],
    [L(l, 'In 1 hour', 'Dans 1 h'), Date.now() + 3600000],
    [L(l, 'Tonight 8pm', 'Ce soir 20h'), at(0, 20)],
    [L(l, 'Tomorrow 9am', 'Demain 9h'), at(1, 9)],
  ];
  const sorted = [...tasks].sort((a, b) => Number(a.done) - Number(b.done) || (a.due ?? Infinity) - (b.due ?? Infinity));
  const chip = (label: string, active: boolean, onPress: () => void, icon: keyof typeof Ionicons.glyphMap = 'alarm-outline') => (
    <Pressable key={label} onPress={onPress} style={[st.chip, { borderColor: th.line, backgroundColor: active ? th.accent : th.card }]}>
      <Ionicons name={icon} size={14} color={active ? th.accentInk : th.sub} />
      <Text style={{ color: active ? th.accentInk : th.ink, fontWeight: '600', fontSize: 13 }}>{label}</Text>
    </Pressable>
  );

  return (
    <View style={{ flex: 1, backgroundColor: th.bg, paddingTop: ins.top + 12 }}>
      <View style={st.head}>
        <Text style={{ color: th.ink, fontSize: 32, fontWeight: '800' }}>{L(l, 'Tasks', 'Tâches')}</Text>
        <Pressable onPress={testReminder} hitSlop={10} accessibilityLabel="Test notification"><Ionicons name="notifications-outline" size={26} color={th.accent} /></Pressable>
      </View>
      <View style={{ paddingHorizontal: 20, gap: 10 }}>
        <View style={[st.row, { backgroundColor: th.card, borderColor: th.line }]}>
          <TextInput value={text} onChangeText={setText} onSubmitEditing={add} returnKeyType="done"
            placeholder={L(l, 'What needs doing?', 'Que faut-il faire ?')} placeholderTextColor={th.sub} style={{ flex: 1, color: th.ink, paddingVertical: 12 }} />
          <Pressable onPress={add} hitSlop={10} accessibilityLabel="Add task"><Ionicons name="add-circle" size={32} color={th.accent} /></Pressable>
        </View>
        <View style={{ flexDirection: 'row', gap: 8, flexWrap: 'wrap' }}>
          {quick.map(([label, ts], i) => chip(label, quickIdx === i && due !== null, () => { setDue(ts); setQuickIdx(i); }))}
          {chip(L(l, 'Pick date & time', 'Choisir date et heure'), false, pick, 'calendar-outline')}
        </View>
        {due ? (
          <Pressable onPress={() => { setDue(null); setQuickIdx(-1); }} style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <Ionicons name="alarm" size={16} color={th.accent} />
            <Text style={{ color: th.ink }}>{L(l, 'Reminder:', 'Rappel :')} {fmtDate(due, true)}</Text>
            <Ionicons name="close-circle" size={16} color={th.sub} />
          </Pressable>
        ) : null}
        {warn ? <Text style={{ color: '#E5484D', fontSize: 13 }}>{warn}</Text> : null}
      </View>
      <FlatList data={sorted} keyExtractor={t => t.id} contentContainerStyle={{ padding: 20, paddingBottom: 170, gap: 10 }}
        ListEmptyComponent={<Text style={{ color: th.sub, textAlign: 'center', marginTop: 50 }}>{L(l, 'No tasks. Add one above, with a reminder if it matters.', 'Aucune tâche. Ajoutez-en une, avec un rappel si besoin.')}</Text>}
        renderItem={({ item }) => (
          <Pressable onPress={() => toggle(item)} onLongPress={() => remove(item)} style={[st.row, { backgroundColor: th.card, borderColor: th.line, paddingVertical: 14 }]}>
            <Ionicons name={item.done ? 'checkmark-circle' : 'ellipse-outline'} size={24} color={item.done ? th.accent : th.sub} />
            <View style={{ flex: 1 }}>
              <Text style={{ color: th.ink, fontWeight: '600', textDecorationLine: item.done ? 'line-through' : 'none', opacity: item.done ? 0.5 : 1 }}>{item.title}</Text>
              {item.due ? (
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 2 }}>
                  <Ionicons name={item.nid && !item.done ? 'notifications' : 'notifications-off-outline'} size={12} color={item.nid && !item.done ? th.accent : th.sub} />
                  <Text style={{ color: th.sub, fontSize: 12 }}>{fmtDate(item.due, true)}</Text>
                </View>
              ) : null}
            </View>
            <Pressable onPress={() => remove(item)} hitSlop={10}><Ionicons name="trash-outline" size={20} color={th.sub} /></Pressable>
          </Pressable>
        )} />
    </View>
  );
}
const st = StyleSheet.create({
  head: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: 20, marginBottom: 12 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 14, borderRadius: 18, borderWidth: 1 },
  chip: { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 12, height: 32, borderRadius: 16, borderWidth: 1 },
});
