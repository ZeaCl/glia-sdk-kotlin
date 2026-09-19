# 🚀 Épica: Mejoras Arquitecturales y Capacidades Avanzadas para Glia SDKs

Esta épica coordina las mejoras arquitecturales identificadas en la revisión de código del **Glia Swift SDK** ([`ZeaCl/glia-sdk-swift`](https://github.com/ZeaCl/glia-sdk-swift)) y su paridad e implementación en el **Glia Kotlin SDK** ([`ZeaCl/glia-sdk-kotlin`](https://github.com/ZeaCl/glia-sdk-kotlin)).

---

## 🎯 Objetivo

Evolucionar los SDKs cliente de Glia hacia entornos de agentes autónomos móviles de próxima generación, incorporando:
1. **Client-Side Tool Calling:** Invocación de herramientas nativas del dispositivo y callback de resultados.
2. **Session Resumption:** Reanudación tolerante a fallos de streaming y microcortes de red.
3. **Multimodalidad:** Soporte nativo para envío de imágenes, adjuntos y visión en mensajería y UI.
4. **Persistencia Local:** Adaptador desacoplado de almacenamiento local y caché offline.

---

## 🌳 Matriz de Paridad y Seguimiento de Issues en GitHub

| Capacidad / Feature | 🤖 Android / Kotlin (`glia-sdk-kotlin`) | 🍏 iOS & macOS / Swift (`glia-sdk-swift`) |
| :--- | :---: | :---: |
| **Épica Principal** | [ZeaCl/glia-sdk-kotlin#2](https://github.com/ZeaCl/glia-sdk-kotlin/issues/2) | [ZeaCl/glia-sdk-swift#14](https://github.com/ZeaCl/glia-sdk-swift/issues/14) |
| **1. Client-Side Tool Calling** | [#3](https://github.com/ZeaCl/glia-sdk-kotlin/issues/3) | [#10](https://github.com/ZeaCl/glia-sdk-swift/issues/10) |
| **2. Session Resumption & State Sync** | [#4](https://github.com/ZeaCl/glia-sdk-kotlin/issues/4) | [#11](https://github.com/ZeaCl/glia-sdk-swift/issues/11) |
| **3. Mensajería Multimodal (Imágenes & Visión)** | [#5](https://github.com/ZeaCl/glia-sdk-kotlin/issues/5) | [#12](https://github.com/ZeaCl/glia-sdk-swift/issues/12) |
| **4. Persistencia Local & Caché Offline** | [#6](https://github.com/ZeaCl/glia-sdk-kotlin/issues/6) | [#13](https://github.com/ZeaCl/glia-sdk-swift/issues/13) |

---

## 📋 Detalle de las Mejoras

### 1. Invocación de Herramientas en Cliente (`Client-Side Tool Calling`)
- **Problema:** Actualmente las herramientas (`GliaToolDefinition`) asumen ejecución remota mediante webhooks del backend.
- **Solución:**
  - Agregar tipo de ejecución (`webhook` vs `client`).
  - Método en cliente: `sendToolResult(callId, result)` para enviar el frame `tool_result` al socket Phoenix.
  - Dispatcher en el ViewModel para resolver herramientas locales (GPS, cámara, biometría) con feedback visual en el chat.

### 2. Sincronización y Reanudación de Sesión (`Session Resumption`)
- **Problema:** Desconexiones momentáneas durante streaming activo (`message_delta` / `thinking_delta`) provocan pérdida de tokens y dejan el estado de la UI colgado.
- **Solución:**
  - Checkpointing de secuencias recibidas (`lastSequenceId`).
  - Enviar `resume_token` o `last_seq` en el handshake de `phx_join` para retransmitir tokens perdidos.
  - Estado de fallback limpio en la UI con opción de reintentar si la sesión expiró en el servidor.

### 3. Mensajería Multimodal (Imágenes, Adjuntos y Visión)
- **Problema:** El contrato de mensajería `send(prompt: String)` es 100% texto plano.
- **Solución:**
  - Estructura `GliaContentPart` soportando texto e imágenes (URL o base64/binario).
  - Selector de imágenes y previsualización en la barra de entrada de `GliaChatView` (SwiftUI) y `GliaChat` (Jetpack Compose).

### 4. Persistencia Local Integrada y Caché Offline
- **Problema:** Cada aplicación host debe reimplementar su lógica de almacenamiento y recarga de historial.
- **Solución:**
  - Abstracción `GliaChatStorage` / `GliaChatStorageProtocol`.
  - Implementación estándar basada en archivos JSON en caché.
  - Integración reactiva automática con `GliaChatViewModel`.
