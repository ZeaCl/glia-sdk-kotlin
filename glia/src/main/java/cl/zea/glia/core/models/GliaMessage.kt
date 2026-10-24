package cl.zea.glia.core.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

object GliaMessageRoleSerializer : KSerializer<GliaMessageRole> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("GliaMessageRole", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: GliaMessageRole) {
        encoder.encodeString(value.name.lowercase())
    }

    override fun deserialize(decoder: Decoder): GliaMessageRole {
        val str = decoder.decodeString()
        return when (str.lowercase()) {
            "user" -> GliaMessageRole.USER
            "assistant" -> GliaMessageRole.ASSISTANT
            else -> GliaMessageRole.ASSISTANT
        }
    }
}

@Serializable(with = GliaMessageRoleSerializer::class)
enum class GliaMessageRole {
    @SerialName("user")
    USER,
    @SerialName("assistant")
    ASSISTANT;

    companion object {
        fun fromString(value: String): GliaMessageRole = when (value.lowercase()) {
            "user" -> USER
            "assistant" -> ASSISTANT
            else -> ASSISTANT
        }
    }
}

@Serializable
data class GliaChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: GliaMessageRole,
    val content: String = "",
    val thinking: String? = null,
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val sequence: Int? = null,
    @SerialName("inserted_at")
    val insertedAt: String? = null
)

typealias GliaMessage = GliaChatMessage

@Serializable
data class GliaHistoryResponse(
    val messages: List<GliaChatMessage> = emptyList(),
    @SerialName("has_more")
    val hasMore: Boolean = false,
    @SerialName("session_id")
    val sessionId: String? = null
)
