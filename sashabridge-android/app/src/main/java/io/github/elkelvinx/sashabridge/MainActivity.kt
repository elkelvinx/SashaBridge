package io.github.elkelvinx.sashabridge

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.elkelvinx.sashabridge.bridge.BridgeRepository
import kotlinx.coroutines.launch

/**
 * Screen with the bridge on/off button.
 *
 * The button writes [BridgeRepository.enabled] and the screen shows its value, so it always
 * matches what the service does, also after the activity is recreated (A7). Phase 6 adds the
 * server status and the current track. Main thread.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Revisar permisos (tu código original)
        if (!hasNotificationAccess()) {
            Toast.makeText(this, "Da los permisos a esta app AHORA", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        val repository = BridgeRepository.get(this)
        val btnToggle = findViewById<Button>(R.id.btnToggleBridge)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnToggle.setOnClickListener { repository.setEnabled(!repository.enabled.value) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.enabled.collect { enabled ->
                    if (enabled) {
                        btnToggle.text = "DESCONECTAR"
                        btnToggle.setBackgroundColor(Color.parseColor("#DA3633")) // Rojo
                        tvStatus.text = "PUENTE ACTIVO"
                        tvStatus.setTextColor(Color.parseColor("#3FB950")) // Verde
                    } else {
                        btnToggle.text = "CONECTAR"
                        btnToggle.setBackgroundColor(Color.parseColor("#3FB950")) // Verde
                        tvStatus.text = "PUENTE PAUSADO"
                        tvStatus.setTextColor(Color.parseColor("#8B949E")) // Gris
                    }
                }
            }
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledListeners.contains(packageName)
    }
}
