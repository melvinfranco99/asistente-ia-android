package com.melvin.asistenteia

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.Normalizer

/** Definición de una herramienta que el modelo puede invocar. */
data class ToolSpec(
    val name: String,
    val description: String,
    val properties: Map<String, Map<String, Any>> = emptyMap(),
    val required: List<String> = emptyList(),
)

private fun str(desc: String) = mapOf("type" to "string", "description" to desc)
private fun int(desc: String) = mapOf("type" to "integer", "description" to desc)
private fun bool(desc: String) = mapOf("type" to "boolean", "description" to desc)
private fun enumOf(desc: String, vararg values: String) = mapOf("type" to "string", "description" to desc, "enum" to values.toList())

/** Implementación de todas las acciones que el asistente puede hacer en el móvil. */
class PhoneTools(private val ctx: Context, private val prefs: Prefs) {

    val specs: List<ToolSpec> = listOf(
        ToolSpec("open_app", "Abre una aplicación instalada por su nombre (p. ej. 'WhatsApp', 'ChatGPT', 'Spotify', 'Ajustes'). Si no la encuentra, devuelve nombres parecidos.",
            mapOf("name" to str("Nombre de la app tal como lo diría el usuario")), listOf("name")),
        ToolSpec("list_apps", "Lista las apps instaladas (nombre y paquete), opcionalmente filtradas por texto.",
            mapOf("filter" to str("Texto para filtrar; vacío para todas"))),
        ToolSpec("open_camera", "Abre la cámara en modo foto, vídeo o selfie. Para hacer la foto o empezar a grabar, después lee la pantalla y pulsa el botón disparador/grabar.",
            mapOf("mode" to enumOf("Modo de la cámara", "photo", "video", "selfie")), listOf("mode")),
        ToolSpec("find_contact", "Busca contactos de la agenda por nombre y devuelve sus números de teléfono.",
            mapOf("name" to str("Nombre (o parte) del contacto")), listOf("name")),
        ToolSpec("open_whatsapp_chat", "Abre directamente el chat de WhatsApp con un número. Si se da 'message', el texto queda escrito en el chat (luego hay que pulsar enviar con tap si el usuario quiere enviarlo).",
            mapOf("phone" to str("Número de teléfono (con o sin prefijo de país)"), "message" to str("Texto opcional a dejar escrito"),
                "business" to bool("true para WhatsApp Business")), listOf("phone")),
        ToolSpec("call_phone", "Llama por teléfono a un número.", mapOf("phone" to str("Número de teléfono")), listOf("phone")),
        ToolSpec("compose_sms", "Abre la app de mensajes con un SMS redactado para un número.",
            mapOf("phone" to str("Número"), "message" to str("Texto del SMS")), listOf("phone", "message")),
        ToolSpec("compose_email", "Abre el correo con un email redactado.",
            mapOf("to" to str("Destinatario"), "subject" to str("Asunto"), "body" to str("Cuerpo"))),
        ToolSpec("open_url", "Abre una dirección web o un enlace profundo (deep link) en la app correspondiente.",
            mapOf("url" to str("URL completa")), listOf("url")),
        ToolSpec("web_search", "Busca algo en Google en el navegador.", mapOf("query" to str("Texto a buscar")), listOf("query")),
        ToolSpec("navigate", "Abre Google Maps con ruta o búsqueda de un lugar.",
            mapOf("destination" to str("Destino o lugar"), "mode" to enumOf("Medio de transporte; omitir para solo mostrar el lugar", "driving", "walking", "transit", "bicycling")),
            listOf("destination")),
        ToolSpec("set_alarm", "Crea una alarma.",
            mapOf("hour" to int("Hora 0-23"), "minute" to int("Minuto 0-59"), "label" to str("Etiqueta opcional")), listOf("hour", "minute")),
        ToolSpec("set_timer", "Pone un temporizador.", mapOf("seconds" to int("Duración en segundos"), "label" to str("Etiqueta opcional")), listOf("seconds")),
        ToolSpec("flashlight", "Enciende o apaga la linterna.", mapOf("on" to bool("true encender, false apagar")), listOf("on")),
        ToolSpec("set_volume", "Cambia el volumen multimedia, o silencia.",
            mapOf("percent" to int("Volumen 0-100"), "stream" to enumOf("Qué volumen", "media", "ring", "alarm")), listOf("percent")),
        ToolSpec("media_control", "Controla la música/vídeo que suena.",
            mapOf("action" to enumOf("Acción", "play", "pause", "play_pause", "next", "previous")), listOf("action")),
        ToolSpec("play_music", "Busca y reproduce música (canción, artista, lista) en la app de música predeterminada.",
            mapOf("query" to str("Qué reproducir")), listOf("query")),
        ToolSpec("open_settings", "Abre una sección de los ajustes del sistema.",
            mapOf("section" to enumOf("Sección", "general", "wifi", "bluetooth", "display", "sound", "battery", "location", "apps", "airplane", "mobile_data", "nfc", "accessibility")),
            listOf("section")),
        ToolSpec("share_text", "Abre el menú de compartir con un texto (o lo envía a una app concreta si se da su paquete).",
            mapOf("text" to str("Texto"), "package" to str("Paquete opcional de la app destino, p. ej. com.openai.chatgpt")), listOf("text")),
        ToolSpec("read_screen", "Lee lo que hay en la pantalla ahora mismo: lista numerada de elementos con texto, tipo, si son pulsables/editables y posición. Úsala antes de tap/type_text."),
        ToolSpec("tap", "Pulsa un elemento de la última lectura de pantalla por su número.",
            mapOf("element" to int("Número [n] del elemento"), "long_press" to bool("Pulsación larga")), listOf("element")),
        ToolSpec("tap_xy", "Toca la pantalla en unas coordenadas en píxeles (cuando el elemento no aparece en la lectura).",
            mapOf("x" to int("X"), "y" to int("Y")), listOf("x", "y")),
        ToolSpec("type_text", "Escribe texto en un campo editable (por número; si se omite, en el campo con foco).",
            mapOf("element" to int("Número [n] del campo editable"), "text" to str("Texto a escribir"), "submit" to bool("Pulsar Intro tras escribir")),
            listOf("text")),
        ToolSpec("scroll", "Desplaza la pantalla o una lista.",
            mapOf("direction" to enumOf("Dirección del contenido que quieres ver", "down", "up", "left", "right"), "element" to int("Número de la lista (opcional)")),
            listOf("direction")),
        ToolSpec("system_action", "Acción global del sistema.",
            mapOf("action" to enumOf("Acción", "back", "home", "recents", "notifications", "quick_settings", "lock_screen", "screenshot")), listOf("action")),
        ToolSpec("wait", "Espera unos segundos a que cargue algo (máx. 10).", mapOf("seconds" to int("Segundos")), listOf("seconds")),
    )

    suspend fun run(name: String, a: JSONObject): String = try {
        when (name) {
            "open_app" -> openApp(a.getString("name"))
            "list_apps" -> listApps(a.optString("filter"))
            "open_camera" -> openCamera(a.optString("mode", "photo"))
            "find_contact" -> findContact(a.getString("name"))
            "open_whatsapp_chat" -> whatsapp(a.getString("phone"), a.optString("message"), a.optBoolean("business"))
            "call_phone" -> call(a.getString("phone"))
            "compose_sms" -> start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.getString("phone")))).putExtra("sms_body", a.getString("message")), "SMS redactado")
            "compose_email" -> start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(a.optString("to"))))
                .putExtra(Intent.EXTRA_SUBJECT, a.optString("subject")).putExtra(Intent.EXTRA_TEXT, a.optString("body")), "Email redactado")
            "open_url" -> start(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url"))), "Enlace abierto")
            "web_search" -> webSearch(a.getString("query"))
            "navigate" -> navigate(a.getString("destination"), a.optString("mode"))
            "set_alarm" -> start(Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, a.getInt("minute"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label")).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Alarma creada")
            "set_timer" -> start(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds")).putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Temporizador iniciado")
            "flashlight" -> flashlight(a.getBoolean("on"))
            "set_volume" -> volume(a.getInt("percent"), a.optString("stream", "media"))
            "media_control" -> media(a.getString("action"))
            "play_music" -> start(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, a.getString("query")).putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*"), "Reproduciendo")
            "open_settings" -> settings(a.getString("section"))
            "share_text" -> share(a.getString("text"), a.optString("package"))
            "read_screen" -> withAccess { delay(700); it.readScreen() }
            "tap" -> withAccess { it.tap(a.getInt("element"), a.optBoolean("long_press")).also { delay(600) } }
            "tap_xy" -> withAccess { it.tapAt(a.getInt("x").toFloat(), a.getInt("y").toFloat()).also { delay(600) } }
            "type_text" -> withAccess { it.typeText(if (a.has("element")) a.getInt("element") else null, a.getString("text"), a.optBoolean("submit")) }
            "scroll" -> withAccess { it.scroll(a.getString("direction"), if (a.has("element")) a.getInt("element") else null).also { delay(500) } }
            "system_action" -> withAccess { it.global(a.getString("action")).also { delay(500) } }
            "wait" -> { delay(a.optInt("seconds", 1).coerceIn(1, 10) * 1000L); "Esperado" }
            else -> "Error: herramienta desconocida $name"
        }
    } catch (e: Exception) {
        "Error: ${e.javaClass.simpleName}: ${e.message}"
    }

    // ---------- Implementaciones ----------

    private suspend fun withAccess(block: suspend (AgentAccessibilityService) -> String): String {
        val s = AgentAccessibilityService.instance
            ?: return "Error: el control de pantalla (servicio de accesibilidad) no está activado. Dile al usuario que lo active en la app."
        return block(s)
    }

    private suspend fun start(intent: Intent, ok: String): String = try {
        AgentAccessibilityService.launch(ctx, intent)
        delay(1200)
        ok
    } catch (e: ActivityNotFoundException) {
        "Error: no hay ninguna app que pueda hacer esto"
    }

    private data class AppInfo(val label: String, val pkg: String)

    private fun installedApps(): List<AppInfo> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private suspend fun openApp(name: String): String {
        val apps = installedApps()
        val q = norm(name)
        val scored = apps.map { app ->
            val l = norm(app.label)
            val score = when {
                l == q -> 100
                l.replace(" ", "") == q.replace(" ", "") -> 95
                app.pkg.equals(name, true) -> 95
                l.startsWith(q) -> 80
                l.contains(q) -> 70
                q.contains(l) && l.length >= 3 -> 60
                app.pkg.lowercase().contains(q.replace(" ", "")) -> 55
                else -> (100 * (1.0 - levenshtein(l, q).toDouble() / maxOf(l.length, q.length, 1))).toInt() - 30
            }
            app to score
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull()
        if (best == null || best.second < 45) {
            val sugg = scored.take(8).joinToString { "${it.first.label} (${it.first.pkg})" }
            return "No encuentro la app '$name'. Parecidas: $sugg"
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(best.first.pkg)
            ?: return "Error: no se puede abrir ${best.first.label}"
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        AgentAccessibilityService.launch(ctx, intent)
        delay(1800)
        return "Abierta ${best.first.label} (${best.first.pkg})"
    }

    private fun listApps(filter: String): String {
        val f = norm(filter)
        return installedApps().filter { f.isEmpty() || norm(it.label).contains(f) || it.pkg.contains(f) }
            .joinToString("\n") { "${it.label} — ${it.pkg}" }.take(6000).ifEmpty { "Ninguna" }
    }

    private suspend fun openCamera(mode: String): String {
        val intent = when (mode) {
            "video" -> Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
            else -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        }
        if (mode == "selfie") {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
                .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        return start(intent, "Cámara abierta en modo $mode").also { delay(800) }
    }

    private fun findContact(name: String): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return "Error: falta el permiso de contactos"
        val q = norm(name)
        val out = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                out += n to num
            }
        }
        val words = q.split(" ").filter { it.isNotBlank() }
        val matches = out.filter { (n, _) ->
            val nn = norm(n)
            words.all { w -> nn.split(" ").any { it.startsWith(w) || levenshtein(it, w) <= 1 && w.length > 3 } }
        }.distinctBy { it.first + it.second.filter { ch -> ch.isDigit() } }
        if (matches.isEmpty()) return "No hay contactos que coincidan con '$name'"
        return matches.take(15).joinToString("\n") { "${it.first}: ${it.second}" }
    }

    private fun normalizePhone(phone: String): String {
        var p = phone.filter { it.isDigit() || it == '+' }
        if (p.startsWith("00")) p = "+" + p.drop(2)
        if (!p.startsWith("+")) p = "+" + prefs.countryCode + p.trimStart('0')
        return p
    }

    private suspend fun whatsapp(phone: String, message: String, business: Boolean): String {
        val p = normalizePhone(phone).drop(1)
        var url = "https://api.whatsapp.com/send?phone=$p"
        if (message.isNotBlank()) url += "&text=" + Uri.encode(message)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pkg = if (business) "com.whatsapp.w4b" else "com.whatsapp"
        if (isInstalled(pkg)) intent.setPackage(pkg)
        else if (isInstalled("com.whatsapp.w4b")) intent.setPackage("com.whatsapp.w4b")
        return start(intent, "Chat de WhatsApp abierto con +$p" + if (message.isNotBlank()) " con el mensaje escrito (sin enviar)" else "")
    }

    private suspend fun call(phone: String): String {
        val uri = Uri.parse("tel:" + Uri.encode(phone.filter { it.isDigit() || it == '+' }))
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        return start(Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri), if (granted) "Llamando" else "Marcador abierto (falta permiso de llamada)")
    }

    private suspend fun webSearch(q: String): String =
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))), "Búsqueda abierta")

    private suspend fun navigate(dest: String, mode: String): String {
        val uri = if (mode.isBlank()) Uri.parse("geo:0,0?q=" + Uri.encode(dest))
        else Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(dest) + "&travelmode=" + mode)
        return start(Intent(Intent.ACTION_VIEW, uri), "Mapa abierto")
    }

    private fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "Error: este móvil no tiene linterna"
        cm.setTorchMode(id, on)
        return if (on) "Linterna encendida" else "Linterna apagada"
    }

    private fun volume(percent: Int, stream: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = when (stream) { "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM; else -> AudioManager.STREAM_MUSIC }
        val max = am.getStreamMaxVolume(s)
        am.setStreamVolume(s, (max * percent.coerceIn(0, 100) / 100.0).toInt(), AudioManager.FLAG_SHOW_UI)
        return "Volumen $stream al $percent%"
    }

    private fun media(action: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Hecho: $action"
    }

    private suspend fun settings(section: String): String {
        val action = when (section) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "mobile_data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return start(Intent(action), "Ajustes de $section abiertos")
    }

    private suspend fun share(text: String, pkg: String): String {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val intent = if (pkg.isNotBlank()) send.setPackage(pkg) else Intent.createChooser(send, "Compartir")
        return start(intent, "Compartido")
    }

    private fun isInstalled(pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    /** Información de contexto que se envía al modelo con cada orden. */
    fun deviceContext(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val now = java.text.SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", java.util.Locale("es", "ES")).format(java.util.Date())
        val fg = AgentAccessibilityService.instance?.rootInActiveWindow?.packageName ?: "desconocida"
        val access = if (AgentAccessibilityService.instance != null) "activado" else "NO activado"
        return "Fecha y hora: $now. Batería: $battery%. App en primer plano: $fg. Control de pantalla: $access. " +
            "Modelo de móvil: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}."
    }

    companion object {
        fun norm(s: String): String = Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        fun levenshtein(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]; dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}
