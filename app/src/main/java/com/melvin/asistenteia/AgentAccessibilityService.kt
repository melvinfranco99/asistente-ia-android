package com.melvin.asistenteia

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Servicio de accesibilidad: permite al agente "ver" la pantalla (árbol de vistas),
 * pulsar elementos, escribir texto, hacer scroll y acciones globales (atrás, inicio…).
 * Además, al estar enlazado por el sistema, puede abrir actividades desde segundo plano.
 */
class AgentAccessibilityService : AccessibilityService() {

    /** Nodos numerados de la última lectura de pantalla. */
    private val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        instance = this
        EventLog.add("Control de pantalla activado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------- Lectura de pantalla ----------

    fun readScreen(): String {
        nodes.clear()
        val roots = windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION || it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM }
            .mapNotNull { it.root }
            .ifEmpty { listOfNotNull(rootInActiveWindow) }
        if (roots.isEmpty()) return "No se puede leer la pantalla ahora mismo."
        val sb = StringBuilder()
        val active = rootInActiveWindow?.packageName
        sb.append("App en primer plano: ").append(active ?: "?").append('\n')
        val dm = resources.displayMetrics
        sb.append("Pantalla: ${dm.widthPixels}x${dm.heightPixels}\n")
        var counter = 0
        for (root in roots) {
            if (root.packageName == packageName) continue
            walk(root, 0) { node, depth ->
                val line = describe(node) ?: return@walk
                counter++
                nodes[counter] = node
                sb.append("  ".repeat(minOf(depth, 6))).append('[').append(counter).append("] ").append(line).append('\n')
            }
            if (sb.length > 12000) break
        }
        if (counter == 0) sb.append("(no hay elementos visibles con texto o interactivos)\n")
        return sb.toString().take(14000)
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, visit: (AccessibilityNodeInfo, Int) -> Unit) {
        if (!node.isVisibleToUser) return
        visit(node, depth)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, visit)
        }
    }

    private fun describe(n: AccessibilityNodeInfo): String? {
        val text = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
        val id = n.viewIdResourceName?.substringAfter(":id/").orEmpty()
        val interactive = n.isClickable || n.isEditable || n.isCheckable || n.isScrollable || n.isLongClickable
        if (text.isEmpty() && desc.isEmpty() && hint.isEmpty() && !interactive) return null
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
        val flags = buildList {
            if (n.isClickable) add("clicable")
            if (n.isEditable) add("editable")
            if (n.isScrollable) add("desplazable")
            if (n.isCheckable) add(if (n.isChecked) "marcado" else "no-marcado")
            if (n.isFocused) add("con-foco")
            if (!n.isEnabled) add("deshabilitado")
        }
        val r = Rect().also { n.getBoundsInScreen(it) }
        return buildString {
            append(cls)
            if (text.isNotEmpty()) append(" \"").append(text.take(150)).append('"')
            if (desc.isNotEmpty() && desc != text) append(" desc=\"").append(desc.take(100)).append('"')
            if (hint.isNotEmpty() && hint != text) append(" pista=\"").append(hint.take(80)).append('"')
            if (id.isNotEmpty()) append(" id=").append(id)
            if (flags.isNotEmpty()) append(" (").append(flags.joinToString(",")).append(')')
            append(" @").append(r.centerX()).append(',').append(r.centerY())
        }
    }

    // ---------- Acciones ----------

    suspend fun tap(index: Int, longPress: Boolean = false): String {
        val node = nodes[index] ?: return "Error: no existe el elemento [$index]. Vuelve a leer la pantalla."
        val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            val ok = if (longPress) target.isLongClickable else target.isClickable
            if (ok && target.performAction(action)) return "Pulsado [$index]"
            target = target.parent
        }
        // Si ningún ancestro acepta el clic, toque físico en el centro.
        val r = Rect().also { node.getBoundsInScreen(it) }
        return if (gestureTap(r.exactCenterX(), r.exactCenterY(), if (longPress) 800 else 60)) "Pulsado [$index] (gesto)"
        else "Error: no se pudo pulsar [$index]"
    }

    suspend fun tapAt(x: Float, y: Float): String =
        if (gestureTap(x, y, 60)) "Toque en ($x, $y)" else "Error: no se pudo tocar ($x, $y)"

    fun typeText(index: Int?, text: String, submit: Boolean): String {
        val node = if (index != null) nodes[index] else findFocusedEditable()
        if (node == null) return "Error: no hay campo de texto [${index ?: "con foco"}]. Lee la pantalla."
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return "Error: el campo no acepta texto"
        if (submit && Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return "Texto escrito" + if (submit) " y enviado con Intro (comprueba si hace falta pulsar el botón de enviar)" else ""
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused?.isEditable == true) return focused
        var found: AccessibilityNodeInfo? = null
        rootInActiveWindow?.let { root -> walk(root, 0) { n, _ -> if (found == null && n.isEditable) found = n } }
        return found
    }

    suspend fun scroll(direction: String, index: Int?): String {
        val node = index?.let { nodes[it] }
        if (node != null) {
            val act = if (direction == "up" || direction == "left") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            var t: AccessibilityNodeInfo? = node
            while (t != null) {
                if (t.isScrollable && t.performAction(act)) return "Desplazado $direction"
                t = t.parent
            }
        }
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val (x1, y1, x2, y2) = when (direction) {
            "up" -> listOf(w / 2, h * 0.3f, w / 2, h * 0.75f)
            "left" -> listOf(w * 0.2f, h / 2, w * 0.8f, h / 2)
            "right" -> listOf(w * 0.8f, h / 2, w * 0.2f, h / 2)
            else -> listOf(w / 2, h * 0.75f, w / 2, h * 0.3f)
        }
        return if (swipe(x1, y1, x2, y2, 350)) "Desplazado $direction (gesto)" else "Error al desplazar"
    }

    fun global(action: String): String {
        val a = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return "No soportado"
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return "No soportado"
            else -> return "Acción desconocida: $action"
        }
        return if (performGlobalAction(a)) "Hecho: $action" else "Error: $action"
    }

    private suspend fun gestureTap(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build())
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build())
    }

    private suspend fun dispatch(g: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { it.contains(context.packageName + "/") && it.contains("AgentAccessibilityService") }
        }

        /** Abre una actividad; desde el servicio de accesibilidad se permite aunque estemos en segundo plano. */
        fun launch(context: Context, intent: Intent) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ctx: Context = instance ?: context
            ctx.startActivity(intent)
        }
    }
}
