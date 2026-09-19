# 📋 Requerimientos de Paridad: Glia Kotlin SDK vs Glia Swift SDK

Este documento establece los requerimientos formales necesarios para alcanzar el 100% de paridad funcional, arquitectural y de resiliencia entre **`glia-sdk-kotlin`** y la versión de producción de **`glia-sdk-swift`** (`v1.0.1`).

---

## 1. Módulo Core y Protocolo Phoenix (`cl.zea.glia.core`)

### REQ-CORE-01: Handshake `phx_join` Sincrónico y Tipado
- **Descripción:** `GliaClient.connect()` debe suspenderse hasta recibir la confirmación `phx_reply` con `status: "ok"` del servidor.
- **Criterio de Aceptación:**
  - Si el servidor responde `status: "ok"`, `connect()` finaliza exitosamente y `isConnected` pasa a `true`.
  - Si el servidor responde `status: "error"` (e.g. token inválido o expirado), `connect()` lanza `GliaException.JoinFailed(reason)`.
  - Si no hay respuesta tras `timeoutMs` (por defecto 60s), lanza `GliaException.ConnectionTimeout`.
  - No marcar `isConnected = true` prematuramente antes de la confirmación del servidor.

### REQ-CORE-02: Manejo de Frames `phx_reply`
- **Descripción:** El loop de lectura de frames de Phoenix debe interceptar los mensajes con evento `"phx_reply"`.
- **Criterio de Aceptación:**
  - Correlacionar el frame con el `ref` enviado en el join o en peticiones RPC mediante un mapa de `CompletableDeferred`.
  - Desbloquear la llamada suspendida correspondiente.

### REQ-CORE-03: Paridad de Contrato en Evento `done`
- **Descripción:** El backend Elixir (`GliaWeb.SessionChannel`) emite `{:done, response} -> push(socket, "done", %{text: response})`, mientras que versiones legacy emitían `full_message`.
- **Criterio de Aceptación:**
  - El parsing del evento `done` debe priorizar `payload["text"]` y tener fallback a `payload["full_message"]`.

### REQ-CORE-04: Manejo de Desconexión Voluntaria vs Involuntaria
- **Descripción:** Distinguir cuando la desconexión fue invocada explícitamente mediante `client.disconnect()`.
- **Criterio de Aceptación:**
  - Si la desconexión es voluntaria, no emitir `GliaStreamEvent.Error` ni activar reintentos de reconexión.
  - Cancelar tareas de heartbeat y reconexión de forma limpia.

### REQ-CORE-05: Desacoplamiento de Transporte WebSocket (DIP)
- **Descripción:** Abstraer el cliente WebSocket de Ktor/OkHttp tras una interfaz para permitir testing unitario determinista en memoria.
- **Criterio de Aceptación:**
  - Crear interfaz `WebSocketConnection` / `WebSocketSessionFactory`.
  - Implementación por defecto basada en Ktor/OkHttp.
  - Implementación mockeable para pruebas sin abrir sockets reales.

---

## 2. Modelos de Datos y Serialización (`cl.zea.glia.core.models`)

### REQ-DATA-01: Serialización de Modelos de Chat con `kotlinx.serialization`
- **Descripción:** `GliaChatMessage` y `GliaMessageRole` deben ser serializables para permitir persistencia en almacenamiento local (Room, DataStore, JSON en disco).
- **Criterio de Aceptación:**
  - Anotar con `@Serializable`.
  - Incluir `timestamp: Long = System.currentTimeMillis()`.

### REQ-DATA-02: Configuración de Timeouts en `GliaOptions`
- **Descripción:** Parámetros de tiempo límite configurables.
- **Criterio de Aceptación:**
  - Campo `timeoutMs: Long = 60_000L` para control de join y operaciones.

---

## 3. Capa de Presentación y Estado (`cl.zea.glia.ui.GliaChatViewModel`)

### REQ-VM-01: Protección contra Envíos Concurrentes (`isStreaming` Guard)
- **Descripción:** Evitar que el usuario o la UI envíen múltiples prompts concurrentes mientras el asistente sigue respondiendo.
- **Criterio de Aceptación:**
  - Si `uiState.value.isStreaming == true`, ignorar cualquier llamada a `send()`.

### REQ-VM-02: Carga de Historial Inicial y Callback de Actualización
- **Descripción:** Permitir inyectar mensajes previos y escuchar cambios en la lista de mensajes para persistencia.
- **Criterio de Aceptación:**
  - Constructor con `initialMessages: List<GliaChatMessage> = emptyList()`.
  - Callback `onMessagesUpdated: ((List<GliaChatMessage>) -> Unit)? = null`.
  - Métodos `loadMessages(newMessages)` y `clearMessages()`.
  - Disparar `onMessagesUpdated` al enviar mensaje de usuario y al completar mensaje de asistente.

### REQ-VM-03: Preservación de `toolName` en Burbuja Final
- **Descripción:** Cuando se ejecuta una herramienta, `tool_call` fija el nombre, pero `tool_result` lo limpia. Al llegar `done`, el mensaje del asistente debe conservar la referencia de la herramienta que se ejecutó.
- **Criterio de Aceptación:**
  - Mantener variable `lastExecutedTool` en el ViewModel y asignarla al `GliaChatMessage` final del asistente.

---

## 4. Componente Visual Jetpack Compose (`cl.zea.glia.ui.GliaChat`)

### REQ-UI-01: Control de Desconexión en Ciclo de Vida (`disconnectOnDispose`)
- **Descripción:** Evitar desconexiones accidentales al navegar entre pestañas o abrir diálogos/bottom sheets.
- **Criterio de Aceptación:**
  - Parámetro `disconnectOnDispose: Boolean = false` en `GliaChat`.

### REQ-UI-02: Bloque de Razonamiento Colapsable (Thinking Accordion)
- **Descripción:** Las respuestas de modelos de razonamiento (como DeepSeek-R1) generan textos extensos en `thinking_delta`.
- **Criterio de Aceptación:**
  - Encabezado colapsable "Proceso de razonamiento" con ícono cerebral y flecha de expansión.
  - Colapsado por defecto en historial final, expandible a demanda.

### REQ-UI-03: Soporte para `pendingPrompt`
- **Descripción:** Permitir inyectar prompts desde vistas externas (e.g. deep links, atajos).
- **Criterio de Aceptación:**
  - Parámetro `pendingPrompt: String? = null` con `onPromptConsumed: () -> Unit`.

### REQ-UI-04: Optimización del Scroll en Streaming
- **Descripción:** Evitar animaciones continuas durante la llegada de deltas de texto rápidos.
- **Criterio de Aceptación:**
  - Scroll directo (`scrollToItem`) durante deltas continuos; `animateScrollToItem` solo al agregar nuevos mensajes.

---

## 5. Pruebas Unitarias y Calidad CI/CD

### REQ-QA-01: Suite de Tests Unitarios Deterministas
- **Criterio de Aceptación:**
  - Tests para `GliaClient`: Handshake exitoso, error de autenticación (`unauthorized`), normalización de URLs, streaming parsing (`thinking_delta`, `message_delta`, `tool_call`, `done` con `text` y `full_message`), desconexión voluntaria.
  - Tests para `GliaChatViewModel`: Conexión, acumulación de streaming, bloqueo de envíos concurrentes, preservación de `toolName`, serialización de mensajes.

### REQ-CI-01: Pipeline GCP Cloud Build (`cloudbuild.yaml`)
- **Criterio de Aceptación:**
  - Ejecución de pruebas unitarias (`./gradlew testDebugUnitTest`).
  - Escaneo de seguridad y cumplimiento con `microglia scan . --details`.

---

## 6. Arquitectura Multi-Backend (Conectores Agénticos)

### REQ-BACKEND-01: Abstracción de Transporte de Agentes (`GliaBackendType`)
- **Descripción:** El SDK debe permitir conectarse no solo a ZEA Glia (Phoenix Channels), sino también a backends de agentes externos basados en HTTP Server-Sent Events (SSE) y streaming estándar (Soma Agent Hub tipo Pi/open-source con autenticación vía Thalamus Auth Server, Dify.ai, LangGraph, OpenAI Assistants).
- **Criterio de Aceptación:**
  - `GliaClientProtocol` se mantiene como contrato universal agnóstico.
  - Soportar enum/configuración:
    ```kotlin
    enum class GliaBackendType {
        PHOENIX, // Glia Elixir runtime (por defecto)
        SSE      // Soma Hub, Dify, LangGraph, OpenAI SSE
    }
    ```
  - `PhoenixAgentClient`: Implementación basada en Phoenix Channels v2.
  - `SseAgentClient`: Implementación basada en HTTP `text/event-stream` que mapea eventos entrantes a `GliaStreamEvent`.
  - Factory en `GliaClient`:
    ```kotlin
    val client = GliaClient.create(options, backendType = GliaBackendType.PHOENIX)
    ```
