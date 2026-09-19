# Glia Kotlin SDK (`glia-sdk-kotlin`)

Official Android / Kotlin client SDK and Jetpack Compose UI components for **Glia** — the high-performance, cloud-agnostic agent runtime built on Phoenix Channels WebSockets.

---

## 🚀 Features

- **Protocol Parity:** Built on standard Phoenix Channels v2 (`phx_join`, `run`, heartbeat, exponential reconnect).
- **Cloud & Vendor Agnostic:** Connects to any Glia instance (Docker, AWS, GCP, Bare-Metal, localhost).
- **Domain & Client Agnostic:** Zero client-specific hardcoding. Fully customizable themes, system prompts, and declarative tools.
- **Real-Time Streaming:** Sub-second streaming of tokens (`message_delta`), reasoning traces (`thinking_delta`), and tool calls (`tool_call`, `tool_result`).
- **Jetpack Compose Ready:** Includes `GliaChat` with live streaming, collapsible thinking accordion, and dynamic tool status chips.

---

## 📦 Installation (Gradle)

In your `settings.gradle.kts`:

```kotlin
include(":glia")
project(":glia").projectDir = file("../glia-sdk-kotlin/glia")
```

In your `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":glia"))
}
```

---

## 🛠️ Usage

### 1. Initialize GliaClient (Core)

```kotlin
import cl.zea.glia.core.client.GliaClient
import cl.zea.glia.core.models.GliaOptions

val options = GliaOptions(
    gatewayUrl = "wss://glia.yourdomain.com",
    appId = "your-app-id",
    userId = "user-12345",
    token = "jwt-bearer-token"
)

val client = GliaClient(options)

// Connect
client.connect()

// Send a prompt
client.send(
    prompt = "¿Cuál es el balance del fondo?",
    systemPrompt = "Eres un asistente financiero."
)

// Observe events
lifecycleScope.launch {
    client.events.collect { event ->
        when (event) {
            is GliaStreamEvent.ThinkingDelta -> println("Thinking: ${event.content}")
            is GliaStreamEvent.MessageDelta -> println("Message: ${event.content}")
            is GliaStreamEvent.ToolCall -> println("Tool: ${event.name}")
            is GliaStreamEvent.Done -> println("Completed!")
            else -> {}
        }
    }
}
```

### 2. Embed GliaChat (Jetpack Compose)

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import cl.zea.glia.ui.GliaChat
import cl.zea.glia.ui.GliaChatViewModel
import cl.zea.glia.ui.GliaTheme

@Composable
fun AssistantScreen(viewModel: GliaChatViewModel) {
    GliaChat(
        viewModel = viewModel,
        title = "Mi Asistente",
        welcomeMessage = "¡Hola! ¿En qué puedo ayudarte?",
        placeholder = "Escribe tu consulta...",
        suggestedPrompts = listOf(
            "Consultar estado de cuenta",
            "Programar una reunión"
        ),
        theme = GliaTheme(
            primary = Color(0xFF2563EB),
            userBubble = Color(0xFF2563EB),
            bg = Color(0xFF121212)
        )
    )
}
```

---

## 📄 License

Apache License 2.0 © ZEA Platform

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the [LICENSE](LICENSE) file for more details.
