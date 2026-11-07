# Notia Mobile (Android) - Expo SDK 58, React Native, TypeScript

## Run
1. Install Node.js 20+ and start your Android emulator.
2. Windows: double-click `run.bat` (Mac/Linux: `sh run.sh`). Or: `npm ci` then `npx expo start --android`.
3. Edit in VS Code; the app hot-reloads. Check types with `npm run typecheck`.

## Code map (src/)
| File | What it does |
|---|---|
| store.tsx / theme.ts / i18n.ts | notes, categories, theme, EN/FR, persistence |
| screens/Notes, Editor, EditorTools | list + rich editor; link / table / formula / chart sheets; share & export sheet |
| tools.ts | formula evaluator (no eval), table + chart (SVG) builders, HTML sanitiser |
| docx.ts / exporter.ts | HTML -> .docx (pure JS), PDF + DOCX + text sharing |
| net.ts / screens/Transfer | Wi-Fi send/receive and one-scan desktop QR import |
| screens/Library, Reader, library.tsx, readerHtml.ts | books, reading stats, highlights, bookmarks, daily reminder |
| screens/Tasks, tasks.ts | tasks + reminders |
| screens/Vault | PIN / password vault |

## Standalone Android APK
Every push to `mobile-app` triggers **Build Notia Android APK**. The workflow runs TypeScript checks, generates the native Android project, and builds a standalone **Release** APK (JavaScript is bundled; Metro is not required at runtime).

The downloadable artifact is named **Notia-Mobile-arm64-Release-APK**. It targets 64-bit ARM Android phones to avoid packaging emulator and legacy CPU libraries into the same APK. It uses Hermes, R8 code shrinking, resource shrinking, and compressed native libraries. This is a sideload build signed with the repository's debug key; it is not suitable for Play Store publishing. A Play Store build needs a private release keystore.

The exact APK size is reported in the Actions log and will be measured on every build. The 20 MB target is a strict optimization goal, not a size that can be guaranteed from source configuration alone: React Native, Hermes, Expo native modules, and the bundled offline reader all contribute to the final binary. No app feature or reader engine is intentionally removed to chase the size target.

## One-scan desktop QR sync
On desktop, open Share and choose **Sync notes to phone (QR)**. In mobile, open Transfer and tap **Scan desktop QR code**. One scan imports or updates notes by ID; newer versions replace older ones. Both devices must be on the same Wi-Fi, and the desktop share dialog must remain open until import finishes. The QR contains a temporary local-network URL and random token; no cloud backend or second scan is involved.

The mobile scanner checks the Notia URL format, requests the note payload with a timeout, validates the JSON structure, sanitises incoming HTML, and imports the notes. The QR transfer is a desktop-to-phone operation; it does not replace the separate manual Wi-Fi send/receive tools.

## Wi-Fi transfer
Both devices must be on the same Wi-Fi. **Sending** works in Expo Go.
**Receiving** needs a raw TCP server, which Expo Go cannot run. It requires a native build containing `react-native-tcp-socket`; the standalone APK includes this module.

### Protocol (PC <-> phone)
```
POST http://<receiver-ip>:8765/notia/v1/notes
X-Notia-Code: <4-digit code displayed by the receiver>
{"v":1,"notes":[{"id":"abc","title":"...","html":"...","category":"Work"|null,"created":1730000000000,"modified":1730000000000}]}
-> 200 {"ok":true,"count":N} | 401 wrong code | 400 bad body
```
Incoming HTML is sanitised (scripts, event handlers, and javascript: links removed). A note with the same ID is only overwritten by a newer one.

## Notes
- Charts export to Word as data tables (Word has no SVG chart from HTML); in PDF they stay graphical.
- Exports are written to the app cache folder, then shared. If the share sheet fails, PDF falls back to the system print dialog ("Save as PDF").

## Loading screen / icon
The app shows the Notia logo while it loads. The native Android splash from `app.json` only appears in a built APK / development build — Expo Go shows its own splash first.

## Books
Supported: **PDF, EPUB, MOBI / AZW / PRC, DOCX, TXT, HTML**. DRM-protected books and KF8-only AZW3 are not supported; convert unsupported formats to EPUB with Calibre.
- Adding a book copies it into the app's own storage. Several fallback strategies support Downloads, Drive, USB and cloud providers. Maximum: 60 MB per book and 10 books.
- Covers are extracted from PDF, EPUB and MOBI where available; DOCX, TXT and HTML use generated title covers.
- EPUB table-of-contents and in-text links jump inside the book; web links open in the browser.
- Text-book highlights are reapplied when reopened. PDF highlights are listed because PDF pages are rendered as images.

## Reader (offline)
PDF, EPUB and DOCX engines are bundled in `src/readerLibs.ts` (pdfjs-dist, jszip, mammoth); MOBI, TXT and HTML are parsed by `src/readerHtml.ts`. Book HTML is sanitised. PDF pages are rendered on demand and released when far off-screen to reduce memory use.

## Reminders
Android 13+ needs a notification channel before it will show the permission prompt. Tasks creates it first. Use the bell icon on the Tasks screen to fire a test notification in 5 seconds.

<!-- GitHub Actions APK build configuration is in .github/workflows/mobile-apk.yml. -->
