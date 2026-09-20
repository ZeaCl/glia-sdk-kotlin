# 📋 Parity Requirements: Glia Kotlin SDK vs Glia Swift SDK

This document establishes the formal requirements necessary to achieve 100% functional, architectural, and resilience parity between **`glia-sdk-kotlin`** and the production release of **`glia-sdk-swift`** (`v1.0.1`).

---

## 1. Core Module and Phoenix Protocol (`cl.zea.glia.core`)

### REQ-CORE-01: Synchronous & Typed `phx_join` Handshake
- **Description:** `GliaClient.connect()` must suspend until receiving the `phx_reply` acknowledgement with `status: "ok"` from the server.
- **Acceptance Criteria:**
  - If the server replies `status: "ok"`, `connect()` completes successfully and `isConnected` transitions to `true`.
  - If the server replies `status: "error"` (e.g., invalid or expired token), `connect()` throws `GliaException.JoinFailed(reason)`.
  - If no reply is received after `timeoutMs` (default 60s), throws `GliaException.ConnectionTimeout`.
  - Do not flag `isConnected = true` prematurely before server acknowledgement.

### REQ-CORE-02: `phx_reply` Frame Handling
- **Description:** The Phoenix frame reading loop must intercept messages with the `"phx_reply"` event.
- **Acceptance Criteria:**
  - Correlate the frame with the `ref` sent during join or RPC requests via a map of `CompletableDeferred`.
  - Unblock the corresponding suspended call.

### REQ-CORE-03: Contract Parity in `done` Event
- **Description:** The Elixir backend (`GliaWeb.SessionChannel`) emits `{:done, response} -> push(socket, "done", %{text: response})`, while legacy versions emitted `full_message`.
- **Acceptance Criteria:**
  - The parsing of `done` must prioritize `payload["text"]` with fallback to `payload["full_message"]`.

### REQ-CORE-04: Voluntary vs Involuntary Disconnection Handling
- **Description:** Distinguish when disconnection was explicitly triggered via `client.disconnect()`.
- **Acceptance Criteria:**
  - If disconnection is voluntary, do not emit `GliaStreamEvent.Error` or trigger reconnect retries.
  - Cancel heartbeat and reconnection tasks cleanly.

### REQ-CORE-05: WebSocket Transport Decoupling (DIP)
- **Description:** Abstract the Ktor/OkHttp WebSocket client behind an interface for deterministic in-memory unit testing.
- **Acceptance Criteria:**
  - Define `WebSocketConnection` / `WebSocketConnectionFactory`.
  - Default implementation backed by Ktor/OkHttp.
  - Mockable implementation for testing without real network sockets.

---

## 2. Data Models and Serialization (`cl.zea.glia.core.models`)

### REQ-DATA-01: Chat Model Serialization with `kotlinx.serialization`
- **Description:** `GliaChatMessage` and `GliaMessageRole` must be serializable to support local storage (Room, DataStore, JSON on disk).
- **Acceptance Criteria:**
  - Annotate with `@Serializable`.
  - Include `timestamp: Long = System.currentTimeMillis()`.

### REQ-DATA-02: Timeout Configuration in `GliaOptions`
- **Description:** Configurable timeout parameters.
- **Acceptance Criteria:**
  - Field `timeoutMs: Long = 60_000L` for join and operation timeouts.

---

## 3. Presentation and State Layer (`cl.zea.glia.ui.GliaChatViewModel`)

### REQ-VM-01: Protection Against Concurrent Sends (`isStreaming` Guard)
- **Description:** Prevent user or UI from dispatching multiple concurrent prompts while the assistant is streaming responses.
- **Acceptance Criteria:**
  - If `uiState.value.isStreaming == true`, ignore calls to `send()`.

### REQ-VM-02: Initial History Loading and Update Callback
- **Description:** Allow injecting previous messages and observing message list updates for persistence.
- **Acceptance Criteria:**
  - Constructor with `initialMessages: List<GliaChatMessage> = emptyList()`.
  - Callback `onMessagesUpdated: ((List<GliaChatMessage>) -> Unit)? = null`.
  - Methods `loadMessages(newMessages)` and `clearMessages()`.
  - Trigger `onMessagesUpdated` on user message send and assistant message completion.

### REQ-VM-03: Preservation of `toolName` in Final Assistant Bubble
- **Description:** When a tool executes, `tool_call` sets the tool name, but `tool_result` clears it. Upon `done`, the assistant message should retain the tool reference.
- **Acceptance Criteria:**
  - Track `lastExecutedTool` in ViewModel and assign it to the assistant's final `GliaChatMessage`.

---

## 4. Jetpack Compose UI Component (`cl.zea.glia.ui.GliaChat`)

### REQ-UI-01: Lifecycle Disconnect Control (`disconnectOnDispose`)
- **Description:** Prevent accidental disconnections when navigating tabs or opening bottom sheets.
- **Acceptance Criteria:**
  - Parameter `disconnectOnDispose: Boolean = false` in `GliaChat`.

### REQ-UI-02: Collapsible Reasoning Block (Thinking Accordion)
- **Description:** Reasoning model outputs generate verbose text in `thinking_delta`.
- **Acceptance Criteria:**
  - Collapsible header "Reasoning Process" with psychology icon and chevron.
  - Collapsed by default in historical messages, expandable on demand.

### REQ-UI-03: Support for `pendingPrompt`
- **Description:** Allow injecting prompts from external views (e.g. deep links, shortcuts).
- **Acceptance Criteria:**
  - Parameter `pendingPrompt: String? = null` with `onPromptConsumed: () -> Unit`.

### REQ-UI-04: Streaming Scroll Optimization
- **Description:** Avoid continuous heavy animations during rapid incoming text tokens.
- **Acceptance Criteria:**
  - Direct scroll (`scrollToItem`) during continuous deltas; `animateScrollToItem` only when new messages are added.

---

## 5. Unit Testing and CI/CD Quality

### REQ-QA-01: Deterministic Unit Test Suite
- **Acceptance Criteria:**
  - Tests for `GliaClient`: Successful handshake, unauthorized error, URL normalization, streaming parsing (`thinking_delta`, `message_delta`, `tool_call`, `done`), voluntary disconnection.
  - Tests for `GliaChatViewModel`: Connection, delta accumulation, concurrent send guard, toolName preservation, message serialization.

### REQ-CI-01: GCP Cloud Build Pipeline (`cloudbuild.yaml`)
- **Acceptance Criteria:**
  - Execute unit tests (`./gradlew testDebugUnitTest`).
  - Security audit with `microglia scan . --sarif reports/microglia.sarif`.

---

## 6. Multi-Backend Architecture (Agent Connectors)

### REQ-BACKEND-01: Agent Transport Abstraction (`GliaBackendType`)
- **Description:** The SDK must connect not only to ZEA Glia (Phoenix Channels), but also to external agent backends based on HTTP Server-Sent Events (SSE) and standard streaming (Dify.ai, LangGraph, OpenAI Assistants).
- **Acceptance Criteria:**
  - `GliaClientProtocol` remains the universal agnostic contract.
  - Support configuration enum:
    ```kotlin
    enum class GliaBackendType {
        PHOENIX, // Glia Elixir runtime (default)
        SSE      // Dify, LangGraph, OpenAI SSE
    }
    ```
  - `PhoenixAgentClient`: Implementation based on Phoenix Channels v2.
  - `SseAgentClient`: Implementation based on HTTP `text/event-stream` mapping incoming events to `GliaStreamEvent`.
  - Factory in `GliaClient`:
    ```kotlin
    val client = GliaClient.create(options, backendType = GliaBackendType.PHOENIX)
    ```
