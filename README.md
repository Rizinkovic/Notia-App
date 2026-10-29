# Notia
Offline note-taking, tasks and planner for Windows 10/11. Made by [Song Richard](https://srichard.site). MIT.
Features: notes, categories, rich editor, code colorizing, tasks + month planner, reminders, PDF library with reading progress, sharing (Wi-Fi, Bluetooth, social, email, Drive), FR/EN, light/dark.
Build: run the *build-windows* workflow (or push a `v*` tag), download `Notia-Setup`. Docs: `docs/system-design-and-workflow.md`.

## Download

[⬇️ Download Notia for Windows](https://github.com/Rizinkovic/Notia-App/releases/latest)

Works on Windows 10 and 11. Run the installer and follow the steps. If Windows shows "Windows protected your PC", click **More info**, then **Run anyway**.

## What's new in 1.2

Ebook "Open in Notia" (right-click, PDF/EPUB/MOBI) · cover-flow / list library · full ebook reader (TOC, Ctrl+L full screen, ↓ to keep reading, highlights, bookmarks, search, copy to note with book + page) · find in note · spell-check language auto-detect · more fonts + tooltips · dynamic tables · resizable notes list · image resize/align · diagrams · charts (xlsx/CSV import) · offline LaTeX formulas (KaTeX) · DOCX/PDF export with images · PIN/password lock with AES-256-GCM encryption at rest · locked categories · passwords vault · encrypted `.ntia` backups · update notifier · settings with shortcut list · privacy shield (blocks screen capture).

Build: `npm install && npm run vendor && npm run build` (vendor now also copies KaTeX).
