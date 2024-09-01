import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SerializationTest {
    @Serializable
    data class Message(
        val message: String,
        val from: Int,
        val token: String
    )
    private val json = Json { ignoreUnknownKeys = true } // 配置Json解析

    @Test
    fun `test serialize and deserialize with valid input`() {
        val originalMessage = Message("Hello from Node", 1, "valid_token")
        val jsonString = json.encodeToString(Message.serializer(), originalMessage)

        val deserializedMessage = json.decodeFromString(Message.serializer(), jsonString)

        assertEquals(originalMessage, deserializedMessage, "Deserialized message should match the original message.")
    }

    @Test
    fun `test deserialize with invalid JSON`() {
        val invalidJson = """
            {
                "message": "Hello from Node",
                "from": "invalid_number",
                "token": "some_token"
            }
        """.trimIndent()

        assertThrows(SerializationException::class.java) {
            json.decodeFromString(Message.serializer(), invalidJson)
        }
    }

    @Test
    fun `test deserialize with malformed JSON`() {
        val malformedJson = """
            {
                "message": "Hello from Node",
                "from": 1,
                "token": "some_token"
            """.trimIndent()

        assertThrows(SerializationException::class.java) {
            json.decodeFromString(Message.serializer(), malformedJson)
        }
    }
}
