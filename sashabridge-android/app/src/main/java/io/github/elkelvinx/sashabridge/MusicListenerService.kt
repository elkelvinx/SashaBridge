package io.github.elkelvinx.sashabridge

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

import androidx.core.content.ContextCompat
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class MusicListenerService : NotificationListenerService() {

    private var server: NettyApplicationEngine? = null
    private val trackUpdates = MutableSharedFlow<String>(extraBufferCapacity = 10)

    // 🔥 NUEVO 1: Variable para guardar la conexión de la PC y poder "colgarle"
    private var activeSession: DefaultWebSocketServerSession? = null

    private var puenteActivo = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "io.github.elkelvinx.sashabridge.TOGGLE_BRIDGE") {
                puenteActivo = intent.getBooleanExtra("ESTADO", true)
                if (!puenteActivo) {
                    Log.d("SashaBridge", "🛑 Pausando servidor Ktor y desconectando PC...")
                    CoroutineScope(Dispatchers.IO).launch {
                        // 🔥 NUEVO 2: Le colgamos el teléfono a la PC inmediatamente
                        activeSession?.close(CloseReason(CloseReason.Codes.NORMAL, "Puente Apagado"))
                        // Detenemos el servidor más rápido (100ms)
                        server?.stop(100, 500)
                        server = null
                    }
                } else {
                    Log.d("SashaBridge", "🟢 Reactivando servidor Ktor...")
                    iniciarServidor()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("SashaBridge", "=== SERVICIO ESPÍA INICIADO ===")

        val filter = android.content.IntentFilter("io.github.elkelvinx.sashabridge.TOGGLE_BRIDGE")
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        iniciarServidor()
    }

    private fun iniciarServidor() {
        if (server != null) return

        server = embeddedServer(Netty, port = 8080) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    Log.d("SashaBridge", "=== PC CONECTADA ===")
                    // Guardamos la sesión actual
                    activeSession = this

                    val senderJob = launch {
                        trackUpdates.collect { jsonString ->
                            send(Frame.Text(jsonString))
                        }
                    }

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val comandoBruto = frame.readText()
                                Log.d("SashaBridge", "📥 LLEGÓ ALGO DE LA PC: [$comandoBruto]")
                                ejecutarComandoMultimedia(comandoBruto)
                            }
                        }
                    } finally {
                        senderJob.cancel()
                        activeSession = null // Limpiamos la variable al desconectar
                        Log.d("SashaBridge", "=== PC DESCONECTADA ===")
                    }
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch { server?.start(wait = true) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!puenteActivo) return
        sbn ?: return

        // 🔥 CORRECCIÓN: Detectamos cualquier app de música (Spotify, YT Music, etc.)
        if (sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            actualizarDatosMultimedia()
        }
    }

    private var lastTrackKey: String? = null

    private fun actualizarDatosMultimedia() {
        try {
            val mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, MusicListenerService::class.java)
            val controllers = mediaSessionManager.getActiveSessions(componentName)

            if (controllers.isNotEmpty()) {
                val controller = controllers[0]
                val metadata = controller.metadata
                val playbackState = controller.playbackState

                val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Desconocido"
                val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Desconocido"

                val json = JSONObject()
                json.put("title", title)
                json.put("artist", artist)
                json.put("duration", metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L)

                val state = playbackState?.state
                json.put("isPlaying", state == android.media.session.PlaybackState.STATE_PLAYING)
                json.put("position", playbackState?.position ?: 0L)

                // 🖼️ Solo mandamos la carátula cuando cambia la canción
                val trackKey = "$title|$artist"
                if (trackKey != lastTrackKey) {
                    lastTrackKey = trackKey
                    val artBitmap = metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)

                    if (artBitmap != null) {
                        val scaled = Bitmap.createScaledBitmap(artBitmap, 300, 300, true)
                        val stream = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                        json.put("albumArt", Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP))
                        Log.d("SashaBridge", "🖼️ Carátula nueva incluida (${stream.size()} bytes)")
                    }
                }

                trackUpdates.tryEmit(json.toString())
            }
        } catch (e: Exception) {
            Log.e("SashaBridge", "Error extrayendo metadatos: ${e.message}")
        }
    }

    private fun ejecutarComandoMultimedia(comandoRaw: String) {
        // 🔥 NUEVO 3: Si el puente está apagado, nos volvemos sordos a los comandos de teclado
        if (!puenteActivo) {
            Log.d("SashaBridge", "🛡️ Comando bloqueado: El puente está apagado.")
            return
        }

        val comando = comandoRaw.trim()
        Log.d("SashaBridge", "🛠️ Intentando ejecutar comando limpio: [$comando]")

        try {
            val mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, MusicListenerService::class.java)

            val controllers = mediaSessionManager.getActiveSessions(componentName)
            Log.d("SashaBridge", "📊 Reproductores activos encontrados: ${controllers.size}")

            if (controllers.isNotEmpty()) {
                val mainController = controllers[0]
                Log.d("SashaBridge", "🎯 Mandando comando a la app: ${mainController.packageName}")

                val transportControls = mainController.transportControls

                when { // OJO: cambiamos "when (comando)" por "when" a secas
                    comando == "NEXT" -> {
                        transportControls.skipToNext()
                    }
                    comando == "PREV" -> {
                        transportControls.skipToPrevious()
                    }
                    comando == "PLAY_PAUSE" -> {
                        val state = mainController.playbackState?.state
                        if (state == android.media.session.PlaybackState.STATE_PLAYING) {
                            transportControls.pause()
                        } else {
                            transportControls.play()
                        }
                    }
                    // 🔥 NUEVO: Atrapamos el comando SEEK para adelantar la canción
                    comando.startsWith("SEEK:") -> {
                        val ms = comando.removePrefix("SEEK:").toLongOrNull()
                        if (ms != null) transportControls.seekTo(ms)
                    }
                    else -> Log.w("SashaBridge", "❓ Comando desconocido: $comando")
                }
            } else {
                Log.w("SashaBridge", "⚠️ No hay sesiones activas. Intentando Plan B (Fuerza Bruta)...")
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

                val keyCode = when (comando) {
                    "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "PREV" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "PLAY_PAUSE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    else -> {
                        Log.w("SashaBridge", "❓ Comando desconocido en Plan B: $comando")
                        return
                    }
                }

                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                Log.d("SashaBridge", "🥊 Fuerza bruta aplicada exitosamente para keyCode: $keyCode")
            }

        } catch (e: Exception) {
            Log.e("SashaBridge", "❌ ERROR FATAL al ejecutar comando: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        CoroutineScope(Dispatchers.IO).launch {
            activeSession?.close()
            server?.stop(100, 500)
        }
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}