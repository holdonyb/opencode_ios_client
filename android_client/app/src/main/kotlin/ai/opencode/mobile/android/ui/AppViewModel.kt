package ai.opencode.mobile.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.opencode.mobile.android.ssh.AndroidSshTunnelManager
import ai.opencode.mobile.android.ssh.SshTunnelConfig
import ai.opencode.mobile.core.model.Message
import ai.opencode.mobile.core.network.HttpOpenCodeApi
import ai.opencode.mobile.core.network.OpenCodeApi
import ai.opencode.mobile.core.network.ServerConfig
import ai.opencode.mobile.core.state.AppState
import ai.opencode.mobile.core.state.AppStateStore
import ai.opencode.mobile.core.state.SseSideEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppViewModel : ViewModel() {
    data class SettingsForm(
        val baseUrl: String = "http://127.0.0.1:4096",
        val username: String = "",
        val password: String = ""
    )

    data class ModelPreset(
        val displayName: String,
        val providerID: String,
        val modelID: String
    )

    data class SshForm(
        val host: String = "",
        val port: String = "22",
        val username: String = "",
        val password: String = "",
        val useKeyAuth: Boolean = false,
        val privateKeyPem: String = "",
        val privateKeyPassphrase: String = "",
        val remotePort: String = "18080",
        val localPort: String = "14096"
    )

    private val modelPresets = listOf(
        ModelPreset("GLM-5", "zai-coding-plan", "glm-5"),
        ModelPreset("Opus 4.6", "anthropic", "claude-opus-4-6"),
        ModelPreset("Sonnet 4.6", "anthropic", "claude-sonnet-4-6"),
        ModelPreset("GPT-5.3 Codex", "openai", "gpt-5.3-codex"),
        ModelPreset("GPT-5.2", "openai", "gpt-5.2"),
        ModelPreset("Gemini 3.1 Pro", "google", "gemini-3.1-pro-preview"),
        ModelPreset("Gemini 3 Flash", "google", "gemini-3-flash-preview")
    )

    private var currentConfig = ServerConfig(baseUrl = "http://127.0.0.1:4096")
    private var api = HttpOpenCodeApi(currentConfig)
    private val store = AppStateStore(currentConfig)
    private val sshManager = AndroidSshTunnelManager()
    private var sseJob: Job? = null
    private val draftBySession = mutableMapOf<String, String>()
    private val modelBySession = mutableMapOf<String, Int>()
    private val agentBySession = mutableMapOf<String, String>()

    val state: StateFlow<AppState> = store.state

    private val _settingsForm = MutableStateFlow(SettingsForm(baseUrl = currentConfig.baseUrl))
    val settingsForm: StateFlow<SettingsForm> = _settingsForm.asStateFlow()

    private val _chatInput = MutableStateFlow("")
    val chatInput: StateFlow<String> = _chatInput.asStateFlow()

    private val _sessionTitleInput = MutableStateFlow("")
    val sessionTitleInput: StateFlow<String> = _sessionTitleInput.asStateFlow()

    private val _selectedModelIndex = MutableStateFlow(0)
    val selectedModelIndex: StateFlow<Int> = _selectedModelIndex.asStateFlow()

    private val _selectedAgentName = MutableStateFlow("build")
    val selectedAgentName: StateFlow<String> = _selectedAgentName.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _sshForm = MutableStateFlow(SshForm())
    val sshForm: StateFlow<SshForm> = _sshForm.asStateFlow()

    private val _sshStatus = MutableStateFlow("Disconnected")
    val sshStatus: StateFlow<String> = _sshStatus.asStateFlow()

    val models: List<ModelPreset> = modelPresets

    init {
        refreshAll()
    }

    fun setSettingsBaseUrl(value: String) = _settingsForm.update { it.copy(baseUrl = value) }
    fun setSettingsUsername(value: String) = _settingsForm.update { it.copy(username = value) }
    fun setSettingsPassword(value: String) = _settingsForm.update { it.copy(password = value) }
    fun setChatInput(value: String) {
        _chatInput.value = value
        state.value.currentSessionID?.let { sid ->
            if (value.isBlank()) {
                draftBySession.remove(sid)
            } else {
                draftBySession[sid] = value
            }
        }
    }
    fun setSessionTitleInput(value: String) = _sessionTitleInput.update { value }
    fun setSelectedModel(index: Int) {
        val clamped = index.coerceIn(0, modelPresets.lastIndex)
        _selectedModelIndex.value = clamped
        state.value.currentSessionID?.let { sid -> modelBySession[sid] = clamped }
    }

    fun setSelectedAgent(name: String) {
        val normalized = if (name.isBlank()) "build" else name
        _selectedAgentName.value = normalized
        state.value.currentSessionID?.let { sid -> agentBySession[sid] = normalized }
    }

    fun setSshHost(value: String) = _sshForm.update { it.copy(host = value) }
    fun setSshPort(value: String) = _sshForm.update { it.copy(port = value) }
    fun setSshUsername(value: String) = _sshForm.update { it.copy(username = value) }
    fun setSshPassword(value: String) = _sshForm.update { it.copy(password = value) }
    fun setSshUseKeyAuth(value: Boolean) = _sshForm.update { it.copy(useKeyAuth = value) }
    fun setSshPrivateKeyPem(value: String) = _sshForm.update { it.copy(privateKeyPem = value) }
    fun setSshPrivateKeyPassphrase(value: String) = _sshForm.update { it.copy(privateKeyPassphrase = value) }
    fun setSshRemotePort(value: String) = _sshForm.update { it.copy(remotePort = value) }
    fun setSshLocalPort(value: String) = _sshForm.update { it.copy(localPort = value) }

    fun applySettingsAndReconnect() {
        val normalizedBase = normalizeBaseUrl(_settingsForm.value.baseUrl)
        currentConfig = ServerConfig(
            baseUrl = normalizedBase,
            username = _settingsForm.value.username.ifBlank { null },
            password = _settingsForm.value.password.ifBlank { null }
        )
        api = HttpOpenCodeApi(currentConfig)
        store.setServerConfig(currentConfig)
        draftBySession.clear()
        modelBySession.clear()
        agentBySession.clear()
        _chatInput.value = ""
        _selectedModelIndex.value = 0
        _selectedAgentName.value = "build"
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _lastError.value = null
            try {
                val health = api.health()
                store.setConnection(connected = health.healthy, version = health.version, error = null)

                runCatching { api.projects() }.onSuccess { store.setProjects(it) }
                runCatching { api.agents() }.onSuccess { agents ->
                    store.setAgents(agents.filter { it.hidden != true })
                    val first = agents.firstOrNull { it.hidden != true }?.name
                    if (!first.isNullOrBlank() && _selectedAgentName.value == "build" && state.value.currentSessionID == null) {
                        _selectedAgentName.value = first
                    }
                }

                loadSessions()
                loadSessionStatuses()
                loadCurrentSessionData()
                loadFileRoot()
                loadFileStatuses()
                startSse()
            } catch (e: Exception) {
                store.setConnection(connected = false, error = e.message)
                _lastError.value = e.message
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun connectSshTunnel() {
        val form = _sshForm.value
        val config = SshTunnelConfig(
            host = form.host.trim(),
            port = form.port.toIntOrNull() ?: 22,
            username = form.username.trim(),
            password = form.password,
            privateKeyPem = form.privateKeyPem.takeIf { form.useKeyAuth && it.isNotBlank() },
            privateKeyPassphrase = form.privateKeyPassphrase.takeIf { form.useKeyAuth && it.isNotBlank() },
            remotePort = form.remotePort.toIntOrNull() ?: 18080,
            localPort = form.localPort.toIntOrNull() ?: 14096
        )
        if (config.host.isBlank() || config.username.isBlank()) {
            _lastError.value = "SSH host and username are required"
            return
        }
        if (!form.useKeyAuth && config.password.isBlank()) {
            _lastError.value = "SSH password is required for password auth"
            return
        }
        if (form.useKeyAuth && config.privateKeyPem.isNullOrBlank()) {
            _lastError.value = "SSH private key is required for key auth"
            return
        }

        viewModelScope.launch {
            _sshStatus.value = "Connecting..."
            sshManager.connect(config)
                .onSuccess { local ->
                    _sshStatus.value = "Connected (127.0.0.1:$local)"
                    _settingsForm.update { it.copy(baseUrl = "http://127.0.0.1:$local") }
                }
                .onFailure {
                    _sshStatus.value = "Error"
                    _lastError.value = it.message
                }
        }
    }

    fun disconnectSshTunnel() {
        viewModelScope.launch {
            sshManager.disconnect()
            _sshStatus.value = "Disconnected"
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            runCatching {
                val selected = state.value.selectedProjectWorktree
                api.sessions(directory = selected, limit = 100)
            }.onSuccess { sessions ->
                store.setSessions(sessions.sortedByDescending { it.time.updated })
                restoreSessionScopedSelections(state.value.currentSessionID)
            }.onFailure {
                _lastError.value = it.message
            }
        }
    }

    fun selectProject(worktree: String?) {
        store.setSelectedProject(worktree)
        loadSessions()
    }

    fun selectSession(sessionID: String) {
        store.setCurrentSession(sessionID)
        restoreSessionScopedSelections(sessionID)
        loadCurrentSessionData()
    }

    fun createSession() {
        viewModelScope.launch {
            val title = _sessionTitleInput.value.trim().ifBlank { null }
            runCatching { api.createSession(title) }
                .onSuccess { created ->
                    _sessionTitleInput.value = ""
                    loadSessions()
                    store.setCurrentSession(created.id)
                    restoreSessionScopedSelections(created.id)
                    loadCurrentSessionData()
                }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun renameCurrentSession() {
        val sid = state.value.currentSessionID ?: return
        val title = _sessionTitleInput.value.trim()
        if (title.isBlank()) return
        viewModelScope.launch {
            runCatching { api.updateSession(sid, title) }
                .onSuccess {
                    _sessionTitleInput.value = ""
                    loadSessions()
                }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun deleteCurrentSession() {
        val sid = state.value.currentSessionID ?: return
        viewModelScope.launch {
            runCatching { api.deleteSession(sid) }
                .onSuccess {
                    draftBySession.remove(sid)
                    modelBySession.remove(sid)
                    agentBySession.remove(sid)
                    loadSessions()
                    store.setCurrentSession(state.value.sessions.firstOrNull()?.id)
                    restoreSessionScopedSelections(state.value.currentSessionID)
                    loadCurrentSessionData()
                }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun sendMessage() {
        val text = _chatInput.value.trim()
        val sid = state.value.currentSessionID ?: return
        if (text.isEmpty()) return

        val model = modelPresets.getOrNull(_selectedModelIndex.value)?.let {
            Message.ModelInfo(providerID = it.providerID, modelID = it.modelID)
        }
        val agent = _selectedAgentName.value

        _chatInput.value = ""
        draftBySession.remove(sid)
        viewModelScope.launch {
            runCatching {
                api.promptAsync(
                    sessionID = sid,
                    text = text,
                    agent = agent,
                    model = model
                )
            }.onSuccess {
                loadMessages(sid)
            }.onFailure {
                _lastError.value = it.message
                _chatInput.value = text
                draftBySession[sid] = text
            }
        }
    }

    fun abortCurrentSession() {
        val sid = state.value.currentSessionID ?: return
        viewModelScope.launch {
            runCatching { api.abort(sid) }
                .onSuccess {
                    loadSessionStatuses()
                    loadMessages(sid)
                }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun respondPermission(sessionID: String, permissionID: String, allowAlways: Boolean = false) {
        viewModelScope.launch {
            val response = if (allowAlways) OpenCodeApi.PermissionResponse.Always else OpenCodeApi.PermissionResponse.Once
            runCatching { api.respondPermission(sessionID, permissionID, response) }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun rejectPermission(sessionID: String, permissionID: String) {
        viewModelScope.launch {
            runCatching { api.respondPermission(sessionID, permissionID, OpenCodeApi.PermissionResponse.Reject) }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun loadFileRoot() = loadFileList(path = "")

    fun loadFileList(path: String) {
        viewModelScope.launch {
            runCatching { api.fileList(path) }
                .onSuccess { store.setFileNodes(path, it.sortedWith(compareBy({ it.type != "directory" }, { it.name.lowercase() }))) }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun openFile(path: String) {
        viewModelScope.launch {
            runCatching { api.fileContent(path) }
                .onSuccess { store.setSelectedFile(path, it) }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    private fun loadCurrentSessionData() {
        val sid = state.value.currentSessionID ?: return
        loadMessages(sid)
        loadSessionTodos(sid)
    }

    private fun loadMessages(sessionID: String) {
        viewModelScope.launch {
            runCatching { api.messages(sessionID = sessionID, limit = 60) }
                .onSuccess { store.setMessages(sessionID, it) }
                .onFailure { _lastError.value = it.message }
        }
    }

    private fun loadSessionTodos(sessionID: String) {
        viewModelScope.launch {
            runCatching { api.sessionTodos(sessionID) }
                .onSuccess { store.setSessionTodos(sessionID, it) }
        }
    }

    private fun loadSessionStatuses() {
        viewModelScope.launch {
            runCatching { api.sessionStatus() }.onSuccess { statuses ->
                store.setSessionStatuses(statuses)
            }
        }
    }

    private fun loadFileStatuses() {
        viewModelScope.launch {
            runCatching { api.fileStatus() }
                .onSuccess { store.setFileStatuses(it) }
        }
    }

    private fun startSse() {
        sseJob?.cancel()
        sseJob = viewModelScope.launch {
            api.globalEvents().collect { event ->
                when (store.applySseEvent(event)) {
                    SseSideEffect.None -> Unit
                    SseSideEffect.FullSync -> {
                        loadSessionStatuses()
                        loadCurrentSessionData()
                    }
                    SseSideEffect.ReloadSessions -> loadSessions()
                    SseSideEffect.ReloadMessages -> state.value.currentSessionID?.let { loadMessages(it) }
                }
            }
        }
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "http://127.0.0.1:4096"
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    private fun restoreSessionScopedSelections(sessionID: String?) {
        if (sessionID == null) {
            _chatInput.value = ""
            _selectedModelIndex.value = 0
            return
        }
        _chatInput.value = draftBySession[sessionID].orEmpty()
        _selectedModelIndex.value = modelBySession[sessionID] ?: 0
        val defaultAgent = state.value.agents.firstOrNull()?.name ?: "build"
        _selectedAgentName.value = agentBySession[sessionID] ?: defaultAgent
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
        viewModelScope.launch {
            sshManager.disconnect()
        }
    }
}
