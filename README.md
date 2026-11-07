# Notia Mobile (Android) - Expo SDK 58, React Native, TypeScript

## Run
1. Install Node.js 20+ and start your Android emulator.
2. Windows: double-click `run.bat` (Mac/Linux: `sh run.sh`). Or: `npm install` then `npx expo start --android`.
3. Edit in VS Code; the app hot-reloads. Check types any time with `npm run typecheck`.

## Code map (src/)
| File | What it does |
|---|---|
| store.tsx / theme.ts / i18n.ts | notes, categories, theme, EN/FR, persistence |
| screens/Notes, Editor, EditorTools | list + rich editor; link / table / formula / chart sheets; share & export sheet |
| tools.ts | formula evaluator (no eval), table + chart (SVG) builders, HTML sanitiser |
| docx.ts / exporter.ts | HTML -> .docx (pure JS), PDF + DOCX + text sharing |
| net.ts / screens/Transfer | Wi-Fi send/receive |
| screens/Library, Reader, library.tsx, readerHtml.ts | books, reading stats, highlights, bookmarks, daily reminder |
| screens/Tasks, tasks.ts | tasks + reminders |
| screens/Vault | PIN / password vault |

## One-scan desktop QR sync
On desktop, open Share and choose **Sync notes to phone (QR)**. In mobile, open Transfer and tap **Scan desktop QR code**. Notes are imported or updated by ID; newer versions replace older ones. Both devices must be on the same Wi-Fi. No database or cloud backend is used. Keep the desktop share dialog open until import finishes.

## Wi-Fi transfer
Both devices on the same Wi-Fi. **Sending** works everywhere (Expo Go included).
**Receiving** needs a raw TCP server, which Expo Go cannot run, so build a development client once (no Android Studio needed):
```
npx expo install expo-dev-client expo-build-properties
```
add to app.json -> expo.plugins: `["expo-build-properties", { "android": { "usesCleartextTraffic": true } }]`, then
```
npx eas-cli build --profile development --platform android   (cloud build; free Expo account)
```
install the resulting APK on the emulator and run `npx expo start --dev-client`.

### Protocol (for the Notia desktop app, PC <-> phone)
```
POST http://<receiver-ip>:8765/notia/v1/notes
X-Notia-Code: <4-digit code displayed by the receiver>
{"v":1,"notes":[{"id":"abc","title":"...","html":"...","category":"Work"|null,"created":1730000000000,"modified":1730000000000}]}
-> 200 {"ok":true,"count":N} | 401 wrong code | 400 bad body
```
The desktop app can send with a plain HTTP POST, and can receive by running the same endpoint on its own port.
Incoming HTML is sanitised (scripts, event handlers, javascript: links removed). A note with the same id is only overwritten by a newer one.

## Notes
- Charts export to Word as data tables (Word has no SVG chart from HTML); in PDF they stay graphical.

## Loading screen / icon
The app shows the Notia logo while it loads (in-app screen, works everywhere). The native Android splash from `app.json` only appears in a built APK / development build — Expo Go always shows its own splash first.

## Books
Supported: **PDF, EPUB, MOBI / AZW / PRC, DOCX, TXT, HTML**. Not supported (convert to EPUB with Calibre): DRM-protected books, KF8-only AZW3, FB2, CBZ, DJVU, DOC, RTF.
- Adding a book copies it into the app's own storage (several fallback strategies, so it works with Downloads, Drive, USB and cloud providers). Max 60 MB per book, 10 books.
- If adding fails, the message now includes the real system error so it can be diagnosed.
- Covers: the real cover is extracted (PDF first page, EPUB / MOBI cover image). Formats with no image (DOCX, TXT, HTML) get a generated title cover. Covers appear as soon as the book is opened once; a generated cover is replaced if a real one is found later.
- In EPUBs, table-of-contents and in-text links jump inside the book; web links open in the browser.
- Highlights are re-applied when a book is reopened (text books; PDFs are images, so highlights are only listed).

## Reader (offline)
PDF, EPUB and DOCX engines are bundled in `src/readerLibs.ts` (generated from npm: pdfjs-dist, jszip, mammoth); MOBI, TXT and HTML are parsed by `src/readerHtml.ts` itself. Book HTML is sanitised (scripts, inline handlers, hard-coded colours removed). PDF pages are rendered on demand and released when far off-screen to keep memory low.

## Reminders
Android 13+ needs a notification channel before it will show the permission prompt; Tasks creates it first. Use the bell icon on the Tasks screen to fire a test notification in 5 seconds.

## PDF / Word export
Exports are written to the app's own cache folder, then shared. If the share sheet fails, PDF falls back to the system print dialog ("Save as PDF").
