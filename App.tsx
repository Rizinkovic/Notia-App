import React, { useEffect, useState } from 'react';
import { View, Image, ActivityIndicator } from 'react-native';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { NavigationContainer, DefaultTheme } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { Provider, useApp } from './src/store';
import Notes from './src/screens/Notes';
import Editor from './src/screens/Editor';
import Settings from './src/screens/Settings';
import Tasks from './src/screens/Tasks';
import Vault from './src/screens/Vault';
import Library from './src/screens/Library';
import Reader from './src/screens/Reader';
import Transfer from './src/screens/Transfer';
import { LibProvider } from './src/library';

SplashScreen.preventAutoHideAsync().catch(() => {});

function Loading() {
  return (
    <View style={{ flex:1, alignItems:'center', justifyContent:'center', backgroundColor:'#14121C' }}>
      <Image source={require('./assets/logo.png')} style={{ width:112, height:112 }} />
      <ActivityIndicator color="#E3B55B" style={{ marginTop:28 }} />
    </View>
  );
}

const Stack = createNativeStackNavigator();
const Tab = createBottomTabNavigator();

function Tabs() {
  const { th } = useApp();
  const ins = useSafeAreaInsets();
  return (
    <Tab.Navigator screenOptions={({ route }) => ({
      headerShown:false, tabBarShowLabel:false, tabBarHideOnKeyboard:true,
      tabBarActiveTintColor:th.accent, tabBarInactiveTintColor:'#8F8AA8',
      tabBarStyle:{ position:'absolute', left:12, right:12, bottom:ins.bottom + 12, height:56, borderRadius:28, backgroundColor:th.nav, borderTopWidth:0, elevation:8 },
      tabBarItemStyle:{ paddingVertical:6 },
      tabBarIcon:({ color }) => <Ionicons name={({ Notes:'document-text', Library:'book', Tasks:'checkbox', Vault:'lock-closed', Settings:'settings-sharp' } as any)[route.name]} size={22} color={color} />,
    })}>
      <Tab.Screen name="Notes" component={Notes} />
      <Tab.Screen name="Library" component={Library} />
      <Tab.Screen name="Tasks" component={Tasks} />
      <Tab.Screen name="Vault" component={Vault} />
      <Tab.Screen name="Settings" component={Settings} />
    </Tab.Navigator>
  );
}

function Root() {
  const { th, s, ready } = useApp();
  const [min, setMin] = useState(false);
  useEffect(() => { const t = setTimeout(() => setMin(true), 900); return () => clearTimeout(t); }, []);
  useEffect(() => { SplashScreen.hideAsync().catch(() => {}); }, []);
  if (!ready || !min) return <Loading />;
  return (
    <NavigationContainer theme={{ ...DefaultTheme, dark:s.mode === 'dark', colors:{ ...DefaultTheme.colors, background:th.bg, card:th.bg, text:th.ink, border:th.line, primary:th.accent } }}>
      <StatusBar style={s.mode === 'dark' ? 'light' : 'dark'} />
      <Stack.Navigator screenOptions={{ headerShown:false }}>
        <Stack.Screen name="Tabs" component={Tabs} />
        <Stack.Screen name="Editor" component={Editor} />
        <Stack.Screen name="Reader" component={Reader} />
        <Stack.Screen name="Transfer" component={Transfer} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}

function LibHost() {
  const { s } = useApp();
  return <LibProvider lang={s.lang}><Root /></LibProvider>;
}

export default function App() {
  return <SafeAreaProvider><Provider><LibHost /></Provider></SafeAreaProvider>;
}
