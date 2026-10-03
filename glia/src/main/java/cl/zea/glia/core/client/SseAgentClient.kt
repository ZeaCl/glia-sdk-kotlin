package cl.zea.glia.core.client

import cl.zea.glia.core.models.GliaOptions
import cl.zea.glia.core.providers.sse.SseAgentProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Backward-compatible implementation of [SseAgentProvider] located in the client package.
 */
open class SseAgentClient(
    options: GliaOptions,
    httpClient: HttpClient = HttpClient(OkHttp)
) : SseAgentProvider(options, httpClient)
