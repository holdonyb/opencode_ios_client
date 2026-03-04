package ai.opencode.mobile.core.model

import ai.opencode.mobile.core.test.GoldenLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

class WireParsersTest {
    @Test
    fun parseProviders_supportsMapAndArrayVariants() {
        val golden = WireParsers.json.parseToJsonElement(
            GoldenLoader.load("provider_variants.json")
        ).jsonObject
        val input = golden["input"]!!.jsonObject

        val objectVariant = WireParsers.parseProviders(input["variant_object"].toString())
        val arrayVariant = WireParsers.parseProviders(input["variant_array"].toString())

        assertEquals(1, objectVariant.providers.size)
        assertEquals(1, arrayVariant.providers.size)
        assertEquals("openai", objectVariant.providers.first().id)
        assertEquals("openai", arrayVariant.providers.first().id)

        val limitA = objectVariant.providers.first().models["gpt-5.2"]?.limit?.context
        val limitB = arrayVariant.providers.first().models["gpt-5.2"]?.limit?.context
        assertEquals(200000, limitA)
        assertEquals(200000, limitB)
    }

    @Test
    fun parseMessages_supportsArrayWrappedAndSingleObject() {
        val golden = WireParsers.json.parseToJsonElement(
            GoldenLoader.load("message_payload_variants.json")
        ).jsonObject["input"]!!.jsonObject

        val decodedA = WireParsers.parseMessages(golden["array_shape"].toString())
        val decodedB = WireParsers.parseMessages(golden["messages_shape"].toString())
        val decodedC = WireParsers.parseMessages(golden["single_shape"].toString())

        assertEquals("m1", decodedA.first().info.id)
        assertEquals("m2", decodedB.first().info.id)
        assertEquals("m3", decodedC.first().info.id)
        assertTrue(decodedC.first().parts.isEmpty())
    }
}

