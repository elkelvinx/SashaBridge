// ============================================================
//  MiniBridge — v1.2  (SMTC + graceful shutdown)
//  Cambios sobre la versión estable:
//    [NUEVO] Bloque `smtc` al inicio: crea una message-only window
//            invisible para darle un HWND válido a souvlaki.
//    [NUEVO] `controls` actualiza la barra multimedia de Windows
//            cada vez que llega una canción.
//    [NUEVO] scrcpy se guarda como `Child` para poder matarlo
//            limpiamente con Ctrl+C.
//    El resto (rdev, WebSocket, select!, filtro de duplicados)
//    es idéntico a tu versión estable.
// ============================================================

use futures_util::{SinkExt, StreamExt};
use rdev::{listen, Event, EventType, Key};
use souvlaki::{MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig};
use std::process::Command;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;

// ── [NUEVO] Hidden top-level window ─────────────────────────────────────────
// SMTC en Windows requiere una ventana top-level real. La mantenemos oculta
// para que no aparezca en pantalla ni en la barra de tareas, pero sí sea un
// HWND válido para souvlaki.
mod smtc {
    use std::sync::{Arc, Mutex};

    use windows_sys::Win32::Foundation::{HWND, LRESULT};
    use windows_sys::Win32::Graphics::Gdi::HBRUSH;
    use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DefWindowProcW, DispatchMessageW, GetMessageW,
        RegisterClassW, CS_HREDRAW, CS_VREDRAW, MSG, WNDCLASSW, WS_OVERLAPPED,
    };

    fn to_wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }

    // edition 2024: unsafe fn ya no hace implícito el cuerpo; necesitas
    // el bloque unsafe {} explícito para llamar DefWindowProcW.
    unsafe extern "system" fn wnd_proc(
        hwnd: HWND,
        msg: u32,
        wparam: usize,
        lparam: isize,
    ) -> LRESULT {
        unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) }
    }

    pub fn create_and_pump(hwnd_out: Arc<Mutex<Option<isize>>>) {
        unsafe {
            let hinstance = GetModuleHandleW(std::ptr::null());
            assert!(
                !hinstance.is_null(),
                "GetModuleHandleW devolvió null"
            );

            let class_name = to_wide("MiniBridgeMsgWnd");

            let wc = WNDCLASSW {
                style: CS_HREDRAW | CS_VREDRAW,
                lpfnWndProc: Some(wnd_proc),
                cbClsExtra: 0,
                cbWndExtra: 0,
                hInstance: hinstance,
                hIcon: std::ptr::null_mut(),   // HICON = *mut c_void
                hCursor: std::ptr::null_mut(), // HCURSOR = *mut c_void
                hbrBackground: std::ptr::null_mut() as HBRUSH,
                lpszMenuName: std::ptr::null(),
                lpszClassName: class_name.as_ptr(),
            };

            // Si la clase ya existe (re-run en dev), RegisterClassW da 0
            // pero el error es CLASS_ALREADY_EXISTS — lo ignoramos.
            RegisterClassW(&wc);

            let hwnd = CreateWindowExW(
                0,                              // dwExStyle
                class_name.as_ptr(),            // lpClassName
                to_wide("MiniBridge").as_ptr(), // lpWindowName
                WS_OVERLAPPED,                  // dwStyle
                0, 0, 0, 0,                     // x, y, w, h
                std::ptr::null_mut(),           // top-level real, but hidden
                std::ptr::null_mut(),           // hMenu
                hinstance,                      // hInstance
                std::ptr::null(),               // lpParam
            );

            assert!(
                !hwnd.is_null(),
                "CreateWindowExW falló — no se pudo crear la ventana oculta"
            );

            // Publicamos el HWND para que main() pueda usarlo con souvlaki.
            *hwnd_out.lock().unwrap() = Some(hwnd as isize);

            // Message loop: obligatorio para que SMTC funcione.
            let mut msg: MSG = std::mem::zeroed();
            while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) != 0 {
                DispatchMessageW(&msg);
            }
        }
    }
}
// ── fin del módulo smtc ───────────────────────────────────────────────────────

fn json_str<'a>(value: &'a serde_json::Value, keys: &[&str]) -> Option<&'a str> {
    keys.iter().find_map(|key| value.get(*key).and_then(serde_json::Value::as_str))
}

fn json_duration(value: &serde_json::Value, keys: &[&str]) -> Option<Duration> {
    keys.iter().find_map(|key| {
        let raw = value.get(*key)?;

        if let Some(number) = raw.as_u64() {
            let key_lower = key.to_ascii_lowercase();
            if key_lower.contains("ms") || key_lower.contains("mill") {
                return Some(Duration::from_millis(number));
            }
            if number > 10_000 {
                return Some(Duration::from_millis(number));
            }
            return Some(Duration::from_secs(number));
        }

        if let Some(number) = raw.as_f64() {
            let key_lower = key.to_ascii_lowercase();
            if key_lower.contains("ms") || key_lower.contains("mill") {
                return Some(Duration::from_millis(number.max(0.0) as u64));
            }
            return Some(Duration::from_secs_f64(number.max(0.0)));
        }

        None
    })
}

fn build_playback(value: &serde_json::Value, fallback: &MediaPlayback) -> MediaPlayback {
    let progress = json_duration(value, &["position", "progress", "elapsed", "current_time"])
        .map(MediaPosition);

    let paused = value
        .get("paused")
        .and_then(serde_json::Value::as_bool)
        .or_else(|| {
            value
                .get("isPlaying")
                .and_then(serde_json::Value::as_bool)
                .map(|playing| !playing)
        })
        .or_else(|| {
            value
                .get("playing")
                .and_then(serde_json::Value::as_bool)
                .map(|playing| !playing)
        });

    match paused {
        Some(true) => MediaPlayback::Paused { progress },
        Some(false) => MediaPlayback::Playing { progress },
        None => fallback.clone(),
    }
}

#[tokio::main]
async fn main() {
    println!("🚀 Iniciando sistema MiniBridge v1.2...");

    // ── 1. ADB ────────────────────────────────────────────────────────────────
    Command::new("adb")
        .args(["forward", "tcp:8080", "tcp:8080"])
        .spawn()
        .ok();

    // ── 2. scrcpy — guardamos el Child para matarlo en Ctrl+C ─────────────────
    // [CAMBIO] antes era .spawn().ok() descartando el proceso.
    // Ahora lo guardamos en Arc<Mutex<>> para accederlo desde el handler de señal.
    let scrcpy_child = Arc::new(Mutex::new(
        Command::new(".\\scrcpy-win64-v4.0\\scrcpy.exe")
            .args(["--no-video", "--no-window"])
            .spawn()
            .ok(), // Option<Child>
    ));

    println!("✅ ADB y scrcpy iniciados.");

    // ── 3. [NUEVO] Message-only window + souvlaki ─────────────────────────────
    let hwnd_shared: Arc<Mutex<Option<isize>>> = Arc::new(Mutex::new(None));
    let hwnd_clone = Arc::clone(&hwnd_shared);

    thread::spawn(move || {
        smtc::create_and_pump(hwnd_clone);
    });

    // Esperamos a que el hilo Win32 publique el HWND.
    // En la práctica <1 ms; el loop no da más de 2-3 iteraciones.
    let hwnd_val = loop {
        let v = *hwnd_shared.lock().unwrap();
        if let Some(h) = v {
            break h;
        }
        thread::sleep(std::time::Duration::from_millis(1));
    };
    println!("✅ Ventana Win32 oculta lista (HWND: {}).", hwnd_val);

    let mut controls = MediaControls::new(PlatformConfig {
        dbus_name: "minibridge",
        display_name: "MiniBridge",
        hwnd: Some(hwnd_val as *mut std::ffi::c_void),
    })
    .expect("❌ souvlaki: error al crear MediaControls");

    // Sin set_playback inicial los SMTC no aparecen aunque actualices metadata.
    controls
        .set_playback(MediaPlayback::Playing { progress: None })
        .ok();

    controls
    .attach(|event: souvlaki::MediaControlEvent| {
        println!("📻 Evento desde SMTC: {:?}", event);
    })
    .expect("❌ Error al registrar souvlaki con Windows");
    println!("✅ Controles multimedia de Windows (SMTC) activos.");

    // ── 4. WebSocket ──────────────────────────────────────────────────────────
    let url = "ws://127.0.0.1:8080/";
    println!("🔌 Conectando a {}...", url);

    let (ws_stream, _) = connect_async(url)
        .await
        .expect("❌ No se pudo conectar. ¿Cable USB conectado y ADB activo?");

    println!("✅ Conexión exitosa con el teléfono.");

    let (mut write, mut read) = ws_stream.split();
    let (cmd_tx, mut cmd_rx) = mpsc::channel::<String>(10);
    let mut playback_state = MediaPlayback::Playing { progress: None };

    // ── 5. Detector de teclas (idéntico a tu versión estable) ─────────────────
    let cmd_tx_clone = cmd_tx.clone();
    thread::spawn(move || {
        let callback = move |event: Event| {
            if let EventType::KeyPress(key) = event.event_type {
                match key {
                    Key::Unknown(176) => { let _ = cmd_tx_clone.try_send("NEXT".to_string()); }
                    Key::Unknown(177) => { let _ = cmd_tx_clone.try_send("PREV".to_string()); }
                    Key::Unknown(179) => { let _ = cmd_tx_clone.try_send("PLAY_PAUSE".to_string()); }
                    _ => {}
                }
            }
        };
        if let Err(e) = listen(callback) {
            eprintln!("⚠️ Error en detector de teclado: {:?}", e);
        }
    });

    // ── 6. [NUEVO] Ctrl+C — graceful shutdown ─────────────────────────────────
    let scrcpy_for_ctrlc = Arc::clone(&scrcpy_child);
    tokio::spawn(async move {
        tokio::signal::ctrl_c()
            .await
            .expect("Error registrando Ctrl+C");
        println!("\n🛑 Cerrando sistema...");
        if let Ok(mut guard) = scrcpy_for_ctrlc.lock() {
            if let Some(ref mut child) = *guard {
                child.kill().ok();
                println!("✅ scrcpy terminado.");
            }
        }
        // Liberamos el túnel ADB para no dejar el puerto ocupado.
        Command::new("adb")
            .args(["forward", "--remove", "tcp:8080"])
            .spawn()
            .ok();
        std::process::exit(0);
    });

    // ── 7. Bucle principal (idéntico a tu versión estable + update SMTC) ──────
    let mut ultimo_mensaje = String::new();
    println!(
        "🎧 Sistema listo. Escuchando música y teclas...\n-----------------------------------"
    );

    loop {
        tokio::select! {
            msg = read.next() => {
                match msg {
                    Some(Ok(Message::Text(text))) => {
                        if text != ultimo_mensaje {
                            println!("🎵 Sonando: {}", text);

                            // [NUEVO] Actualizar SMTC de Windows
                            let v: serde_json::Value =
                                serde_json::from_str(&text).unwrap_or_default();
                            let title = json_str(&v, &["title", "track", "song", "name"])
                                .unwrap_or("Desconocido");
                            let artist = json_str(&v, &["artist", "author", "creator", "channel"])
                                .unwrap_or("Desconocido");
                            let album = json_str(&v, &["album", "record", "collection"]);
                            let cover_url = json_str(
                                &v,
                                &["cover_url", "coverUrl", "artUrl", "art_url", "thumbnail"],
                            );
                            let duration = json_duration(
                                &v,
                                &["duration", "length", "track_duration", "total_duration"],
                            );

                            playback_state = build_playback(&v, &playback_state);

                            controls.set_metadata(MediaMetadata {
                                title: Some(title),
                                artist: Some(artist),
                                album,
                                cover_url,
                                duration,
                            }).ok();
                            controls.set_playback(playback_state.clone()).ok();

                            ultimo_mensaje = text.to_string();
                        }
                    }
                    Some(Err(e)) => {
                        eprintln!("⚠️ Error leyendo WebSocket: {:?}", e);
                        break;
                    }
                    None => {
                        println!("🔌 El teléfono cerró la conexión.");
                        break;
                    }
                    _ => {}
                }
            }

            Some(cmd) = cmd_rx.recv() => {
                println!("📤 Tecla detectada → enviando: [{}]", cmd);
                let is_play_pause = cmd == "PLAY_PAUSE";

                if let Err(e) = write.send(Message::Text(cmd.into())).await {
                    eprintln!("❌ Error enviando comando: {}", e);
                    break;
                }

                if is_play_pause {
                    playback_state = match playback_state {
                        MediaPlayback::Playing { progress } => MediaPlayback::Paused { progress },
                        MediaPlayback::Paused { progress } => MediaPlayback::Playing { progress },
                        MediaPlayback::Stopped => MediaPlayback::Playing { progress: None },
                    };
                    controls.set_playback(playback_state.clone()).ok();
                }
            }
        }
    }

    // Si salimos del loop por desconexión (no Ctrl+C), limpiamos igual.
    if let Ok(mut guard) = scrcpy_child.lock() {
        if let Some(ref mut child) = *guard {
            child.kill().ok();
        }
    }
}