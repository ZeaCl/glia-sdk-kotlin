package cl.zea.glia.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Declarative specification of dynamic tools for the Glia agent.
 */
@Serializable
data class GliaToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    @SerialName("webhook_url")
    val webhookUrl: String
)
