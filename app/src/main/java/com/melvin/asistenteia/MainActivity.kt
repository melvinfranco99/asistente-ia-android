package com.melvin.asistenteia

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView
    private lateinit var txtPerms: TextView
    private lateinit var btnToggle: MaterialButton
    private val listener: () -> Unit = { refresh() }

    private val runtimePerms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)
        txtPerms = findViewById(R.id.txtPerms)
        btnToggle = findViewById(R.id.btnToggle)

        val edtApiKey = findViewById<EditText>(R.id.edtApiKey)
        val spnModel = findViewById<Spinner>(R.id.spnModel)
        val edtWake = findViewById<EditText>(R.id.edtWake)
        val edtCountry = findViewById<EditText>(R.id.edtCountry)
        val swContinuous = findViewById<MaterialSwitch>(R.id.swContinuous)
        val swSpeak = findViewById<MaterialSwitch>(R.id.swSpeak)
        val edtCommand = findViewById<EditText>(R.id.edtCommand)

        edtApiKey.setText(prefs.apiKey)
        spnModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Prefs.MODELS)
        spnModel.setSelection(Prefs.MODELS.indexOf(prefs.model).coerceAtLeast(0))
        edtWake.setText(prefs.wakeWord)
        edtCountry.setText(prefs.countryCode)
        swContinuous.isChecked = prefs.continuous
        swSpeak.isChecked = prefs.speak

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            prefs.apiKey = edtApiKey.text.toString()
            prefs.model = spnModel.selectedItem as String
            prefs.wakeWord = edtWake.text.toString()
            prefs.countryCode = edtCountry.text.toString()
            prefs.continuous = swContinuous.isChecked
            prefs.speak = swSpeak.isChecked
            Toast.makeText(this, "Ajustes guardados", Toast.LENGTH_SHORT).show()
            if (AssistantService.running) AssistantService.start(this) // aplica el modo de escucha
        }

        btnToggle.setOnClickListener {
            if (AssistantService.running) AssistantService.stop(this)
            else if (checkReady()) AssistantService.start(this)
            btnToggle.postDelayed({ refresh() }, 400)
        }
        findViewById<MaterialButton>(R.id.btnTalk).setOnClickListener {
            if (checkReady()) AssistantService.start(this, AssistantService.ACTION_LISTEN)
        }
        val send = {
            val t = edtCommand.text.toString().trim()
            if (t.isNotEmpty() && checkReady()) {
                AssistantService.start(this, AssistantService.ACTION_TEXT, t)
                edtCommand.setText("")
            }
        }
        findViewById<MaterialButton>(R.id.btnSend).setOnClickListener { send() }
        edtCommand.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }

        findViewById<MaterialButton>(R.id.btnPerms).setOnClickListener {
            ActivityCompat.requestPermissions(this, runtimePerms.toTypedArray(), 1)
        }
        findViewById<MaterialButton>(R.id.btnAccess).setOnClickListener { showAccessibilityHelp() }
        findViewById<MaterialButton>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener { requestBattery() }

        if (prefs.apiKey.isBlank()) edtApiKey.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        EventLog.addListener(listener)
        refresh()
    }

    override fun onPause() {
        EventLog.removeListener(listener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun checkReady(): Boolean {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, runtimePerms.toTypedArray(), 1)
            return false
        }
        if (prefs.apiKey.isBlank()) {
            Toast.makeText(this, "Introduce tu clave API de Anthropic y pulsa Guardar", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun showAccessibilityHelp() {
        AlertDialog.Builder(this)
            .setTitle("Control de pantalla")
            .setMessage(
                "Para que el asistente pueda pulsar botones y escribir en otras apps (por ejemplo, escribir tu pregunta en ChatGPT o pulsar grabar en la cámara), " +
                    "activa “Asistente IA” en Ajustes › Accesibilidad › Apps instaladas.\n\n" +
                    "Si Android dice que es un “ajuste restringido”: ve a Ajustes › Apps › Asistente IA, menú ⋮ › “Permitir ajustes restringidos”, y vuelve a intentarlo.\n\n" +
                    "El asistente solo lee la pantalla cuando le das una orden."
            )
            .setPositiveButton("Abrir ajustes") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun requestBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Ya está sin restricciones", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun refresh() {
        txtStatus.text = if (AssistantService.running) "● ${EventLog.status}" else "○ Detenido"
        btnToggle.text = if (AssistantService.running) "Detener asistente" else "Activar en segundo plano"
        txtLog.text = EventLog.text().ifEmpty { "Aquí verás las órdenes y lo que hace el asistente.\n\nPrueba: “${prefs.wakeWord}, abre la cámara”" }
        val ok = "✅"; val no = "❌"
        val pm = getSystemService(PowerManager::class.java)
        txtPerms.text = buildString {
            append(if (granted(Manifest.permission.RECORD_AUDIO)) ok else no).append(" Micrófono\n")
            append(if (granted(Manifest.permission.READ_CONTACTS)) ok else no).append(" Contactos\n")
            append(if (granted(Manifest.permission.CALL_PHONE)) ok else no).append(" Llamadas\n")
            append(if (AgentAccessibilityService.isEnabled(this@MainActivity)) ok else no).append(" Control de pantalla\n")
            append(if (Settings.canDrawOverlays(this@MainActivity)) ok else no).append(" Abrir apps en segundo plano\n")
            append(if (pm.isIgnoringBatteryOptimizations(packageName)) ok else no).append(" Sin restricción de batería")
        }
    }
}
