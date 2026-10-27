// ============================================================
//  MiniBridge — VERSIÓN GORDA (Windows 10) - CORREGIDA
// ============================================================

use base64::{engine::general_purpose, Engine as _};
use futures_util::{SinkExt, StreamExt};
use souvlaki::{MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig};
use std::env;
use std::fs::File;
use std::io::Write;
use std::process::Command;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;

mod smtc {
    use std::sync::{Arc, Mutex};
    use windows_sys::Win32::Foundation::{HWND, LRESULT};
    use windows_sys::Win32::Graphics::Gdi::HBRUSH;
    use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DefWindowProcW, DispatchMessageW, GetMessageW, RegisterClassW, CS_HREDRAW,
        CS_VREDRAW, MSG, WNDCLASSW, WS_OVERLAPPED,
    };

    fn to_wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }

    unsafe extern "system" fn wnd_proc(hwnd: HWND, msg: u32, wparam: usize, lparam: isize) -> LRESULT {
        unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) }
    }

    pub fn create_and_pump(hwnd_out: Arc<Mutex<Option<isize>>>) {
        unsafe {
            let hinstance = GetModuleHandleW(std::ptr::null());
            let class_name = to_wide("MiniBridgeMsgWnd");

            let wc = WNDCLASSW {
                style: CS_HREDRAW | CS_VREDRAW,
                lpfnWndProc: Some(wnd_proc),
                cbClsExtra: 0,
                cbWndExtra: 0,
                hInstance: hinstance,
                hIcon: std::ptr::null_mut(),
                hCursor: std::ptr::null_mut(),
                hbrBackground: std::ptr::null_mut() as HBRUSH,
                lpszMenuName: std::ptr::null(),
                lpszClassName: class_name.as_ptr(),
            };

            RegisterClassW(&wc);

            let hwnd = CreateWindowExW(
                0,
                class_name.as_ptr(),
                to_wide("MiniBridge").as_ptr(),
                WS_OVERLAPPED,
                0, 0, 0, 0,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                hinstance,
                std::ptr::null(),
            );

            *hwnd_out.lock().unwrap() = Some(hwnd as isize);

            let mut msg: MSG = std::mem::zeroed();
            while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) != 0 {
                DispatchMessageW(&msg);
            }
        }
    }
}

fn json_str<'a>(value: &'a serde_json::Value, key: &str) -> Option<&'a str> {
    value.get(key).and_then(serde_json::Value::as_str)
}

fn json_duration(value: &serde_json::Value, key: &str) -> Option<Duration> {
    value.get(key).and_then(serde_json::Value::as_u64).map(Duration::from_millis)
}

#[tokio::main]
async fn main() {
    println!("🚀 Iniciando MiniBridge [VERSIÓN GORDA]...");

    Command::new(".\\scrcpy-win64-v4.0\\adb.exe").args(["forward", "tcp:8080", "tcp:8080"]).output().ok();

    let scrcpy_child = Arc::new(Mutex::new(
        Command::new(".\\scrcpy-win64-v4.0\\scrcpy.exe")
            .args(["--no-video", "--no-window"])
            .spawn()
            .ok(),
    ));

    let hwnd_shared: Arc<Mutex<Option<isize>>> = Arc::new(Mutex::new(None));
    let hwnd_clone = Arc::clone(&hwnd_shared);

    thread::spawn(move || {
        smtc::create_and_pump(hwnd_clone);
    });

    let hwnd_val = loop {
        if let Some(h) = *hwnd_shared.lock().unwrap() { break h; }
        thread::sleep(Duration::from_millis(1));
    };

    let mut controls = MediaControls::new(PlatformConfig {
        dbus_name: "minibridge",
        display_name: "MiniBridge",
        hwnd: Some(hwnd_val as *mut std::ffi::c_void),
    }).expect("❌ Error al crear MediaControls");

    let url = "ws://127.0.0.1:8080/";
    let (ws_stream, _) = connect_async(url).await.expect("❌ Fallo conexión teléfono.");
    println!("✅ Conexión exitosa con el teléfono.");

    let (mut write, mut read) = ws_stream.split();
    let (cmd_tx, mut cmd_rx) = mpsc::channel::<String>(10);

    let cmd_tx_smtc = cmd_tx.clone();
    controls.attach(move |event| {
        println!("📻 Evento de Windows: {:?}", event);
        match event {
            souvlaki::MediaControlEvent::Play | souvlaki::MediaControlEvent::Pause | souvlaki::MediaControlEvent::Toggle => {
                let _ = cmd_tx_smtc.try_send("PLAY_PAUSE".to_string());
            }
            souvlaki::MediaControlEvent::Next => {
                let _ = cmd_tx_smtc.try_send("NEXT".to_string());
            }
            souvlaki::MediaControlEvent::Previous => {
                let _ = cmd_tx_smtc.try_send("PREV".to_string());
            }
            souvlaki::MediaControlEvent::SetPosition(MediaPosition(dur)) => {
                let _ = cmd_tx_smtc.try_send(format!("SEEK:{}", dur.as_millis()));
            }
            _ => {}
        }
    }).expect("❌ Error al registrar eventos");

    let scrcpy_for_ctrlc = Arc::clone(&scrcpy_child);
    tokio::spawn(async move {
        tokio::signal::ctrl_c().await.unwrap();
        if let Ok(mut guard) = scrcpy_for_ctrlc.lock() {
            if let Some(ref mut child) = *guard { child.kill().ok(); }
        }
        Command::new(".\\scrcpy-win64-v4.0\\adb.exe").args(["forward", "--remove", "tcp:8080"]).output().ok();
        std::process::exit(0);
    });

    let mut last_cover_uri: Option<String> = None;
    let mut cover_counter = 0;

    loop {
        tokio::select! {
            msg = read.next() => {
                if let Some(Ok(Message::Text(text))) = msg {
                    let v: serde_json::Value = serde_json::from_str(&text).unwrap_or_default();
                    
                    let title = json_str(&v, "title").unwrap_or("Desconocido");
                    let artist = json_str(&v, "artist").unwrap_or("Desconocido");
                    let is_playing = v.get("isPlaying").and_then(serde_json::Value::as_bool).unwrap_or(true);
                    let duration = json_duration(&v, "duration");
                    let position = json_duration(&v, "position").unwrap_or_default();
                    let has_art = v.get("albumArt").is_some();

                    // 🔥 AHORA SÍ VEMOS LO QUE PASA EN CONSOLA
                    println!("🎵 Info recibida -> Título: [{}], Estado: [{}], Foto: [{}]", title, is_playing, has_art);

                    // Extraer y guardar carátula si viene en este mensaje
                    if let Some(base64_str) = json_str(&v, "albumArt") {
                        if let Ok(image_bytes) = general_purpose::STANDARD.decode(base64_str) {
                            cover_counter += 1;
                            let cover_path = env::temp_dir().join(format!("minibridge_cover_{}.jpg", cover_counter));
                            if let Ok(mut file) = File::create(&cover_path) {
                                file.write_all(&image_bytes).ok();
                                
                                // 🔥 EL TRUCO MÁGICO: file:// + la ruta nativa de Windows
                                last_cover_uri = Some(format!("file://{}", cover_path.display()));
                                println!("🖼️ Carátula lista en: {}", last_cover_uri.as_deref().unwrap_or(""));
                            }
                        }
                    }

                    // Forzamos la actualización de Windows SIEMPRE que llegue un mensaje
                    controls.set_metadata(MediaMetadata {
                        title: Some(title),
                        artist: Some(artist),
                        album: None,
                        duration,
                        cover_url: last_cover_uri.as_deref(), 
                    }).ok();

                    let pb_state = if is_playing {
                        MediaPlayback::Playing { progress: Some(MediaPosition(position)) }
                    } else {
                        MediaPlayback::Paused { progress: Some(MediaPosition(position)) }
                    };
                    controls.set_playback(pb_state).ok();
                }
            }

            Some(cmd) = cmd_rx.recv() => {
                let _ = write.send(Message::Text(cmd.into())).await;
            }
        }
    }
}