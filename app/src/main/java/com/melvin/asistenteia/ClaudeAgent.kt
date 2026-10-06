package com.melvin.asistenteia

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Duration

/**
 * Interpreta la orden de voz con Claude y la ejecuta en el móvil mediante herramientas
 * (bucle manual de tool use: pedir → ejecutar herramientas → devolver resultados → repetir).
 */
class ClaudeAgent(private val prefs: Prefs, private val tools: PhoneTools) {

    private var client: AnthropicClient? = null
    private var clientKey: String? = null
    private val history = mutableListOf<MessageParam>()
    private var lastActivity = 0L

    private val toolDefs: List<Tool> = tools.specs.map { spec ->
        val props = Tool.InputSchema.Properties.builder()
        spec.properties.forEach { (k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)) }
        Tool.builder()
            .name(spec.name)
            .description(spec.description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(props.build())
                    .required(spec.required)
                    .build()
            )
            .build()
    }

    private fun client(): AnthropicClient {
        val key = prefs.apiKey
        if (client == null || clientKey != key) {
            client = AnthropicOkHttpClient.builder()
                .apiKey(key)
                .timeout(Duration.ofSeconds(90))
                .maxRetries(2)
                .build()
            clientKey = key
        }
        return client!!
    }

    /** Ejecuta una orden. [onStep] informa del progreso. Devuelve la frase final para decir al usuario. */
    suspend fun handle(command: String, onStep: (String) -> Unit): String = withContext(Dispatchers.IO) {
        if (prefs.apiKey.isBlank()) return@withContext "Primero tienes que poner tu clave API de Anthropic en la app."

        // Conversación de seguimiento si la orden llega poco después de la anterior (p. ej. responder a una pregunta).
        if (System.currentTimeMillis() - lastActivity > FOLLOW_UP_WINDOW_MS) history.clear()
        history += MessageParam.builder()
            .role(MessageParam.Role.USER)
            .content("[Estado del móvil: ${tools.deviceContext()}]\n\nOrden del usuario (transcrita por voz): \"$command\"")
            .build()

        try {
            for (step in 1..MAX_STEPS) {
                val model = prefs.model
                val builder = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(16000L)
                    .system(SYSTEM_PROMPT)
                    .messages(history.toList())
                    .cacheControl(CacheControlEphemeral.builder().build())
                toolDefs.forEach { builder.addTool(it) }
                if (!model.startsWith("claude-haiku")) {
                    // Respuestas rápidas para un asistente de voz.
                    builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    // Si el modelo rechaza la petición por un falso positivo, el servidor reintenta con otro modelo.
                    builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }

                val response = client().messages().create(builder.build())
                history += response.toParam()

                val stop = response.stopReason().map { it.toString() }.orElse("")
                if (stop == "refusal") {
                    history.clear()
                    return@withContext "Lo siento, no puedo ayudarte con eso."
                }

                val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
                if (toolUses.isEmpty()) {
                    lastActivity = System.currentTimeMillis()
                    val text = response.content().mapNotNull { it.text().orElse(null)?.text() }
                        .joinToString(" ").trim()
                    return@withContext text.ifEmpty { "Hecho." }
                }

                val results = toolUses.map { tu ->
                    val inputJson = jsonMapper().writeValueAsString(tu._input())
                    onStep("⚙ ${tu.name()} $inputJson")
                    val result = tools.run(tu.name(), JSONObject(inputJson))
                    onStep("  → ${result.lineSequence().first().take(160)}")
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                            .toolUseId(tu.id())
                            .content(result)
                            .isError(result.startsWith("Error"))
                            .build()
                    )
                }
                history += MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(results)
                    .build()
            }
            history.clear()
            "He llegado al límite de pasos sin terminar la tarea."
        } catch (e: UnauthorizedException) {
            history.clear(); "La clave API no es válida. Revísala en los ajustes."
        } catch (e: PermissionDeniedException) {
            history.clear(); "Tu clave API no tiene permiso para usar el modelo ${prefs.model}."
        } catch (e: RateLimitException) {
            history.clear(); "Hay demasiadas peticiones ahora mismo. Inténtalo en un momento."
        } catch (e: AnthropicServiceException) {
            history.clear(); "Error del servicio de IA (${e.statusCode()}): ${e.message?.take(120)}"
        } catch (e: AnthropicIoException) {
            history.clear(); "No hay conexión con el servicio de IA."
        }
    }

    fun reset() = history.clear()

    companion object {
        private const val MAX_STEPS = 25
        private const val FOLLOW_UP_WINDOW_MS = 90_000L

        val SYSTEM_PROMPT = """
Eres un asistente de voz que controla un móvil Android en nombre de su dueño. El usuario te habla en voz alta; recibes su orden ya transcrita (puede tener errores de reconocimiento de voz: interpreta lo que más probablemente quiso decir, p. ej. "guasap" = WhatsApp, "chat gepeté" = ChatGPT).

Tu trabajo es averiguar la intención y llevarla a cabo en el móvil usando las herramientas, no explicar cómo hacerlo.

Cómo actuar:
- Usa la herramienta directa cuando exista (open_camera, open_whatsapp_chat, set_alarm, navigate, call_phone…); es más rápida y fiable que tocar la pantalla.
- Para personas por nombre, busca primero con find_contact. Si hay varios contactos que encajan igual de bien, pregunta cuál en una frase corta; si uno encaja claramente, úsalo.
- Para tareas dentro de otras apps (escribir en ChatGPT, pulsar "grabar" en la cámara, buscar algo dentro de una app…): abre la app, llama a read_screen, y usa tap / type_text / scroll con los números de elemento. Tras cada acción importante vuelve a leer la pantalla para comprobar que ha funcionado; si aparece un diálogo, permiso o anuncio, gestiónalo.
- "Empieza a grabar" sin más contexto significa grabar vídeo con la cámara: abre la cámara en modo vídeo y pulsa el botón de grabar. "Haz una foto" igual con el disparador.
- Si te piden preguntar algo a otra IA o app (ChatGPT, Gemini…), escribe la pregunta allí, envíala, espera a la respuesta (wait + read_screen, varias veces si hace falta) y resume en voz alta lo que ha respondido.
- Si la orden es una pregunta que puedes contestar tú mismo sin el móvil (cultura general, cálculos, la hora con el estado del móvil), contéstala directamente sin herramientas. Para información en tiempo real (tiempo, noticias, resultados) abre una búsqueda web y lee la pantalla para contestar.
- Enviar mensajes, hacer llamadas o publicar algo: hazlo cuando el usuario lo pida explícitamente ("envíale…", "llama a…"). Si solo pide abrir un chat, ábrelo sin enviar nada. No hagas compras, pagos ni borres datos salvo petición clara y explícita.
- Si algo falla, prueba otra vía razonable (otra herramienta, buscar la app con list_apps, tocar por coordenadas) antes de rendirte.

Respuesta final: cuando termines, responde con UNA o DOS frases cortas en español, en tono natural, porque se leerán en voz alta. Sin markdown, sin listas, sin emojis. Si necesitas que el usuario aclare algo, termina tu respuesta con una pregunta (signo ?) y escucharás su contestación.
""".trimIndent()
    }
}
