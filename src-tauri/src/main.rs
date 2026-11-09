#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use std::{io::{Read, Write}, net::{IpAddr, TcpListener}, sync::atomic::{AtomicBool, Ordering}, thread, time::{Duration, SystemTime, UNIX_EPOCH}};
use std::{path::Path, sync::Mutex};
use tauri::{menu::{Menu, MenuItem}, tray::{TrayIconBuilder, TrayIconEvent}, Emitter, Manager, WindowEvent};
use tauri_plugin_dialog::DialogExt;
use qrcode::{QrCode, render::svg};

static STOP: AtomicBool = AtomicBool::new(false);
static PENDING: Mutex<Option<String>> = Mutex::new(None);

fn file_arg(args: &[String]) -> Option<String> {
    args.iter().skip(1).find(|a| !a.starts_with("--") && Path::new(a.as_str()).is_file()).cloned()
}

#[tauri::command]
fn take_open_file() -> Option<String> { PENDING.lock().ok()?.take() }

// Hides the window from screenshots, screen recorders and AI screen-capture tools (Windows 10 2004+).
#[tauri::command]
fn privacy(app: tauri::AppHandle, on: bool) {
    if let Some(w) = app.get_webview_window("main") { let _ = w.set_content_protected(on); }
}

fn backups_dir(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    Ok(app.path().document_dir().map_err(|e| e.to_string())?.join("Notia Backups"))
}

#[tauri::command]
fn backup_write(app: tauri::AppHandle, name: String, data: Vec<u8>) -> Result<String, String> {
    let d = backups_dir(&app)?;
    std::fs::create_dir_all(&d).map_err(|e| e.to_string())?;
    let safe: String = name.chars().filter(|c| c.is_ascii_alphanumeric() || "-_.".contains(*c)).collect();
    if !safe.ends_with(".ntia") { return Err("bad name".into()); }
    let p = d.join(&safe);
    std::fs::write(&p, data).map_err(|e| e.to_string())?;
    let mut all = backup_files(&d);
    all.sort_by(|a, b| b.cmp(a));
    for old in all.iter().skip(7) { let _ = std::fs::remove_file(d.join(old)); }
    Ok(p.display().to_string())
}

fn backup_files(d: &Path) -> Vec<String> {
    std::fs::read_dir(d).map(|r| r.filter_map(|e| e.ok()).map(|e| e.file_name().to_string_lossy().to_string()).filter(|n| n.ends_with(".ntia")).collect()).unwrap_or_default()
}

#[tauri::command]
fn backup_list(app: tauri::AppHandle) -> Vec<(String, String)> {
    let Ok(d) = backups_dir(&app) else { return vec![] };
    let mut v = backup_files(&d);
    v.sort_by(|a, b| b.cmp(a));
    v.into_iter().map(|n| { let p = d.join(&n).display().to_string(); (n, p) }).collect()
}

#[tauri::command]
async fn save_file(app: tauri::AppHandle, name: String, data: Vec<u8>) -> Result<bool, String> {
    match app.dialog().file().set_file_name(&name).blocking_save_file() {
        Some(p) => {
            std::fs::write(p.into_path().map_err(|e| e.to_string())?, data).map_err(|e| e.to_string())?;
            Ok(true)
        }
        None => Ok(false),
    }
}

#[tauri::command]
fn read_file(path: String) -> Result<tauri::ipc::Response, String> {
    // Only ebooks and Notia backups can be read from disk.
    let ext = Path::new(&path).extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
    if !["pdf", "epub", "mobi", "azw", "ntia"].contains(&ext.as_str()) { return Err("File type not allowed".into()); }
    std::fs::read(path).map(tauri::ipc::Response::new).map_err(|e| e.to_string())
}

#[tauri::command]
fn bluetooth_send(name: String, data: Vec<u8>) -> Result<(), String> {
    let p = std::env::temp_dir().join(&name);
    std::fs::write(&p, data).map_err(|e| e.to_string())?;
    let _ = std::process::Command::new("explorer").arg(format!("/select,{}", p.display())).spawn();
    std::process::Command::new("fsquirt").spawn().map(|_| ()).map_err(|e| e.to_string())
}

// Pick an address another device on the local network can reach.
// Never advertise loopback (127.0.0.1): that points back to the receiving device.
fn lan_ipv4() -> Result<String, String> {
    let interfaces = local_ip_address::list_afinet_netifas().map_err(|e| format!("Could not inspect network interfaces: {e}"))?;
    let mut candidates: Vec<(u8, String)> = interfaces.into_iter().filter_map(|(name, addr)| {
        let IpAddr::V4(ip) = addr else { return None; };
        if ip.is_loopback() || ip.is_unspecified() || !ip.is_private() { return None; }
        let n = name.to_ascii_lowercase();
        let preference = if n.contains("wi-fi") || n.contains("wifi") || n.contains("wireless") || n.contains("wlan") { 0 }
            else if n.contains("ethernet") || n.contains("en0") { 1 } else { 2 };
        Some((preference, ip.to_string()))
    }).collect();
    candidates.sort_by(|a, b| a.0.cmp(&b.0).then_with(|| a.1.cmp(&b.1)));
    candidates.into_iter().next().map(|(_, ip)| ip)
        .ok_or_else(|| "No private LAN IPv4 address found. Connect this computer to the same Wi-Fi network as your phone and try again.".to_string())
}

#[tauri::command]
fn share_stop() { STOP.store(true, Ordering::SeqCst); }

#[tauri::command]
fn share_start(files: Vec<(String, Vec<u8>)>) -> Result<String, String> {
    STOP.store(true, Ordering::SeqCst);
    thread::sleep(Duration::from_millis(250));
    STOP.store(false, Ordering::SeqCst);
    let l = TcpListener::bind("0.0.0.0:0").map_err(|e| e.to_string())?;
    l.set_nonblocking(true).map_err(|e| e.to_string())?;
    let port = l.local_addr().map_err(|e| e.to_string())?.port();
    let ip = lan_ipv4()?;
    let tok = format!("{:x}", SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(1) ^ port as u64);
    let t2 = tok.clone();
    thread::spawn(move || {
        while !STOP.load(Ordering::SeqCst) {
            let Ok((mut s, _)) = l.accept() else { thread::sleep(Duration::from_millis(100)); continue };
            let _ = s.set_nonblocking(false);
            let _ = s.set_read_timeout(Some(Duration::from_secs(2)));
            let mut buf = [0u8; 2048];
            let n = s.read(&mut buf).unwrap_or(0);
            let req = String::from_utf8_lossy(&buf[..n]).to_string();
            let path = req.split_whitespace().nth(1).unwrap_or("/");
            let pre = format!("/{}/", t2);
            let (ct, extra, body): (&str, String, Vec<u8>) = match path.strip_prefix(&pre) {
                Some(rest) => match rest.split('/').next().and_then(|i| i.parse::<usize>().ok()).and_then(|i| files.get(i)) {
                    Some((nm, d)) => ("application/octet-stream", format!("Content-Disposition: attachment; filename=\"{}\"\r\n", nm), d.clone()),
                    None => {
                        let links: String = files.iter().enumerate().map(|(i, (nm, _))| format!("<p><a href=\"{}/{}\">{}</a></p>", i, nm, nm)).collect();
                        ("text/html; charset=utf-8", String::new(), format!("<meta name=viewport content='width=device-width'><h2>Notia</h2>{}", links).into_bytes())
                    }
                },
                None => { let _ = s.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); continue }
            };
            let head = format!("HTTP/1.1 200 OK\r\nContent-Type: {}\r\n{}Content-Length: {}\r\nConnection: close\r\n\r\n", ct, extra, body.len());
            let _ = s.write_all(head.as_bytes());
            let _ = s.write_all(&body);
        }
    });
    Ok(format!("http://{}:{}/{}/", ip, port, tok))
}

#[tauri::command]
fn share_notes_start(payload: String, token: String) -> Result<String, String> {
    if payload.len() > 20_000_000 { return Err("Notes payload is too large (20 MB maximum)".into()); }
    if token.len() != 32 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("Invalid one-time sharing token".into());
    }
    STOP.store(true, Ordering::SeqCst);
    thread::sleep(Duration::from_millis(250));
    STOP.store(false, Ordering::SeqCst);
    let listener = TcpListener::bind("0.0.0.0:0").map_err(|e| e.to_string())?;
    listener.set_nonblocking(true).map_err(|e| e.to_string())?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();
    let ip = lan_ipv4()?;
    let route = format!("/{}/notes", token);
    let server_route = route.clone();
    let body = payload.into_bytes();
    thread::spawn(move || {
        while !STOP.load(Ordering::SeqCst) {
            let Ok((mut stream, _)) = listener.accept() else {
                thread::sleep(Duration::from_millis(100));
                continue;
            };
            let _ = stream.set_read_timeout(Some(Duration::from_secs(3)));
            let mut request = [0u8; 4096];
            let count = stream.read(&mut request).unwrap_or(0);
            let line = String::from_utf8_lossy(&request[..count]);
            let path = line.split_whitespace().nth(1).unwrap_or("");
            if line.starts_with("GET ") && path == server_route {
                let header = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                let _ = stream.write_all(header.as_bytes());
                let _ = stream.write_all(&body);
            } else {
                let _ = stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            }
        }
    });
    Ok(format!("http://{}:{}{}", ip, port, route))
}

#[tauri::command]
fn qr_svg(text: String) -> Result<String, String> {
    let code = QrCode::new(text.as_bytes()).map_err(|e| e.to_string())?;
    Ok(code.render::<svg::Color>().min_dimensions(280, 280).build())
}

fn show(app: &tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") { let _ = w.show(); let _ = w.unminimize(); let _ = w.set_focus(); }
}

fn main() {
    *PENDING.lock().unwrap() = file_arg(&std::env::args().collect::<Vec<_>>());
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            if let Some(p) = file_arg(&args) { let _ = app.emit("open-file", p); }
            show(app);
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_autostart::init(tauri_plugin_autostart::MacosLauncher::LaunchAgent, Some(vec!["--hidden"])))
        .invoke_handler(tauri::generate_handler![save_file, read_file, bluetooth_send, share_start, share_notes_start, qr_svg, share_stop, take_open_file, privacy, backup_write, backup_list])
        .setup(|app| {
            let open = MenuItem::with_id(app, "show", "Open Notia", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &quit])?;
            TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("Notia")
                .menu(&menu)
                .on_menu_event(|a, e| match e.id.as_ref() { "show" => show(a), "quit" => a.exit(0), _ => {} })
                .on_tray_icon_event(|t, e| if let TrayIconEvent::DoubleClick { .. } = e { show(t.app_handle()) })
                .build(app)?;
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.set_content_protected(true); // privacy shield on by default; the UI can turn it off
                if std::env::args().any(|a| a == "--hidden") && PENDING.lock().map(|p| p.is_none()).unwrap_or(true) { let _ = w.hide(); }
            }
            Ok(())
        })
        .on_window_event(|w, e| if let WindowEvent::CloseRequested { api, .. } = e { api.prevent_close(); let _ = w.hide(); })
        .run(tauri::generate_context!())
        .expect("Notia failed to start");
}
