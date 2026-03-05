package ai.opencode.mobile.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.opencode.mobile.android.speech.SpeechProvider
import ai.opencode.mobile.android.ui.AppViewModel
import ai.opencode.mobile.core.model.Part
import ai.opencode.mobile.core.model.Project
import ai.opencode.mobile.core.model.Session
import ai.opencode.mobile.core.model.SessionStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import ai.opencode.mobile.core.state.AppState

private data class ProjectSessionGroupUi(
    val key: String,
    val title: String,
    val roots: List<SessionNodeUi>
)

private data class SessionNodeUi(
    val session: Session,
    val children: List<SessionNodeUi>
)

@Composable
fun OpenCodeAndroidApp(vm: AppViewModel = viewModel()) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val titles = listOf("Chat", "Sessions", "Files", "Settings")
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val error by vm.lastError.collectAsState()
    val context = LocalContext.current
    val isRecording by vm.isRecording.collectAsState()
    val isTranscribing by vm.isTranscribing.collectAsState()

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.startRecording()
        } else {
            vm.clearError()
        }
    }

    val onMicClick: () -> Unit = {
        if (isRecording) {
            vm.stopRecordingAndTranscribe()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                vm.startRecording()
            } else {
                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedIndex == 0,
                    onClick = { selectedIndex = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat") },
                    label = { Text("Chat") }
                )
                NavigationBarItem(
                    selected = selectedIndex == 1,
                    onClick = { selectedIndex = 1 },
                    icon = { Icon(Icons.Default.AccountTree, contentDescription = "Sessions") },
                    label = { Text("Sessions") }
                )
                NavigationBarItem(
                    selected = selectedIndex == 2,
                    onClick = { selectedIndex = 2 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Files") },
                    label = { Text("Files") }
                )
                NavigationBarItem(
                    selected = selectedIndex == 3,
                    onClick = { selectedIndex = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(titles[selectedIndex], style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.weight(1f))
                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (state.isConnected) "Connected" else "Disconnected",
                    color = if (state.isConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )
            }

            error?.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    OutlinedButton(onClick = { vm.clearError() }) {
                        Text("Close")
                    }
                }
            }

            when (selectedIndex) {
                0 -> ChatScreen(vm = vm, state = state, onMicClick = onMicClick)
                1 -> SessionsScreen(vm = vm, state = state)
                2 -> FilesScreen(vm = vm, state = state)
                else -> SettingsScreen(vm, state)
            }
        }
    }
}

@Composable
private fun ChatScreen(vm: AppViewModel, state: AppState, onMicClick: () -> Unit) {
    val chatInput by vm.chatInput.collectAsState()
    val selectedModelIndex by vm.selectedModelIndex.collectAsState()
    val selectedAgentName by vm.selectedAgentName.collectAsState()
    val contextUsage by vm.contextUsage.collectAsState()
    val hasMoreHistory by vm.hasMoreHistory.collectAsState()
    val isLoadingOlderMessages by vm.isLoadingOlderMessages.collectAsState()
    val isRecording by vm.isRecording.collectAsState()
    val isTranscribing by vm.isTranscribing.collectAsState()
    val providerError by vm.providerConfigError.collectAsState()
    val models = vm.models
    val currentSession = remember(state.currentSessionID, state.sessions) {
        state.sessions.firstOrNull { it.id == state.currentSessionID }
    }
    val sessionPermissions = state.pendingPermissions.filter { it.sessionID == state.currentSessionID }
    val currentTodos = state.currentSessionID?.let { state.todosBySession[it] }.orEmpty()
    var controlsExpanded by remember(state.currentSessionID) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Current session", style = MaterialTheme.typography.titleSmall)
                        Text(
                            currentSession?.title?.ifBlank { currentSession.id.take(8) } ?: "No session selected",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    OutlinedButton(onClick = { controlsExpanded = !controlsExpanded }) {
                        Text(if (controlsExpanded) "Hide controls" else "Show controls")
                    }
                }
                if (currentSession == null) {
                    Text(
                        "Go to Sessions tab to choose or create one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!controlsExpanded) {
                    Text(
                        "Controls are hidden to maximize message area.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { vm.loadOlderMessages() },
                enabled = state.currentSessionID != null && (hasMoreHistory || isLoadingOlderMessages),
                modifier = Modifier.weight(1f)
            ) {
                if (isLoadingOlderMessages) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Load older")
                }
            }
            OutlinedButton(
                onClick = { vm.summarizeCurrentSession() },
                enabled = state.currentSessionID != null,
                modifier = Modifier.weight(1f)
            ) {
                Text("Compact")
            }
        }

        if (controlsExpanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { vm.loadProvidersConfig() }, modifier = Modifier.weight(1f)) {
                    Text("Refresh context")
                }
                OutlinedButton(
                    onClick = { vm.abortCurrentSession() },
                    enabled = state.currentSessionID != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Abort")
                }
            }

            Text("Model", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(models.size) { idx ->
                    val model = models[idx]
                    FilterChip(
                        selected = idx == selectedModelIndex,
                        onClick = { vm.setSelectedModel(idx) },
                        label = { Text(model.displayName) }
                    )
                }
            }

            Text("Agent", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val agents = if (state.agents.isEmpty()) listOf("build") else state.agents.map { it.name }
                items(agents) { name ->
                    FilterChip(
                        selected = name == selectedAgentName,
                        onClick = { vm.setSelectedAgent(name) },
                        label = { Text(name) }
                    )
                }
            }

            contextUsage?.let { usage ->
                val color = when {
                    usage.usageRatio >= 0.9 -> Color(0xFFC62828)
                    usage.usageRatio >= 0.7 -> Color(0xFFEF6C00)
                    else -> Color(0xFF2E7D32)
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Context usage", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(
                                progress = { usage.usageRatio.toFloat() },
                                modifier = Modifier.width(28.dp),
                                strokeWidth = 4.dp,
                                color = color,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Text(
                                "${usage.totalTokens}/${usage.contextLimit} (${(usage.usageRatio * 100).toInt()}%) • ${usage.providerID}/${usage.modelID}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            "input=${usage.inputTokens}, output=${usage.outputTokens}, reasoning=${usage.reasoningTokens}, cache(r/w)=${usage.cacheReadTokens}/${usage.cacheWriteTokens}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        usage.totalSessionCost?.let { c ->
                            Text("total cost: ${"%.4f".format(c)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (sessionPermissions.isNotEmpty()) {
                Text("Permissions", style = MaterialTheme.typography.titleSmall)
                sessionPermissions.forEach { perm ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Permission: ${perm.permission ?: perm.id}")
                            if (perm.patterns.isNotEmpty()) {
                                Text("Patterns: ${perm.patterns.joinToString()}", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.respondPermission(perm.sessionID, perm.id, allowAlways = false) }) {
                                    Text("Allow once")
                                }
                                OutlinedButton(onClick = { vm.respondPermission(perm.sessionID, perm.id, allowAlways = true) }) {
                                    Text("Allow always")
                                }
                                OutlinedButton(onClick = { vm.rejectPermission(perm.sessionID, perm.id) }) {
                                    Text("Reject")
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            if (currentTodos.isNotEmpty()) {
                Text("Todo", style = MaterialTheme.typography.titleSmall)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        currentTodos.forEach { todo ->
                            val mark = when {
                                todo.completed == true -> "[x]"
                                todo.status.equals("done", ignoreCase = true) -> "[x]"
                                else -> "[ ]"
                            }
                            Text("$mark ${todo.content ?: "(empty)"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider()
            }
        } else {
            if (sessionPermissions.isNotEmpty() || currentTodos.isNotEmpty()) {
                Text(
                    "There are pending permissions/todos. Tap 'Show controls' to manage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        providerError?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasMoreHistory || isLoadingOlderMessages) {
                item("load_more_hint") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isLoadingOlderMessages) {
                            CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Loading older messages...", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Pull / tap Load older to fetch more history", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(state.messages, key = { it.info.id }) { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "${msg.info.role.uppercase()} • ${msg.info.id.take(8)}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        msg.parts.forEach { part ->
                            PartText(part = part, streamingTexts = state.streamingPartTexts)
                        }
                        if (msg.parts.isEmpty()) {
                            val assistantError = extractAssistantErrorText(msg.info.error)
                            if (!assistantError.isNullOrBlank()) {
                                Text(
                                    "Assistant error: $assistantError",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                Text("(no parts)")
                            }
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = chatInput,
            onValueChange = vm::setChatInput,
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.currentSessionID != null,
            keyboardActions = KeyboardActions(onSend = { vm.sendMessage() }),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            minLines = 2,
            maxLines = 5
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { vm.sendMessage() },
                enabled = state.currentSessionID != null && state.isConnected,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                Spacer(modifier = Modifier.width(6.dp))
                Text("Send")
            }
            OutlinedButton(
                onClick = onMicClick,
                enabled = state.currentSessionID != null && !isTranscribing,
                modifier = Modifier.weight(1f)
            ) {
                when {
                    isTranscribing -> {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Transcribing")
                    }
                    isRecording -> {
                        Icon(Icons.Default.Stop, contentDescription = "Stop recording", tint = Color(0xFFC62828))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Stop")
                    }
                    else -> {
                        Icon(Icons.Default.Mic, contentDescription = "Record voice")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Voice")
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionsScreen(vm: AppViewModel, state: AppState) {
    val sessionTitleInput by vm.sessionTitleInput.collectAsState()
    val canCreateSession by vm.canCreateSession.collectAsState()
    val hasMoreHistory by vm.hasMoreHistory.collectAsState()
    val isLoadingOlderMessages by vm.isLoadingOlderMessages.collectAsState()
    val expandedProjectIDs by vm.expandedProjectIDs.collectAsState()
    val expandedSessionIDs by vm.expandedSessionIDs.collectAsState()

    val projectGroups = remember(state.sessions, state.projects) {
        buildProjectSessionGroups(state.sessions, state.projects)
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Project filter", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    item("default") {
                        AssistChip(
                            onClick = { vm.selectProject(null) },
                            label = {
                                Text(
                                    if (state.selectedProjectWorktree == null) {
                                        "Server default (selected)"
                                    } else {
                                        "Server default"
                                    }
                                )
                            }
                        )
                    }
                    items(state.projects, key = { it.id }) { project ->
                        AssistChip(
                            onClick = { vm.selectProject(project.worktree) },
                            label = {
                                Text(
                                    if (state.selectedProjectWorktree == project.worktree) {
                                        "${project.worktree} (selected)"
                                    } else {
                                        project.worktree
                                    }
                                )
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = sessionTitleInput,
                    onValueChange = vm::setSessionTitleInput,
                    label = { Text("Session title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { vm.createSession() },
                        enabled = state.isConnected && canCreateSession,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Create")
                    }
                    OutlinedButton(
                        onClick = { vm.renameCurrentSession() },
                        enabled = state.currentSessionID != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Rename")
                    }
                    OutlinedButton(
                        onClick = { vm.deleteCurrentSession() },
                        enabled = state.currentSessionID != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Delete")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { vm.summarizeCurrentSession() },
                        enabled = state.currentSessionID != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Compact")
                    }
                    OutlinedButton(
                        onClick = { vm.loadOlderMessages() },
                        enabled = state.currentSessionID != null && (hasMoreHistory || isLoadingOlderMessages),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isLoadingOlderMessages) {
                            CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Load older")
                        }
                    }
                    OutlinedButton(onClick = { vm.refreshAll() }, modifier = Modifier.weight(1f)) {
                        Text("Refresh")
                    }
                }

                if (!canCreateSession) {
                    Text(
                        "Create session is disabled because project filter is active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (projectGroups.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No sessions available.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(projectGroups, key = { it.key }) { group ->
                    val isProjectExpanded = expandedProjectIDs.contains(group.key)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.toggleProjectExpanded(group.key) }
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    if (isProjectExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = if (isProjectExpanded) "Collapse" else "Expand"
                                )
                                Icon(
                                    if (isProjectExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                                    contentDescription = null
                                )
                                Text(group.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text("${group.roots.size}", style = MaterialTheme.typography.labelMedium)
                            }

                            if (isProjectExpanded) {
                                if (group.roots.isEmpty()) {
                                    Text(
                                        "No sessions in this project.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 10.dp)
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        group.roots.forEach { root ->
                                            SessionTreeNode(
                                                node = root,
                                                depth = 0,
                                                currentSessionID = state.currentSessionID,
                                                statuses = state.sessionStatuses,
                                                expandedSessionIDs = expandedSessionIDs,
                                                onToggle = vm::toggleSessionExpanded,
                                                onSelect = vm::selectSession
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionTreeNode(
    node: SessionNodeUi,
    depth: Int,
    currentSessionID: String?,
    statuses: Map<String, SessionStatus>,
    expandedSessionIDs: Set<String>,
    onToggle: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    val hasChildren = node.children.isNotEmpty()
    val isExpanded = expandedSessionIDs.contains(node.session.id)
    val isSelected = currentSessionID == node.session.id
    val status = statuses[node.session.id]?.type ?: "idle"
    val statusColor = when (status) {
        "busy", "retry" -> Color(0xFFEF6C00)
        "error", "failed" -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(node.session.id) }
                .padding(start = (depth * 14).dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (hasChildren) {
                IconButton(onClick = { onToggle(node.session.id) }, modifier = Modifier.width(24.dp)) {
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse session" else "Expand session"
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(24.dp))
            }
            Text(
                text = node.session.title.ifBlank { node.session.id.take(8) },
                style = if (isSelected) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor)
        }

        if (hasChildren && isExpanded) {
            node.children.forEach { child ->
                SessionTreeNode(
                    node = child,
                    depth = depth + 1,
                    currentSessionID = currentSessionID,
                    statuses = statuses,
                    expandedSessionIDs = expandedSessionIDs,
                    onToggle = onToggle,
                    onSelect = onSelect
                )
            }
        }
    }
}

@Composable
private fun PartText(part: Part, streamingTexts: Map<String, String>) {
    val key = "${part.messageID}:${part.id}"
    val stream = streamingTexts[key]
    when (part.type) {
        "text", "reasoning" -> {
            val text = stream ?: part.text.orEmpty()
            if (text.isNotBlank()) Text(text)
        }
        "tool" -> {
            Text("Tool: ${part.tool.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
            part.state?.let { Text("State: $it", style = MaterialTheme.typography.bodySmall) }
        }
        "patch" -> {
            Text("Patch part", style = MaterialTheme.typography.bodyMedium)
            part.files?.forEach { f ->
                Text("- ${f.path} (+${f.additions}/-${f.deletions})", style = MaterialTheme.typography.bodySmall)
            }
        }
        else -> {
            Text("Part(${part.type}) ${part.text.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FilesScreen(vm: AppViewModel, state: AppState) {
    var pathInput by remember(state.filePath) { mutableStateOf(state.filePath) }
    val searchQuery by vm.fileSearchQuery.collectAsState()
    val searchResults by vm.fileSearchResults.collectAsState()
    val diffs by vm.sessionDiffs.collectAsState()
    val selectedDiff = remember(state.selectedFilePath, diffs) {
        val selected = state.selectedFilePath ?: return@remember null
        diffs.firstOrNull { it.file == selected || selected.endsWith(it.file) || it.file.endsWith(selected) }
    }
    var showDiff by remember(state.selectedFilePath, selectedDiff) { mutableStateOf(selectedDiff != null) }
    var markdownPreview by remember(state.selectedFilePath) { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = { Text("Path") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = { vm.loadFileList(pathInput) }) {
                Text("Load")
            }
            OutlinedButton(onClick = { vm.loadFileRoot() }) {
                Text("Root")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = vm::setFileSearchQuery,
                label = { Text("Search file") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardActions = KeyboardActions(onSearch = { vm.searchFiles() }),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            OutlinedButton(onClick = { vm.searchFiles() }) {
                Text("Search")
            }
        }

        if (searchResults.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(searchResults) { path ->
                    AssistChip(
                        onClick = { vm.openFile(path) },
                        label = { Text(path) }
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.fileNodes, key = { it.path }) { node ->
                    val status = state.fileStatuses.firstOrNull { it.path == node.path }?.status
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (node.type == "directory") vm.loadFileList(node.path) else vm.openFile(node.path)
                            }
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (node.type == "directory") "[D] ${node.name}" else node.name,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (!status.isNullOrBlank()) {
                            Text(status, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp)
            ) {
                Text(state.selectedFilePath ?: "No file selected", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                val content = state.selectedFileContent
                when {
                    content == null -> Text("Select a file to preview.")
                    content.type == "text" -> {
                        val text = content.content.orEmpty()
                        val isMarkdown = (state.selectedFilePath ?: "").lowercase().endsWith(".md") ||
                            (state.selectedFilePath ?: "").lowercase().endsWith(".markdown")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selectedDiff != null) {
                                OutlinedButton(onClick = { showDiff = !showDiff }) {
                                    Text(if (showDiff) "Show content" else "Show diff")
                                }
                            }
                            if (isMarkdown) {
                                OutlinedButton(onClick = { markdownPreview = !markdownPreview }) {
                                    Text(if (markdownPreview) "Markdown source" else "Markdown preview")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (showDiff && selectedDiff != null) {
                            Text("--- before ---", color = Color(0xFFC62828), style = MaterialTheme.typography.labelSmall)
                            Text(selectedDiff.before)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("+++ after +++", color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelSmall)
                            Text(selectedDiff.after)
                        } else if (isMarkdown && markdownPreview) {
                            Text(text)
                        } else {
                            Text(text)
                        }
                    }
                    else -> Text("Binary file")
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: AppViewModel, state: AppState) {
    val settings by vm.settingsForm.collectAsState()
    val isApplyingSettings by vm.isApplyingSettings.collectAsState()
    val settingsFeedback by vm.settingsFeedback.collectAsState()
    val speech by vm.speechForm.collectAsState()
    val speechOk by vm.speechConnectionOk.collectAsState()
    val speechError by vm.speechConnectionError.collectAsState()
    val ssh by vm.sshForm.collectAsState()
    val sshStatus by vm.sshStatus.collectAsState()
    val sshError by vm.sshError.collectAsState()
    val isSshConnecting by vm.isSshConnecting.collectAsState()
    val canCreateSession by vm.canCreateSession.collectAsState()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Logo & Build: OpenCode Android",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TAG}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = settings.baseUrl,
            onValueChange = vm::setSettingsBaseUrl,
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = settings.username,
            onValueChange = vm::setSettingsUsername,
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = settings.password,
            onValueChange = vm::setSettingsPassword,
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.applySettingsAndReconnect() },
                enabled = !isApplyingSettings
            ) {
                Icon(Icons.Default.Link, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                if (isApplyingSettings) {
                    CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Applying...")
                } else {
                    Text("Apply & Connect")
                }
            }
            OutlinedButton(onClick = { vm.refreshAll() }) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Refresh")
            }
        }
        settingsFeedback?.let { feedback ->
            val isApplying = feedback.contains("Applying", ignoreCase = true)
            val isError = feedback.contains("failed", ignoreCase = true)
            val icon = when {
                isApplying -> Icons.Default.Info
                isError -> Icons.Default.ErrorOutline
                else -> Icons.Default.CheckCircle
            }
            val color = when {
                isApplying -> MaterialTheme.colorScheme.onSurfaceVariant
                isError -> MaterialTheme.colorScheme.error
                else -> Color(0xFF2E7D32)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(icon, contentDescription = null, tint = color)
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
            }
        }

        Text("Speech recognition", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = speech.provider == SpeechProvider.AIBUILDERS,
                onClick = { vm.setSpeechProvider(SpeechProvider.AIBUILDERS) },
                label = { Text("AI Builder") }
            )
            FilterChip(
                selected = speech.provider == SpeechProvider.DOUBAO,
                onClick = { vm.setSpeechProvider(SpeechProvider.DOUBAO) },
                label = { Text("Doubao") }
            )
        }
        OutlinedTextField(
            value = speech.baseUrl,
            onValueChange = vm::setSpeechBaseUrl,
            label = { Text("Speech Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = speech.token,
            onValueChange = vm::setSpeechToken,
            label = { Text(if (speech.provider == SpeechProvider.DOUBAO) "Doubao Token / AppKey:AccessKey" else "Speech Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        if (speech.provider == SpeechProvider.DOUBAO) {
            Text(
                "For openspeech.bytedance.com, token can be 'appKey:accessKey'. A single key will be used for both.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = speech.doubaoResourceID,
                onValueChange = vm::setSpeechDoubaoResourceID,
                label = { Text("Doubao Resource ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        OutlinedTextField(
            value = speech.customPrompt,
            onValueChange = vm::setSpeechCustomPrompt,
            label = { Text("Custom Prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        OutlinedTextField(
            value = speech.terminology,
            onValueChange = vm::setSpeechTerminology,
            label = { Text("Terminology (optional)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.testSpeechConnection() }) {
                Text("Test speech")
            }
            Text(
                text = when {
                    speechOk -> "Connected"
                    !speechError.isNullOrBlank() -> "Failed"
                    else -> "Not tested"
                },
                color = when {
                    speechOk -> Color(0xFF2E7D32)
                    !speechError.isNullOrBlank() -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (!speechError.isNullOrBlank()) {
            Text(speechError ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Text("SSH Tunnel (password/key auth)", style = MaterialTheme.typography.titleMedium)
        Text("Status: $sshStatus", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = ssh.host,
            onValueChange = vm::setSshHost,
            label = { Text("SSH Host") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = ssh.port,
                onValueChange = vm::setSshPort,
                label = { Text("SSH Port") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = ssh.username,
                onValueChange = vm::setSshUsername,
                label = { Text("SSH User") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        OutlinedTextField(
            value = ssh.password,
            onValueChange = vm::setSshPassword,
            label = { Text("SSH Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Use SSH key auth")
            Switch(checked = ssh.useKeyAuth, onCheckedChange = vm::setSshUseKeyAuth)
        }
        if (!ssh.useKeyAuth) {
            Text(
                "Enable key auth to show 'Private Key (PEM)'. Paste full key text (BEGIN/END).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (ssh.useKeyAuth) {
            OutlinedTextField(
                value = ssh.privateKeyPem,
                onValueChange = vm::setSshPrivateKeyPem,
                label = { Text("Private Key (PEM)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )
            OutlinedTextField(
                value = ssh.privateKeyPassphrase,
                onValueChange = vm::setSshPrivateKeyPassphrase,
                label = { Text("Key Passphrase (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = ssh.remotePort,
                onValueChange = vm::setSshRemotePort,
                label = { Text("Remote Port") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = ssh.localPort,
                onValueChange = vm::setSshLocalPort,
                label = { Text("Local Port") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.connectSshTunnel() },
                enabled = !isSshConnecting
            ) {
                Icon(Icons.Default.VpnKey, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                if (isSshConnecting) {
                    CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Connecting...")
                } else {
                    Text("Connect SSH")
                }
            }
            OutlinedButton(
                onClick = { vm.disconnectSshTunnel() },
                enabled = !isSshConnecting
            ) {
                Icon(Icons.Default.LinkOff, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Disconnect SSH")
            }
        }
        if (!sshError.isNullOrBlank()) {
            Text(
                sshError ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Text("Projects", style = MaterialTheme.typography.titleMedium)
        AssistChip(
            onClick = { vm.selectProject(null) },
            label = { Text(if (state.selectedProjectWorktree == null) "Server default (selected)" else "Server default") }
        )
        state.projects.forEach { project ->
            AssistChip(
                onClick = { vm.selectProject(project.worktree) },
                label = { Text(project.worktree) }
            )
        }
        if (!canCreateSession) {
            Text(
                "Create session is disabled because project filter is active. Switch back to Server default to create.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Server Version: ${state.serverVersion ?: "-"}")
        Text("Current Session: ${state.currentSessionID ?: "-"}")
        Text("Sessions: ${state.sessions.size}")
        Text("Agents: ${state.agents.size}")
    }
}

private fun buildProjectSessionGroups(
    sessions: List<Session>,
    projects: List<Project>
): List<ProjectSessionGroupUi> {
    if (sessions.isEmpty()) return emptyList()

    val projectsById = projects.associateBy { it.id }
    val grouped = sessions.groupBy {
        if (it.projectID.isBlank()) AppViewModel.UNASSIGNED_PROJECT_KEY else it.projectID
    }

    val orderedKeys = buildList {
        projects.forEach { project ->
            if (grouped.containsKey(project.id)) add(project.id)
        }
        grouped.keys
            .filter { it !in this }
            .sorted()
            .forEach { add(it) }
    }

    return orderedKeys.map { key ->
        val title = when (key) {
            AppViewModel.UNASSIGNED_PROJECT_KEY -> "Unassigned"
            else -> projectsById[key]?.worktree ?: key
        }
        val roots = buildSessionForest(grouped[key].orEmpty())
        ProjectSessionGroupUi(
            key = key,
            title = title,
            roots = roots
        )
    }
}

private fun buildSessionForest(sessions: List<Session>): List<SessionNodeUi> {
    if (sessions.isEmpty()) return emptyList()
    val byId = sessions.associateBy { it.id }
    val childrenMap = mutableMapOf<String?, MutableList<Session>>()
    sessions.forEach { session ->
        val parent = session.parentID?.takeIf { byId.containsKey(it) }
        childrenMap.getOrPut(parent) { mutableListOf() }.add(session)
    }

    fun build(parentID: String?): List<SessionNodeUi> {
        return childrenMap[parentID]
            .orEmpty()
            .sortedByDescending { it.time.updated }
            .map { session ->
                SessionNodeUi(
                    session = session,
                    children = build(session.id)
                )
            }
    }

    return build(null)
}

private fun extractAssistantErrorText(error: JsonObject?): String? {
    if (error == null) return null
    val direct = error["message"]?.jsonPrimitive?.contentOrNull
    if (!direct.isNullOrBlank()) return direct

    val data = error["data"] as? JsonObject
    val nested = data?.get("message")?.jsonPrimitive?.contentOrNull
    if (!nested.isNullOrBlank()) return nested

    val name = error["name"]?.jsonPrimitive?.contentOrNull
    return name?.takeIf { it.isNotBlank() }
}
