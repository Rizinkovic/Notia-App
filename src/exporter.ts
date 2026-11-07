import * as Print from 'expo-print';
import * as Sharing from 'expo-sharing';
import * as FS from 'expo-file-system/legacy';
import { Share } from 'react-native';
import { buildDocx } from './docx';
import { plain } from './store';

type N = { title: string; html: string };
const safe = (t: string) => (t.replace(/[^\p{L}\p{N}\- _]+/gu, '').trim().slice(0, 40).trim() || 'note');

export const noteHtml = (n: N) =>
  `<html><head><meta charset="utf-8"><style>body{font-family:sans-serif;padding:24px;line-height:1.5;color:#111}img{max-width:100%}table{border-collapse:collapse;width:100%}td,th{border:1px solid #999;padding:6px}code,pre{font-family:monospace;background:#f2f2f2}mark{background:#ffe27a}</style></head><body><h1>${n.title.replace(/</g, '&lt;')}</h1>${n.html}</body></html>`;

async function shareFile(uri: string, mimeType: string, UTI?: string) {
  if (!(await Sharing.isAvailableAsync())) throw new Error('Sharing is not available on this device.');
  await Sharing.shareAsync(uri, { mimeType, UTI });
}
export async function sharePdf(n: N) {
  try {
    // base64:true returns the PDF bytes directly, so we never have to read the print engine's own folder
    const r = await Print.printToFileAsync({ html: noteHtml(n), base64: true });
    if (!r.base64) throw new Error('The PDF engine returned no data.');
    const to = (FS.cacheDirectory ?? '') + safe(n.title) + '.pdf';
    await FS.writeAsStringAsync(to, r.base64, { encoding: FS.EncodingType.Base64 });
    await shareFile(to, 'application/pdf', 'com.adobe.pdf');
  } catch {
    await Print.printAsync({ html: noteHtml(n) }); // system dialog -> "Save as PDF"
  }
}
export async function shareDocx(n: N) {
  const b64 = await buildDocx(n.title, n.html);
  const to = (FS.cacheDirectory ?? '') + safe(n.title) + '.docx';
  await FS.writeAsStringAsync(to, b64, { encoding: FS.EncodingType.Base64 });
  await shareFile(to, 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'org.openxmlformats.wordprocessingml.document');
}
export const shareText = (n: N) => Share.share({ title: n.title, message: `${n.title}\n\n${plain(n.html)}` });
