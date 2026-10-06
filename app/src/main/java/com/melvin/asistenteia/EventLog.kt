package com.melvin.asistenteia

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro en memoria de lo que hace el asistente, para mostrarlo en la pantalla principal. */
object EventLog {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Volatile
    var status: String = "Detenido"
        private set

    fun add(line: String) {
        synchronized(lines) {
            lines.addFirst("${fmt.format(Date())}  $line")
            while (lines.size > MAX) lines.removeLast()
        }
        notifyListeners()
    }

    fun setStatus(s: String) {
        status = s
        notifyListeners()
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun notifyListeners() = main.post { listeners.toList().forEach { it() } }
}
