# Asistente IA para Android

Asistente de voz para Android que entiende lo que quieres decir y lo hace en tu móvil, también en segundo plano. Usa Claude (Anthropic) para interpretar la orden y ejecutarla con herramientas: abrir apps, cámara, WhatsApp, llamadas, alarmas, mapas, música, y controlar la pantalla de otras apps (leer, pulsar, escribir) mediante un servicio de accesibilidad.

**Descarga:** https://melvinfranco99.github.io/asistente-ia-android/

## Ejemplos

- "Asistente, abre la cámara"
- "Empieza a grabar" → cámara en modo vídeo + pulsa grabar
- "Ábreme el chat de WhatsApp de Juan" → busca a Juan en contactos y abre su chat
- "Abre ChatGPT y pregúntale sobre el clima hoy en Madrid" → escribe la pregunta, la envía y te lee la respuesta
- "Pon una alarma a las 7:30", "Enciende la linterna", "Llévame al Retiro andando"

## Cómo funciona

| Componente | Archivo |
|---|---|
| Servicio en primer plano: escucha continua con palabra de activación, reconocimiento de voz y respuesta hablada | `AssistantService.kt` |
| Agente de IA: bucle de herramientas con la API de Claude (SDK oficial de Java) | `ClaudeAgent.kt` |
| Herramientas del móvil (apps, cámara, contactos, WhatsApp, alarmas, linterna…) | `PhoneTools.kt` |
| Control de pantalla (leer elementos, pulsar, escribir, desplazar, atrás/inicio) | `AgentAccessibilityService.kt` |
| Botón de ajustes rápidos y acción de asistente | `AssistantTileService.kt`, `ListenActivity.kt` |

Formas de dar una orden: decir la palabra de activación (por defecto “asistente”) con la escucha continua activada, el botón **Hablar** de la notificación, el botón de ajustes rápidos o escribirla en la app.

## Configuración

1. Instala el APK y abre la app.
2. Pega tu clave API de Anthropic (https://console.anthropic.com/settings/keys) y guarda. Modelo por defecto: `claude-opus-5-5`; puedes elegir `claude-sonnet-5-5` o `claude-haiku-4-5` para respuestas más rápidas y baratas.
3. Concede los permisos: micrófono, contactos, llamadas, control de pantalla (accesibilidad), mostrar sobre otras apps y sin restricción de batería.
4. Pulsa **Activar en segundo plano**.

## Compilar

Requiere JDK 17 y el SDK de Android (plataforma 34).

```bash
./gradlew assembleDebug
```

Las versiones se publican con GitHub Actions al subir una etiqueta `v*` (`.github/workflows/release.yml`), firmadas con el keystore guardado en los secretos `KEYSTORE_BASE64` y `KEYSTORE_PASSWORD`.

## Limitaciones

- La escucha continua usa el reconocedor de voz de Android en bucle: consume batería y en algunos móviles suena un pitido al reiniciarse. Si molesta, desactívala y usa el botón Hablar.
- Mientras otra app usa el micrófono (por ejemplo, grabando vídeo), el asistente no puede escuchar.
- El control de pantalla depende de cómo esté hecha cada app; la IA lee la pantalla y lo reintenta, pero puede fallar en apps poco accesibles.
