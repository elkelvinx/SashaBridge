package com.example.minibridgeserver

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

class MainActivity : AppCompatActivity() {

    // Variable para saber si el puente está prendido o apagado
    private var puenteActivo = true

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

        // Configurar el botón
        val btnToggle = findViewById<Button>(R.id.btnToggleBridge)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnToggle.setOnClickListener {
            puenteActivo = !puenteActivo // Cambiamos el estado

            // Enviamos el mensaje por "radio" al servicio
            val intent = Intent("com.example.minibridgeserver.TOGGLE_BRIDGE")
            intent.putExtra("ESTADO", puenteActivo)
            sendBroadcast(intent)

            // Cambiamos los colores y textos de la interfaz
            if (puenteActivo) {
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

    private fun hasNotificationAccess(): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledListeners.contains(packageName)
    }
}