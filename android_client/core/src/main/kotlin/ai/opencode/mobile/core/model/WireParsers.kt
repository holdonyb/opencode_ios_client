package ai.opencode.mobile.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object WireParsers {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    fun parseProviders(raw: String): ProvidersResponse {
        val root = json.parseToJsonElement(raw).jsonObject
        val default = root["default"]?.let { json.decodeFromJsonElement<DefaultProvider>(it) }
        val providersRaw = root["providers"]

        val providers = when (providersRaw) {
            is JsonArray -> providersRaw.mapNotNull { parseProviderElement(it) }
            is JsonObject -> providersRaw.entries.mapNotNull { (providerID, providerValue) ->
                val parsed = parseProviderElement(providerValue) ?: return@mapNotNull null
                if (parsed.id.isNotEmpty()) parsed else parsed.copy(id = providerID)
            }
            else -> emptyList()
        }

        return ProvidersResponse(
            providers = providers.sortedBy { it.id },
            default = default
        )
    }

    fun parseMessages(raw: String): List<MessageWithParts> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        val root = json.parseToJsonElement(trimmed)

        if (root is JsonArray) {
            return root.mapNotNull { parseMessageContainer(it) }
        }

        if (root is JsonObject) {
            val candidateArrays = listOf("messages", "data", "result")
            for (key in candidateArrays) {
                val arr = root[key]
                if (arr is JsonArray) {
                    return arr.mapNotNull { parseMessageContainer(it) }
                }
            }
            parseMessageContainer(root)?.let { return listOf(it) }
        }

        return emptyList()
    }

    private fun parseProviderElement(element: JsonElement): ConfigProvider? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        val models = parseModels(obj["models"])
        return ConfigProvider(
            id = id,
            name = name,
            models = models
        )
    }

    private fun parseModels(modelsRaw: JsonElement?): Map<String, ProviderModel> {
        return when (modelsRaw) {
            is JsonObject -> modelsRaw.entries.mapNotNull { (modelID, modelValue) ->
                val parsed = parseModelElement(modelValue, modelID) ?: return@mapNotNull null
                modelID to parsed
            }.toMap()
            is JsonArray -> modelsRaw.mapNotNull {
                val parsed = parseModelElement(it, null) ?: return@mapNotNull null
                parsed.id.takeIf { id -> id.isNotEmpty() }?.let { id -> id to parsed }
            }.toMap()
            else -> emptyMap()
        }
    }

    private fun parseModelElement(element: JsonElement, fallbackID: String?): ProviderModel? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: fallbackID.orEmpty()
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        val providerID = obj["providerID"]?.jsonPrimitive?.contentOrNull
            ?: obj["providerId"]?.jsonPrimitive?.contentOrNull
        val limit = obj["limit"]?.let {
            json.decodeFromJsonElement<ProviderModelLimit>(it)
        }
        return ProviderModel(id = id, name = name, providerID = providerID, limit = limit)
    }

    private fun parseMessageContainer(element: JsonElement): MessageWithParts? {
        val obj = element as? JsonObject ?: return null
        return if ("info" in obj) {
            json.decodeFromJsonElement<MessageWithParts>(obj)
        } else {
            val info = json.decodeFromJsonElement<Message>(obj)
            MessageWithParts(info = info, parts = emptyList())
        }
    }

    fun buildPromptBody(text: String, agent: String, model: Message.ModelInfo?): String {
        val root = buildJsonObject {
            put("parts", JsonArray(listOf(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(text)) })))
            put("agent", JsonPrimitive(agent))
            if (model != null) {
                put(
                    "model",
                    buildJsonObject {
                        put("providerID", JsonPrimitive(model.providerID))
                        put("modelID", JsonPrimitive(model.modelID))
                    }
                )
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }
}

