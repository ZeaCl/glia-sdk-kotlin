package cl.zea.glia.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Especificación declarativa de herramientas (Tools) dinámicas para el agente Glia.
 */
@Serializable
data class GliaToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    @SerialName("webhook_url")
    val webhookUrl: String
)
