import * as N from 'expo-notifications';
import { Platform } from 'react-native';

export type Task = { id: string; title: string; due: number | null; done: boolean; nid?: string };
export const TASKS_KEY = 'notia:tasks';
export type Scheduled = { nid?: string; err?: 'denied' | string };

try {
  N.setNotificationHandler({
    handleNotification: async () => ({ shouldShowAlert: true, shouldShowBanner: true, shouldShowList: true, shouldPlaySound: true, shouldSetBadge: false } as any),
  });
} catch {}

export async function schedule(title: string, due: number): Promise<Scheduled> {
  try {
    // Android 13+ only shows the permission prompt once a notification channel exists.
    if (Platform.OS === 'android') await N.setNotificationChannelAsync('tasks', { name: 'Tasks', importance: N.AndroidImportance.HIGH });
    let p = await N.getPermissionsAsync();
    if (!p.granted) p = await N.requestPermissionsAsync();
    if (!p.granted) return { err: 'denied' };
    const nid = await N.scheduleNotificationAsync({
      content: { title: 'Notia', body: title },
      trigger: { type: N.SchedulableTriggerInputTypes.DATE, date: new Date(due), channelId: 'tasks' } as any,
    });
    return { nid };
  } catch (e: any) { return { err: String(e?.message ?? e) }; }
}
export const cancel = (nid?: string) => (nid ? N.cancelScheduledNotificationAsync(nid).catch(() => {}) : Promise.resolve());
