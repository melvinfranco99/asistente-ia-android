package com.melvin.asistenteia

import android.app.Activity
import android.os.Bundle

/** Actividad invisible: arranca el servicio en modo "escuchar una orden" y se cierra. */
class ListenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { AssistantService.start(this, AssistantService.ACTION_LISTEN) }
            .onFailure { EventLog.add("No se pudo iniciar: ${it.message}") }
        finish()
        overridePendingTransition(0, 0)
    }
}
