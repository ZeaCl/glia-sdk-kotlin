package cl.zea.glia

import cl.zea.glia.core.models.GliaOptions
import cl.zea.glia.core.models.GliaToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GliaClientTest {

    @Test
    fun testGliaOptionsTopic() {
        val options = GliaOptions(
            gatewayUrl = "wss://glia.example.com",
            appId = "nutrisnaps",
            userId = "user_123"
        )
        assertEquals("session:nutrisnaps:user_123", options.topic)
    }

    @Test
    fun testGliaOptionsWsUrlFormatting() {
        val options = GliaOptions(
            gatewayUrl = "http://localhost:4003",
            appId = "test",
            userId = "usr",
            token = "my_token"
        )

        assertTrue(options.wsUrl.startsWith("ws://localhost:4003/socket/websocket"))
        assertTrue(options.wsUrl.contains("vsn=2.0.0"))
        assertTrue(options.wsUrl.contains("token=my_token"))
    }

    @Test
    fun testToolDefinitionSerialization() {
        val params = buildJsonObject {
            put("type", "object")
        }
        val tool = GliaToolDefinition(
            name = "calculate_macro",
            description = "Calcula macros de una comida",
            parameters = params,
            webhookUrl = "https://api.example.com/macros"
        )

        assertEquals("calculate_macro", tool.name)
        assertEquals("https://api.example.com/macros", tool.webhookUrl)
    }
}
