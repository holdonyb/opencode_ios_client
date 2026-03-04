package ai.opencode.mobile.core.network

import ai.opencode.mobile.core.model.AgentInfo
import ai.opencode.mobile.core.model.FileContent
import ai.opencode.mobile.core.model.FileNode
import ai.opencode.mobile.core.model.FileStatusEntry
import ai.opencode.mobile.core.model.HealthResponse
import ai.opencode.mobile.core.model.Message
import ai.opencode.mobile.core.model.MessageWithParts
import ai.opencode.mobile.core.model.Project
import ai.opencode.mobile.core.model.ProvidersResponse
import ai.opencode.mobile.core.model.Session
import ai.opencode.mobile.core.model.SessionStatus
import ai.opencode.mobile.core.model.SseEvent
import ai.opencode.mobile.core.model.TodoItem
import kotlinx.coroutines.flow.Flow

data class ServerConfig(
    val baseUrl: String,
    val username: String? = null,
    val password: String? = null
)

interface OpenCodeApi {
    enum class PermissionResponse(val wireValue: String) {
        Once("once"),
        Always("always"),
        Reject("reject")
    }

    suspend fun health(): HealthResponse
    suspend fun projects(): List<Project>
    suspend fun projectCurrent(): Project?
    suspend fun sessions(directory: String? = null, limit: Int = 100): List<Session>
    suspend fun createSession(title: String? = null): Session
    suspend fun updateSession(sessionID: String, title: String): Session
    suspend fun deleteSession(sessionID: String)
    suspend fun sessionStatus(): Map<String, SessionStatus>
    suspend fun messages(sessionID: String, limit: Int? = null): List<MessageWithParts>
    suspend fun promptAsync(sessionID: String, text: String, agent: String, model: Message.ModelInfo? = null)
    suspend fun abort(sessionID: String)
    suspend fun providers(): ProvidersResponse
    suspend fun agents(): List<AgentInfo>
    suspend fun sessionTodos(sessionID: String): List<TodoItem>
    suspend fun fileList(path: String = ""): List<FileNode>
    suspend fun fileContent(path: String): FileContent
    suspend fun fileStatus(): List<FileStatusEntry>
    suspend fun findFile(query: String, limit: Int = 50): List<String>
    suspend fun respondPermission(sessionID: String, permissionID: String, response: PermissionResponse)
    fun globalEvents(): Flow<SseEvent>
}
