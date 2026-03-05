package ai.opencode.mobile.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class HealthResponse(
    val healthy: Boolean,
    val version: String? = null
)

@Serializable
data class Project(
    val id: String,
    val worktree: String,
    val vcs: String? = null
)

@Serializable
data class Session(
    val id: String,
    val slug: String = "",
    val projectID: String = "",
    val directory: String,
    val parentID: String? = null,
    val title: String,
    val version: String = "",
    val time: TimeInfo,
    val summary: SummaryInfo? = null
) {
    @Serializable
    data class TimeInfo(
        val created: Long,
        val updated: Long,
        val archived: Long? = null
    )

    @Serializable
    data class SummaryInfo(
        val additions: Int = 0,
        val deletions: Int = 0,
        val files: Int = 0
    )
}

@Serializable
data class SessionStatus(
    val type: String,
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null
)

@Serializable
data class MessageWithParts(
    val info: Message,
    val parts: List<Part> = emptyList()
)

@Serializable
data class Message(
    val id: String,
    val sessionID: String,
    val role: String,
    val parentID: String? = null,
    val providerID: String? = null,
    val modelID: String? = null,
    val model: ModelInfo? = null,
    val tokens: TokenInfo? = null,
    val cost: Double? = null,
    val time: TimeInfo
) {
    @Serializable
    data class ModelInfo(
        val providerID: String,
        val modelID: String
    )

    @Serializable
    data class TokenInfo(
        val total: Int? = null,
        val input: Int? = null,
        val output: Int? = null,
        val reasoning: Int? = null,
        val cache: CacheInfo? = null
    ) {
        @Serializable
        data class CacheInfo(
            val read: Int? = null,
            val write: Int? = null
        )
    }

    @Serializable
    data class TimeInfo(
        val created: Long,
        val completed: Long? = null
    )
}

@Serializable
data class Part(
    val id: String,
    val messageID: String,
    val sessionID: String,
    val type: String,
    val text: String? = null,
    val tool: String? = null,
    val callID: String? = null,
    val state: JsonElement? = null,
    val metadata: JsonObject? = null,
    val files: List<FileChange>? = null
) {
    @Serializable
    data class FileChange(
        val path: String,
        val additions: Int = 0,
        val deletions: Int = 0,
        val status: String? = null
    )
}

@Serializable
data class TodoItem(
    val id: String? = null,
    val content: String? = null,
    val status: String? = null,
    val priority: String? = null,
    val completed: Boolean? = null
)

@Serializable
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val mode: String? = null,
    val hidden: Boolean? = null,
    val native: Boolean? = null
)

@Serializable
data class SseEvent(
    val directory: String? = null,
    val payload: SsePayload
)

@Serializable
data class SsePayload(
    val type: String,
    val properties: JsonObject? = null
)

@Serializable
data class FileNode(
    val name: String,
    val path: String,
    val absolute: String? = null,
    val type: String,
    val ignored: Boolean? = null
)

@Serializable
data class FileContent(
    val type: String,
    val content: String? = null
)

@Serializable
data class FileStatusEntry(
    val path: String? = null,
    val status: String? = null
)

@Serializable
data class FileDiff(
    val file: String = "",
    val before: String = "",
    val after: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null
)

@Serializable
data class ProviderModelLimit(
    val context: Int? = null,
    val input: Int? = null,
    val output: Int? = null
)

@Serializable
data class ProviderModel(
    val id: String,
    val name: String? = null,
    val providerID: String? = null,
    val limit: ProviderModelLimit? = null
)

@Serializable
data class ConfigProvider(
    val id: String,
    val name: String? = null,
    val models: Map<String, ProviderModel> = emptyMap()
)

@Serializable
data class DefaultProvider(
    @SerialName("providerID") val providerID: String? = null,
    @SerialName("modelID") val modelID: String? = null
)

data class ProvidersResponse(
    val providers: List<ConfigProvider>,
    val default: DefaultProvider?
)
