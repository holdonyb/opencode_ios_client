package ai.opencode.mobile.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.opencode.mobile.android.speech.AIBuildersAudioClient
import ai.opencode.mobile.android.speech.AndroidAudioRecorder
import ai.opencode.mobile.android.speech.SpeechProvider
import ai.opencode.mobile.android.ssh.AndroidSshTunnelManager
import ai.opencode.mobile.android.ssh.SshTunnelConfig
import ai.opencode.mobile.android.storage.LocalSettingsStore
import ai.opencode.mobile.android.storage.SecureSecretStore
import ai.opencode.mobile.core.model.FileDiff
import ai.opencode.mobile.core.model.Message
import ai.opencode.mobile.core.network.HttpOpenCodeApi
import ai.opencode.mobile.core.network.OpenCodeApi
import ai.opencode.mobile.core.network.ServerConfig
import ai.opencode.mobile.core.state.AppState
import ai.opencode.mobile.core.state.AppStateStore
import ai.opencode.mobile.core.state.SseSideEffect
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class AppViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val UNASSIGNED_PROJECT_KEY = "__unassigned__"
    }

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
        val remotePort: String = "4096",
        val localPort: String = "14096"
    )

    data class SpeechForm(
        val provider: SpeechProvider = SpeechProvider.AIBUILDERS,
        val baseUrl: String = "https://space.ai-builders.com/backend",
        val token: String = "",
        val doubaoResourceID: String = "volc.seedasr.auc",
        val customPrompt: String = "Prefer snake_case filenames and keep code terms unchanged.",
        val terminology: String = ""
    )

    data class ContextUsageSnapshot(
        val sessionID: String,
        val sessionTitle: String,
        val providerID: String,
        val modelID: String,
        val contextLimit: Int,
        val totalTokens: Int,
        val inputTokens: Int,
        val outputTokens: Int,
        val reasoningTokens: Int,
        val cacheReadTokens: Int,
        val cacheWriteTokens: Int,
        val totalSessionCost: Double?
    ) {
        val usageRatio: Double = if (contextLimit <= 0) 0.0 else (totalTokens.toDouble() / contextLimit.toDouble()).coerceIn(0.0, 1.0)
    }

    private object Keys {
        const val serverBaseUrl = "server_base_url"
        const val serverUsername = "server_username"
        const val selectedProject = "selected_project_worktree"
        const val speechBaseUrl = "speech_base_url"
        const val speechProvider = "speech_provider"
        const val speechDoubaoResourceID = "speech_doubao_resource_id"
        const val speechPrompt = "speech_prompt"
        const val speechTerminology = "speech_terminology"
        const val sshHost = "ssh_host"
        const val sshPort = "ssh_port"
        const val sshUsername = "ssh_username"
        const val sshUseKeyAuth = "ssh_use_key_auth"
        const val sshRemotePort = "ssh_remote_port"
        const val sshLocalPort = "ssh_local_port"

        const val secretServerPassword = "secret_server_password"
        const val secretSpeechToken = "secret_speech_token"
        const val secretSshPassword = "secret_ssh_password"
        const val secretSshPrivateKey = "secret_ssh_private_key"
        const val secretSshPassphrase = "secret_ssh_passphrase"
    }

    private val modelPresets = listOf(
        ModelPreset("GLM-5", "zai-coding-plan", "glm-5"),
        ModelPreset("Opus 4.6", "anthropic", "claude-opus-4-6"),
        ModelPreset("Sonnet 4.6", "anthropic", "claude-sonnet-4-6"),
        ModelPreset("GPT-5.3 Codex", "openai", "gpt-5.3-codex"),
        ModelPreset("GPT-5.2", "openai", "gpt-5.2"),
        ModelPreset("Gemini 3.1 Pro", "google", "gemini-3.1-pro-preview"),
        ModelPreset("Gemini 3 Flash", "google", "gemini-3-flash-preview")
    )

    private val localStore = LocalSettingsStore(getApplication())
    private val secretStore = SecureSecretStore(getApplication())
    private val speechClient = AIBuildersAudioClient()
    private val audioRecorder = AndroidAudioRecorder()

    private var currentConfig = ServerConfig(baseUrl = "http://127.0.0.1:4096")
    private var api = HttpOpenCodeApi(currentConfig)
    private val store = AppStateStore(currentConfig)
    private val sshManager = AndroidSshTunnelManager()
    private var sseJob: Job? = null
    private val draftBySession = mutableMapOf<String, String>()
    private val modelBySession = mutableMapOf<String, Int>()
    private val agentBySession = mutableMapOf<String, String>()
    private val messageLimitBySession = mutableMapOf<String, Int>()
    private val hasMoreHistoryBySession = mutableMapOf<String, Boolean>()
    private val providerContextLimitByKey = mutableMapOf<String, Int>()

    private val defaultMessageLimit = 6
    private val feedbackTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    val state: StateFlow<AppState> = store.state

    private val _settingsForm = MutableStateFlow(SettingsForm(baseUrl = currentConfig.baseUrl))
    val settingsForm: StateFlow<SettingsForm> = _settingsForm.asStateFlow()

    private val _speechForm = MutableStateFlow(SpeechForm())
    val speechForm: StateFlow<SpeechForm> = _speechForm.asStateFlow()

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

    private val _isApplyingSettings = MutableStateFlow(false)
    val isApplyingSettings: StateFlow<Boolean> = _isApplyingSettings.asStateFlow()

    private val _settingsFeedback = MutableStateFlow<String?>(null)
    val settingsFeedback: StateFlow<String?> = _settingsFeedback.asStateFlow()

    private val _sshForm = MutableStateFlow(SshForm())
    val sshForm: StateFlow<SshForm> = _sshForm.asStateFlow()

    private val _sshStatus = MutableStateFlow("Disconnected")
    val sshStatus: StateFlow<String> = _sshStatus.asStateFlow()

    private val _isSshConnecting = MutableStateFlow(false)
    val isSshConnecting: StateFlow<Boolean> = _isSshConnecting.asStateFlow()

    private val _sshError = MutableStateFlow<String?>(null)
    val sshError: StateFlow<String?> = _sshError.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _speechConnectionOk = MutableStateFlow(false)
    val speechConnectionOk: StateFlow<Boolean> = _speechConnectionOk.asStateFlow()

    private val _speechConnectionError = MutableStateFlow<String?>(null)
    val speechConnectionError: StateFlow<String?> = _speechConnectionError.asStateFlow()

    private val _isLoadingProviders = MutableStateFlow(false)
    val isLoadingProviders: StateFlow<Boolean> = _isLoadingProviders.asStateFlow()

    private val _providerConfigError = MutableStateFlow<String?>(null)
    val providerConfigError: StateFlow<String?> = _providerConfigError.asStateFlow()

    private val _contextUsage = MutableStateFlow<ContextUsageSnapshot?>(null)
    val contextUsage: StateFlow<ContextUsageSnapshot?> = _contextUsage.asStateFlow()

    private val _isLoadingOlderMessages = MutableStateFlow(false)
    val isLoadingOlderMessages: StateFlow<Boolean> = _isLoadingOlderMessages.asStateFlow()

    private val _hasMoreHistory = MutableStateFlow(false)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()

    private val _fileSearchQuery = MutableStateFlow("")
    val fileSearchQuery: StateFlow<String> = _fileSearchQuery.asStateFlow()

    private val _fileSearchResults = MutableStateFlow<List<String>>(emptyList())
    val fileSearchResults: StateFlow<List<String>> = _fileSearchResults.asStateFlow()

    private val _sessionDiffs = MutableStateFlow<List<FileDiff>>(emptyList())
    val sessionDiffs: StateFlow<List<FileDiff>> = _sessionDiffs.asStateFlow()

    private val _canCreateSession = MutableStateFlow(true)
    val canCreateSession: StateFlow<Boolean> = _canCreateSession.asStateFlow()

    private val _expandedProjectIDs = MutableStateFlow<Set<String>>(emptySet())
    val expandedProjectIDs: StateFlow<Set<String>> = _expandedProjectIDs.asStateFlow()

    private val _expandedSessionIDs = MutableStateFlow<Set<String>>(emptySet())
    val expandedSessionIDs: StateFlow<Set<String>> = _expandedSessionIDs.asStateFlow()

    val models: List<ModelPreset> = modelPresets

    init {
        hydratePersistedForms()
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
        recomputeContextUsage()
    }

    fun setSelectedAgent(name: String) {
        val normalized = if (name.isBlank()) "build" else name
        _selectedAgentName.value = normalized
        state.value.currentSessionID?.let { sid -> agentBySession[sid] = normalized }
    }

    fun setSshHost(value: String) {
        _sshForm.update { it.copy(host = value) }
        localStore.putString(Keys.sshHost, value)
    }

    fun setSshPort(value: String) {
        _sshForm.update { it.copy(port = value) }
        localStore.putString(Keys.sshPort, value)
    }

    fun setSshUsername(value: String) {
        _sshForm.update { it.copy(username = value) }
        localStore.putString(Keys.sshUsername, value)
    }

    fun setSshPassword(value: String) {
        _sshForm.update { it.copy(password = value) }
        secretStore.put(Keys.secretSshPassword, value)
    }

    fun setSshUseKeyAuth(value: Boolean) {
        _sshForm.update { it.copy(useKeyAuth = value) }
        localStore.putString(Keys.sshUseKeyAuth, value.toString())
    }

    fun setSshPrivateKeyPem(value: String) {
        val normalized = value.replace("\r\n", "\n")
        _sshForm.update { it.copy(privateKeyPem = normalized) }
        secretStore.put(Keys.secretSshPrivateKey, normalized)
    }

    fun setSshPrivateKeyPassphrase(value: String) {
        _sshForm.update { it.copy(privateKeyPassphrase = value) }
        secretStore.put(Keys.secretSshPassphrase, value)
    }

    fun setSshRemotePort(value: String) {
        _sshForm.update { it.copy(remotePort = value) }
        localStore.putString(Keys.sshRemotePort, value)
    }

    fun setSshLocalPort(value: String) {
        _sshForm.update { it.copy(localPort = value) }
        localStore.putString(Keys.sshLocalPort, value)
    }

    fun setSpeechBaseUrl(value: String) {
        _speechForm.update { it.copy(baseUrl = value) }
        localStore.putString(Keys.speechBaseUrl, value)
        _speechConnectionOk.value = false
        _speechConnectionError.value = null
    }

    fun setSpeechProvider(provider: SpeechProvider) {
        _speechForm.update { current ->
            val nextBase = when (provider) {
                SpeechProvider.AIBUILDERS -> {
                    if (current.provider == provider) current.baseUrl else "https://space.ai-builders.com/backend"
                }
                SpeechProvider.DOUBAO -> {
                    if (current.provider == provider) current.baseUrl else current.baseUrl
                }
            }
            current.copy(provider = provider, baseUrl = nextBase)
        }
        localStore.putString(Keys.speechProvider, provider.wireValue)
        _speechConnectionOk.value = false
        _speechConnectionError.value = null
    }

    fun setSpeechToken(value: String) {
        _speechForm.update { it.copy(token = value) }
        secretStore.put(Keys.secretSpeechToken, value)
        _speechConnectionOk.value = false
        _speechConnectionError.value = null
    }

    fun setSpeechDoubaoResourceID(value: String) {
        _speechForm.update { it.copy(doubaoResourceID = value) }
        localStore.putString(Keys.speechDoubaoResourceID, value)
        _speechConnectionOk.value = false
        _speechConnectionError.value = null
    }

    fun setSpeechCustomPrompt(value: String) {
        _speechForm.update { it.copy(customPrompt = value) }
        localStore.putString(Keys.speechPrompt, value)
    }

    fun setSpeechTerminology(value: String) {
        _speechForm.update { it.copy(terminology = value) }
        localStore.putString(Keys.speechTerminology, value)
    }

    fun applySettingsAndReconnect() {
        if (_isApplyingSettings.value) return
        _isApplyingSettings.value = true
        _settingsFeedback.value = "[${nowLabel()}] Applying settings..."
        _lastError.value = null
        val normalizedBase = normalizeBaseUrl(_settingsForm.value.baseUrl)
        val username = _settingsForm.value.username
        val password = _settingsForm.value.password
        localStore.putString(Keys.serverBaseUrl, normalizedBase)
        localStore.putString(Keys.serverUsername, username)
        secretStore.put(Keys.secretServerPassword, password)
        reconfigureApi(
            baseUrl = normalizedBase,
            username = username.ifBlank { null },
            password = password.ifBlank { null }
        )
        draftBySession.clear()
        modelBySession.clear()
        agentBySession.clear()
        messageLimitBySession.clear()
        hasMoreHistoryBySession.clear()
        _chatInput.value = ""
        _selectedModelIndex.value = 0
        _selectedAgentName.value = "build"
        _sessionDiffs.value = emptyList()
        _hasMoreHistory.value = false
        _contextUsage.value = null
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _lastError.value = null
            try {
                val health = api.health()
                store.setConnection(connected = health.healthy, version = health.version, error = null)
                if (_isApplyingSettings.value) {
                    _settingsFeedback.value = if (health.healthy) {
                        "[${nowLabel()}] Apply succeeded: connected"
                    } else {
                        "[${nowLabel()}] Apply completed: server reported unhealthy"
                    }
                }

                loadProvidersConfig()
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
                recomputeCanCreateSession()
            } catch (e: Exception) {
                val reason = e.message?.takeIf { it.isNotBlank() } ?: "Connection failed"
                store.setConnection(connected = false, error = reason)
                _lastError.value = reason
                if (_isApplyingSettings.value) {
                    _settingsFeedback.value = "[${nowLabel()}] Apply failed: $reason"
                }
            } finally {
                _isRefreshing.value = false
                if (_isApplyingSettings.value) {
                    _isApplyingSettings.value = false
                }
            }
        }
    }

    fun connectSshTunnel() {
        if (_isSshConnecting.value) return
        val form = _sshForm.value
        val sshPort = form.port.toIntOrNull()
        val remotePort = form.remotePort.toIntOrNull()
        val localPort = form.localPort.toIntOrNull()
        val normalizedPrivateKey = form.privateKeyPem.replace("\r\n", "\n").trim()

        if (sshPort == null || sshPort !in 1..65535) {
            val msg = "SSH port must be between 1 and 65535"
            _sshStatus.value = "Error"
            _sshError.value = msg
            _lastError.value = msg
            return
        }
        if (remotePort == null || remotePort !in 1..65535) {
            val msg = "Remote port must be between 1 and 65535"
            _sshStatus.value = "Error"
            _sshError.value = msg
            _lastError.value = msg
            return
        }
        if (localPort == null || localPort !in 1..65535) {
            val msg = "Local port must be between 1 and 65535"
            _sshStatus.value = "Error"
            _sshError.value = msg
            _lastError.value = msg
            return
        }

        val config = SshTunnelConfig(
            host = form.host.trim(),
            port = sshPort,
            username = form.username.trim(),
            password = form.password,
            privateKeyPem = normalizedPrivateKey.takeIf { form.useKeyAuth && it.isNotBlank() },
            privateKeyPassphrase = form.privateKeyPassphrase.takeIf { form.useKeyAuth && it.isNotBlank() },
            remotePort = remotePort,
            localPort = localPort
        )
        if (config.host.isBlank() || config.username.isBlank()) {
            val msg = "SSH host and username are required"
            _sshStatus.value = "Error"
            _sshError.value = msg
            _lastError.value = msg
            return
        }
        if (!form.useKeyAuth && config.password.isBlank()) {
            val msg = "SSH password is required for password auth"
            _sshStatus.value = "Error"
            _sshError.value = msg
            _lastError.value = msg
            return
        }
        if (form.useKeyAuth && config.privateKeyPem.isNullOrBlank()) {
            val msg = "SSH private key is required for key auth"
            _sshStatus.value = "Error"
            _sshError.value = msg
            _lastError.value = msg
            return
        }
        if (form.useKeyAuth &&
            (!normalizedPrivateKey.contains("BEGIN") || !normalizedPrivateKey.contains("END"))
        ) {
            val msg = "Invalid PEM format: include full BEGIN/END key block"
            _sshStatus.value = "Error"
            _sshError.value = msg
            _lastError.value = msg
            return
        }

        viewModelScope.launch {
            _isSshConnecting.value = true
            _lastError.value = null
            _sshError.value = null
            _sshStatus.value = "Connecting..."
            runCatching {
                withTimeout(40_000) {
                    sshManager.connect(config).getOrThrow()
                }
            }
                .onSuccess { local ->
                    val tunnelBaseUrl = "http://127.0.0.1:$local"
                    _sshStatus.value = "Connected (127.0.0.1:$local)"
                    _sshError.value = null
                    _settingsForm.update { it.copy(baseUrl = tunnelBaseUrl) }
                    localStore.putString(Keys.serverBaseUrl, tunnelBaseUrl)
                    val username = _settingsForm.value.username.ifBlank { null }
                    val password = _settingsForm.value.password.ifBlank { null }
                    reconfigureApi(
                        baseUrl = tunnelBaseUrl,
                        username = username,
                        password = password
                    )
                    refreshAll()
                }
                .onFailure { e ->
                    val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
                    _sshStatus.value = "Error"
                    _sshError.value = reason
                    _lastError.value = "SSH connect failed: $reason"
                }
            _isSshConnecting.value = false
        }
    }

    fun disconnectSshTunnel() {
        viewModelScope.launch {
            sshManager.disconnect()
            _sshStatus.value = "Disconnected"
            _isSshConnecting.value = false
            _sshError.value = null
        }
    }

    fun testSpeechConnection() {
        val form = _speechForm.value
        if (form.token.isBlank()) {
            _speechConnectionOk.value = false
            _speechConnectionError.value = "${form.provider.displayName} token is required"
            return
        }
        if (form.provider == SpeechProvider.DOUBAO && form.doubaoResourceID.isBlank()) {
            _speechConnectionOk.value = false
            _speechConnectionError.value = "Doubao Resource ID is required"
            return
        }
        viewModelScope.launch {
            _speechConnectionError.value = null
            runCatching {
                speechClient.testConnection(
                    provider = form.provider,
                    baseUrl = form.baseUrl,
                    token = form.token,
                    doubaoResourceID = form.doubaoResourceID.takeIf { it.isNotBlank() }
                )
            }.onSuccess {
                _speechConnectionOk.value = true
            }.onFailure {
                _speechConnectionOk.value = false
                _speechConnectionError.value = it.message
            }
        }
    }

    fun startRecording() {
        viewModelScope.launch {
            audioRecorder.start(getApplication())
                .onSuccess {
                    _isRecording.value = true
                }
                .onFailure {
                    _lastError.value = it.message
                    _isRecording.value = false
                }
        }
    }

    fun stopRecordingAndTranscribe() {
        viewModelScope.launch {
            val file: File? = audioRecorder.stop()
            _isRecording.value = false
            if (file == null || !file.exists()) {
                _lastError.value = "Recording failed: no audio file generated"
                return@launch
            }
            val form = _speechForm.value
            if (form.token.isBlank()) {
                _lastError.value = "${form.provider.displayName} token is required"
                return@launch
            }
            if (form.provider == SpeechProvider.DOUBAO && form.doubaoResourceID.isBlank()) {
                _lastError.value = "Doubao Resource ID is required"
                return@launch
            }
            _isTranscribing.value = true
            runCatching {
                speechClient.transcribe(
                    provider = form.provider,
                    baseUrl = form.baseUrl,
                    token = form.token,
                    audioFile = file,
                    doubaoResourceID = form.doubaoResourceID.takeIf { it.isNotBlank() },
                    prompt = form.customPrompt.takeIf { it.isNotBlank() },
                    terms = form.terminology.takeIf { it.isNotBlank() }
                )
            }.onSuccess { response ->
                val transcript = response.text.trim()
                if (transcript.isNotEmpty()) {
                    val next = if (_chatInput.value.isBlank()) transcript else "${_chatInput.value} $transcript"
                    _chatInput.value = next
                    state.value.currentSessionID?.let { sid -> draftBySession[sid] = next }
                }
            }.onFailure {
                _lastError.value = it.message
            }
            _isTranscribing.value = false
            runCatching { file.delete() }
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            runCatching {
                val selected = state.value.selectedProjectWorktree
                api.sessions(directory = selected, limit = 100)
            }.onSuccess { sessions ->
                val sorted = sessions.sortedByDescending { it.time.updated }
                store.setSessions(sorted)
                val sessionIDs = sorted.map { it.id }.toSet()
                val projectKeys = sorted
                    .map { normalizeProjectKey(it.projectID) }
                    .toSet()
                    .ifEmpty { setOf(UNASSIGNED_PROJECT_KEY) }
                _expandedSessionIDs.update { it.intersect(sessionIDs) }
                _expandedProjectIDs.update { existing ->
                    if (existing.isEmpty()) projectKeys else existing.intersect(projectKeys).ifEmpty { projectKeys }
                }
                restoreSessionScopedSelections(state.value.currentSessionID)
                recomputeCanCreateSession()
            }.onFailure {
                _lastError.value = it.message
            }
        }
    }

    fun selectProject(worktree: String?) {
        store.setSelectedProject(worktree)
        localStore.putString(Keys.selectedProject, worktree.orEmpty())
        recomputeCanCreateSession()
        loadSessions()
    }

    fun selectSession(sessionID: String) {
        store.setCurrentSession(sessionID)
        messageLimitBySession.putIfAbsent(sessionID, defaultMessageLimit)
        restoreSessionScopedSelections(sessionID)
        loadCurrentSessionData()
    }

    fun createSession() {
        if (state.value.selectedProjectWorktree != null) {
            _lastError.value = "Create is allowed only under Server default project"
            return
        }
        viewModelScope.launch {
            val title = _sessionTitleInput.value.trim().ifBlank { null }
            runCatching { api.createSession(title) }
                .onSuccess { created ->
                    _sessionTitleInput.value = ""
                    loadSessions()
                    store.setCurrentSession(created.id)
                    messageLimitBySession[created.id] = defaultMessageLimit
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
                    messageLimitBySession.remove(sid)
                    hasMoreHistoryBySession.remove(sid)
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

    fun loadOlderMessages() {
        val sid = state.value.currentSessionID ?: return
        if (_isLoadingOlderMessages.value) return
        if (hasMoreHistoryBySession[sid] == false) return
        _isLoadingOlderMessages.value = true
        messageLimitBySession[sid] = currentMessageLimit(sid) + defaultMessageLimit
        loadMessages(sid) {
            _isLoadingOlderMessages.value = false
        }
    }

    fun summarizeCurrentSession() {
        val sid = state.value.currentSessionID ?: return
        viewModelScope.launch {
            runCatching { api.summarize(sid) }
                .onSuccess {
                    loadMessages(sid)
                    loadSessionStatuses()
                }
                .onFailure { _lastError.value = it.message }
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

    fun setFileSearchQuery(value: String) {
        _fileSearchQuery.value = value
    }

    fun searchFiles() {
        val query = _fileSearchQuery.value.trim()
        if (query.isEmpty()) {
            _fileSearchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            runCatching { api.findFile(query = query, limit = 50) }
                .onSuccess { _fileSearchResults.value = it }
                .onFailure { _lastError.value = it.message }
        }
    }

    fun toggleProjectExpanded(projectKey: String) {
        _expandedProjectIDs.update { old ->
            if (projectKey in old) old - projectKey else old + projectKey
        }
    }

    fun toggleSessionExpanded(sessionID: String) {
        _expandedSessionIDs.update { old ->
            if (sessionID in old) old - sessionID else old + sessionID
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    private fun loadCurrentSessionData() {
        val sid = state.value.currentSessionID ?: run {
            _sessionDiffs.value = emptyList()
            _hasMoreHistory.value = false
            _contextUsage.value = null
            return
        }
        messageLimitBySession.putIfAbsent(sid, defaultMessageLimit)
        loadMessages(sid)
        loadSessionTodos(sid)
        loadSessionDiff(sid)
    }

    private fun loadMessages(sessionID: String, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            val limit = currentMessageLimit(sessionID)
            runCatching { api.messages(sessionID = sessionID, limit = limit) }
                .onSuccess { messages ->
                    store.setMessages(sessionID, messages)
                    hasMoreHistoryBySession[sessionID] = messages.size >= limit
                    if (state.value.currentSessionID == sessionID) {
                        _hasMoreHistory.value = hasMoreHistoryBySession[sessionID] ?: false
                        recomputeContextUsage()
                    }
                }
                .onFailure { _lastError.value = it.message }
            onDone?.invoke()
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

    private fun loadSessionDiff(sessionID: String) {
        viewModelScope.launch {
            runCatching { api.sessionDiff(sessionID) }
                .onSuccess { diffs ->
                    if (state.value.currentSessionID == sessionID) {
                        _sessionDiffs.value = diffs
                    }
                }
                .onFailure {
                    if (state.value.currentSessionID == sessionID) {
                        _sessionDiffs.value = emptyList()
                    }
                }
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

    fun loadProvidersConfig() {
        viewModelScope.launch {
            _isLoadingProviders.value = true
            _providerConfigError.value = null
            runCatching { api.providers() }
                .onSuccess { response ->
                    providerContextLimitByKey.clear()
                    response.providers.forEach { provider ->
                        provider.models.forEach { (modelID, model) ->
                            val limit = model.limit?.context
                            if (limit != null) {
                                providerContextLimitByKey["${provider.id}/$modelID"] = limit
                            }
                        }
                    }
                    recomputeContextUsage()
                }
                .onFailure { _providerConfigError.value = it.message }
            _isLoadingProviders.value = false
        }
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "http://127.0.0.1:4096"
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    private fun normalizeProjectKey(projectID: String): String {
        return if (projectID.isBlank()) UNASSIGNED_PROJECT_KEY else projectID
    }

    private fun currentMessageLimit(sessionID: String): Int {
        return max(defaultMessageLimit, messageLimitBySession[sessionID] ?: defaultMessageLimit)
    }

    private fun recomputeCanCreateSession() {
        _canCreateSession.value = state.value.selectedProjectWorktree == null
    }

    private fun recomputeContextUsage() {
        val sid = state.value.currentSessionID ?: run {
            _contextUsage.value = null
            return
        }
        val session = state.value.sessions.firstOrNull { it.id == sid } ?: run {
            _contextUsage.value = null
            return
        }
        val messages = state.value.messages
        val lastAssistant = messages.asReversed().firstOrNull { it.info.role == "assistant" && it.info.tokens != null } ?: run {
            _contextUsage.value = null
            return
        }

        val model = lastAssistant.info.model ?: run {
            val provider = lastAssistant.info.providerID
            val modelID = lastAssistant.info.modelID
            if (!provider.isNullOrBlank() && !modelID.isNullOrBlank()) {
                Message.ModelInfo(providerID = provider, modelID = modelID)
            } else {
                null
            }
        } ?: run {
            _contextUsage.value = null
            return
        }

        val contextLimit = providerContextLimitByKey["${model.providerID}/${model.modelID}"] ?: run {
            _contextUsage.value = null
            return
        }

        val tokens = lastAssistant.info.tokens ?: run {
            _contextUsage.value = null
            return
        }
        val input = tokens.input ?: 0
        val output = tokens.output ?: 0
        val reasoning = tokens.reasoning ?: 0
        val cacheRead = tokens.cache?.read ?: 0
        val cacheWrite = tokens.cache?.write ?: 0
        val total = tokens.total ?: (input + output + reasoning + cacheRead + cacheWrite)
        val totalCost = messages.mapNotNull { it.info.cost }.sum().takeIf { it > 0.0 }

        _contextUsage.value = ContextUsageSnapshot(
            sessionID = sid,
            sessionTitle = session.title,
            providerID = model.providerID,
            modelID = model.modelID,
            contextLimit = contextLimit,
            totalTokens = total,
            inputTokens = input,
            outputTokens = output,
            reasoningTokens = reasoning,
            cacheReadTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
            totalSessionCost = totalCost
        )
    }

    private fun reconfigureApi(baseUrl: String, username: String?, password: String?) {
        currentConfig = ServerConfig(baseUrl = baseUrl, username = username, password = password)
        api = HttpOpenCodeApi(currentConfig)
        store.setServerConfig(currentConfig)
        val selectedProject = localStore.getString(Keys.selectedProject).ifBlank { null }
        store.setSelectedProject(selectedProject)
    }

    private fun hydratePersistedForms() {
        val baseUrl = normalizeBaseUrl(localStore.getString(Keys.serverBaseUrl, "http://127.0.0.1:4096"))
        val username = localStore.getString(Keys.serverUsername)
        val password = secretStore.get(Keys.secretServerPassword)
        _settingsForm.value = SettingsForm(baseUrl = baseUrl, username = username, password = password)

        val speechProvider = SpeechProvider.fromWireValue(localStore.getString(Keys.speechProvider))
        val speechBase = localStore.getString(Keys.speechBaseUrl, "https://space.ai-builders.com/backend")
        val speechResourceID = localStore.getString(Keys.speechDoubaoResourceID, SpeechForm().doubaoResourceID)
        val speechPrompt = localStore.getString(Keys.speechPrompt, SpeechForm().customPrompt)
        val speechTerms = localStore.getString(Keys.speechTerminology)
        val speechToken = secretStore.get(Keys.secretSpeechToken)
        _speechForm.value = SpeechForm(
            provider = speechProvider,
            baseUrl = speechBase,
            token = speechToken,
            doubaoResourceID = speechResourceID,
            customPrompt = speechPrompt,
            terminology = speechTerms
        )

        _sshForm.value = SshForm(
            host = localStore.getString(Keys.sshHost),
            port = localStore.getString(Keys.sshPort, "22"),
            username = localStore.getString(Keys.sshUsername),
            password = secretStore.get(Keys.secretSshPassword),
            useKeyAuth = localStore.getString(Keys.sshUseKeyAuth).toBooleanStrictOrNull() ?: false,
            privateKeyPem = secretStore.get(Keys.secretSshPrivateKey),
            privateKeyPassphrase = secretStore.get(Keys.secretSshPassphrase),
            remotePort = localStore.getString(Keys.sshRemotePort, "4096"),
            localPort = localStore.getString(Keys.sshLocalPort, "14096")
        )

        reconfigureApi(
            baseUrl = baseUrl,
            username = username.ifBlank { null },
            password = password.ifBlank { null }
        )
    }

    private fun restoreSessionScopedSelections(sessionID: String?) {
        if (sessionID == null) {
            _chatInput.value = ""
            _selectedModelIndex.value = 0
            _selectedAgentName.value = state.value.agents.firstOrNull()?.name ?: "build"
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
            audioRecorder.stop()
            sshManager.disconnect()
        }
    }

    private fun nowLabel(): String = synchronized(feedbackTimeFormat) {
        feedbackTimeFormat.format(Date())
    }
}
