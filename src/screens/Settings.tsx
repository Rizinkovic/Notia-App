import React from 'react';
import { View, Text, Switch, Pressable, StyleSheet } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useApp } from '../store';

export default function Settings() {
  const { s, th, t, setLang, setMode } = useApp();
  const ins = useSafeAreaInsets();
  const row = [st.row, { backgroundColor:th.card, borderColor:th.line }];
  return (
    <View style={{ flex:1, backgroundColor:th.bg, padding:20, paddingTop:ins.top + 12, gap:12 }}>
      <Text style={[st.h1, { color:th.ink }]}>{t('settings')}</Text>
      <View style={row}>
        <Text style={{ color:th.ink, fontWeight:'600' }}>{t('dark')}</Text>
        <Switch value={s.mode === 'dark'} onValueChange={v => setMode(v ? 'dark' : 'light')} trackColor={{ true:th.accent, false:th.line }} />
      </View>
      <View style={row}>
        <Text style={{ color:th.ink, fontWeight:'600' }}>{t('language')}</Text>
        <View style={{ flexDirection:'row', gap:8 }}>
          {(['en', 'fr'] as const).map(l => (
            <Pressable key={l} onPress={() => setLang(l)} style={[st.pill, { backgroundColor:s.lang === l ? th.accent : 'transparent', borderColor:th.line }]}>
              <Text style={{ color:s.lang === l ? th.accentInk : th.ink, fontWeight:'700' }}>{l === 'en' ? 'English' : 'Français'}</Text>
            </Pressable>
          ))}
        </View>
      </View>
      <View style={[row, { flexDirection:'column', alignItems:'flex-start', gap:6 }]}>
        <Text style={{ color:th.ink, fontWeight:'700' }}>{t('about')}</Text>
        <Text style={{ color:th.sub, lineHeight:20 }}>{t('aboutTxt')}</Text>
      </View>
    </View>
  );
}
const st = StyleSheet.create({
  h1:{ fontSize:32, fontWeight:'800', marginBottom:8 },
  row:{ flexDirection:'row', alignItems:'center', justifyContent:'space-between', padding:16, borderRadius:20, borderWidth:1 },
  pill:{ paddingHorizontal:12, height:34, borderRadius:17, borderWidth:1, justifyContent:'center' },
});
