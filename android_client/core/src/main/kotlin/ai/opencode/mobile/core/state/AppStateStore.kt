package ai.opencode.mobile.core.state

import ai.opencode.mobile.core.model.MessageWithParts
import ai.opencode.mobile.core.model.Session
import ai.opencode.mobile.core.model.SseEvent
import ai.opencode.mobile.core.model.TodoItem
import ai.opencode.mobile.core.model.AgentInfo
import ai.opencode.mobile.core.model.Project
import ai.opencode.mobile.core.model.FileNode
import ai.opencode.mobile.core.model.FileStatusEntry
import ai.opencode.mobile.core.model.FileContent
import ai.opencode.mobile.core.model.SessionStatus
import ai.opencode.mobile.core.network.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

class AppStateStore(config: ServerConfig) {
    private val _state = MutableStateFlow(AppState(serverConfig = config))
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun setServerConfig(config: ServerConfig) {
        _state.update { old ->
            old.copy(
                serverConfig = config,
                isConnected = false,
                connectionError = null,
                sessions = emptyList(),
                currentSessionID = null,
                messages = emptyList(),
                streamingPartTexts = emptyMap(),
                sessionStatuses = emptyMap(),
                todosBySession = emptyMap(),
                projects = emptyList(),
                agents = emptyList(),
                filePath = "",
                fileNodes = emptyList(),
                fileStatuses = emptyList(),
                selectedFilePath = null,
                selectedFileContent = null
            )
        }
    }

    fun setConnection(connected: Boolean, version: String? = null, error: String? = null) {
        _state.update { old ->
            old.copy(
                isConnected = connected,
                serverVersion = version ?: old.serverVersion,
                connectionError = error
            )
        }
    }

    fun setProjects(projects: List<Project>) {
        _state.update { old ->
            val selected = old.selectedProjectWorktree
            val keep = if (selected != null && projects.none { it.worktree == selected }) null else selected
            old.copy(projects = projects, selectedProjectWorktree = keep)
        }
    }

    fun setSelectedProject(worktree: String?) {
        _state.update { it.copy(selectedProjectWorktree = worktree) }
    }

    fun setAgents(agents: List<AgentInfo>) {
        _state.update { it.copy(agents = agents) }
    }

    fun setSessionStatuses(statuses: Map<String, SessionStatus>) {
        _state.update { it.copy(sessionStatuses = statuses) }
    }

    fun setSessionTodos(sessionID: String, todos: List<TodoItem>) {
        _state.update { old ->
            old.copy(todosBySession = old.todosBySession + (sessionID to todos))
        }
    }

    fun setFileNodes(path: String, nodes: List<FileNode>) {
        _state.update { it.copy(filePath = path, fileNodes = nodes) }
    }

    fun setFileStatuses(statuses: List<FileStatusEntry>) {
        _state.update { it.copy(fileStatuses = statuses) }
    }

    fun setSelectedFile(path: String?, content: FileContent?) {
        _state.update { it.copy(selectedFilePath = path, selectedFileContent = content) }
    }

    fun setSessions(sessions: List<Session>) {
        _state.update { old ->
            old.copy(
                sessions = sessions,
                currentSessionID = old.currentSessionID ?: sessions.firstOrNull()?.id
            )
        }
    }

    fun setCurrentSession(sessionID: String?) {
        _state.update {
            it.copy(
                currentSessionID = sessionID,
                messages = emptyList(),
                streamingPartTexts = emptyMap()
            )
        }
    }

    fun setMessages(requestedSessionID: String, messages: List<MessageWithParts>) {
        _state.update { old ->
            if (old.currentSessionID != requestedSessionID) return@update old
            old.copy(messages = messages)
        }
    }

    fun applySseEvent(event: SseEvent): SseSideEffect {
        val type = event.payload.type
        val properties: JsonObject = event.payload.properties ?: buildJsonObject { }

        return when (type) {
            "server.connected" -> SseSideEffect.FullSync
            "session.status" -> {
                val sessionID = properties.string("sessionID")
                val statusObj = properties.obj("status")
                if (sessionID == null || statusObj == null) return SseSideEffect.None

                val nextStatusType = statusObj.string("type") ?: return SseSideEffect.None
                _state.update { old ->
                    val map = old.sessionStatuses.toMutableMap()
                    map[sessionID] = ai.opencode.mobile.core.model.SessionStatus(
                        type = nextStatusType,
                        attempt = statusObj.int("attempt"),
                        message = statusObj.string("message"),
                        next = statusObj.long("next")
                    )
                    old.copy(sessionStatuses = map)
                }
                SseSideEffect.None
            }
            "session.updated" -> {
                SseSideEffect.ReloadSessions
            }
            "session.deleted" -> {
                val sessionID = properties.string("sessionID") ?: properties.string("id")
                if (sessionID != null) {
                    _state.update { old ->
                        old.copy(
                            sessions = old.sessions.filterNot { it.id == sessionID },
                            sessionStatuses = old.sessionStatuses.filterKeys { it != sessionID },
                            todosBySession = old.todosBySession.filterKeys { it != sessionID },
                            pendingPermissions = old.pendingPermissions.filterNot { it.sessionID == sessionID },
                            currentSessionID = if (old.currentSessionID == sessionID) null else old.currentSessionID
                        )
                    }
                }
                SseSideEffect.ReloadSessions
            }
            "message.updated" -> SseSideEffect.ReloadMessages
            "message.part.updated" -> handlePartUpdated(properties)
            "permission.asked" -> {
                val sid = properties.string("sessionID") ?: return SseSideEffect.None
                val permissionID = properties.string("permissionID") ?: properties.string("id") ?: return SseSideEffect.None
                _state.update { old ->
                    if (old.pendingPermissions.any { it.id == permissionID && it.sessionID == sid }) return@update old
                    old.copy(
                        pendingPermissions = old.pendingPermissions + PendingPermission(
                            id = permissionID,
                            sessionID = sid,
                            permission = properties.string("permission"),
                            patterns = properties.arrayOfString("patterns")
                        )
                    )
                }
                SseSideEffect.None
            }
            "permission.replied" -> {
                val sid = properties.string("sessionID")
                val permissionID = properties.string("permissionID") ?: properties.string("id")
                if (sid != null && permissionID != null) {
                    _state.update { old ->
                        old.copy(
                            pendingPermissions = old.pendingPermissions.filterNot {
                                it.sessionID == sid && it.id == permissionID
                            }
                        )
                    }
                }
                SseSideEffect.None
            }
            "todo.updated" -> {
                val sid = properties.string("sessionID") ?: return SseSideEffect.None
                val todosArr = properties["todos"] as? JsonArray ?: return SseSideEffect.None
                val todos = todosArr.map { item ->
                    val obj = item as? JsonObject
                    TodoItem(
                        id = obj?.string("id"),
                        content = obj?.string("content"),
                        status = obj?.string("status"),
                        priority = obj?.string("priority"),
                        completed = obj?.bool("completed")
                    )
                }
                _state.update { old ->
                    old.copy(todosBySession = old.todosBySession + (sid to todos))
                }
                SseSideEffect.None
            }
            else -> SseSideEffect.None
        }
    }

    private fun handlePartUpdated(properties: JsonObject): SseSideEffect {
        val sid = properties.string("sessionID") ?: return SseSideEffect.None
        if (sid != _state.value.currentSessionID) return SseSideEffect.None

        val partObj = properties.obj("part") ?: return SseSideEffect.None
        val messageID = partObj.string("messageID") ?: return SseSideEffect.None
        val partID = partObj.string("id") ?: return SseSideEffect.None
        val key = "$messageID:$partID"
        val delta = properties.string("delta")

        if (!delta.isNullOrEmpty()) {
            _state.update { old ->
                val current = old.streamingPartTexts[key].orEmpty()
                old.copy(streamingPartTexts = old.streamingPartTexts + (key to (current + delta)))
            }
            return SseSideEffect.None
        }
        return SseSideEffect.ReloadMessages
    }
}

enum class SseSideEffect {
    None,
    FullSync,
    ReloadSessions,
    ReloadMessages
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()
private fun JsonObject.bool(key: String): Boolean? = string(key)?.toBooleanStrictOrNull()
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arrayOfString(key: String): List<String> {
    val arr = this[key] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
