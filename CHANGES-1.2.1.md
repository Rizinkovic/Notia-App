# Notia 1.2.1 — audit fixes
- Cargo.toml: edition "2026" does not exist -> "2021" (build would fail).
- hooks.nsh: NOTIA_VERB macro was used before being defined (NSIS error) -> moved above.
- system.js: after unlocking a sealed session and choosing "Remove protection", save() silently stopped (data loss until restart). Fixed with LOCKED()/REM flag.
- docs.js: ordered lists export as "1. 2." instead of bullets (DOCX/PDF/TXT).
## Still unverified (needs a real Windows build)
Rust compile, NSIS right-click entry, PDF/EPUB reader UI, cover flow, real WebView2 spell-check language, privacy shield.
## Known limitations
DOCX loses inline bold/italic/colour; PDF is Latin-only; pdf.js has no CJK cmaps/standard fonts bundled; privacy shield is ON by default (screenshots/screen-share show black; toggle in Settings).

## Library hotfix (also 1.2.1)
- Books added in older versions had no `fmt` field -> treated as MOBI ("Not a MOBI file"), list view crashed, covers never generated. Format is now always derived from the file extension.
- Cover generation is retried (a failed attempt used to be saved as "no cover" forever).
- Removed the lock/unlock emoji from categories (app lock + password vault remain).
- Version really bumped to 1.2.1 in app, package.json, tauri.conf.json, Cargo.toml.
