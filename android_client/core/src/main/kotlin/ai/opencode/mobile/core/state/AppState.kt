package ai.opencode.mobile.core.state

import ai.opencode.mobile.core.model.AgentInfo
import ai.opencode.mobile.core.model.MessageWithParts
import ai.opencode.mobile.core.model.Project
import ai.opencode.mobile.core.model.Session
import ai.opencode.mobile.core.model.SessionStatus
import ai.opencode.mobile.core.model.TodoItem
import ai.opencode.mobile.core.model.FileContent
import ai.opencode.mobile.core.model.FileNode
import ai.opencode.mobile.core.model.FileStatusEntry
import ai.opencode.mobile.core.network.ServerConfig

data class AppState(
    val serverConfig: ServerConfig,
    val isConnected: Boolean = false,
    val serverVersion: String? = null,
    val connectionError: String? = null,
    val sessions: List<Session> = emptyList(),
    val currentSessionID: String? = null,
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    val messages: List<MessageWithParts> = emptyList(),
    val streamingPartTexts: Map<String, String> = emptyMap(),
    val pendingPermissions: List<PendingPermission> = emptyList(),
    val todosBySession: Map<String, List<TodoItem>> = emptyMap(),
    val projects: List<Project> = emptyList(),
    val selectedProjectWorktree: String? = null,
    val agents: List<AgentInfo> = emptyList(),
    val filePath: String = "",
    val fileNodes: List<FileNode> = emptyList(),
    val fileStatuses: List<FileStatusEntry> = emptyList(),
    val selectedFilePath: String? = null,
    val selectedFileContent: FileContent? = null
)

data class PendingPermission(
    val id: String,
    val sessionID: String,
    val permission: String? = null,
    val patterns: List<String> = emptyList()
)
