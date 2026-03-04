package ai.opencode.mobile.android

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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.opencode.mobile.android.ui.AppViewModel
import ai.opencode.mobile.core.model.Part
import ai.opencode.mobile.core.state.AppState

@Composable
fun OpenCodeAndroidApp(vm: AppViewModel = viewModel()) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val titles = listOf("Chat", "Files", "Settings")
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val error by vm.lastError.collectAsState()

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
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Files") },
                    label = { Text("Files") }
                )
                NavigationBarItem(
                    selected = selectedIndex == 2,
                    onClick = { selectedIndex = 2 },
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
                0 -> ChatScreen(vm, state)
                1 -> FilesScreen(vm, state)
                else -> SettingsScreen(vm, state)
            }
        }
    }
}

@Composable
private fun ChatScreen(vm: AppViewModel, state: AppState) {
    val chatInput by vm.chatInput.collectAsState()
    val sessionTitleInput by vm.sessionTitleInput.collectAsState()
    val selectedModelIndex by vm.selectedModelIndex.collectAsState()
    val selectedAgentName by vm.selectedAgentName.collectAsState()
    val models = vm.models

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        Text("Sessions", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.sessions, key = { it.id }) { session ->
                val status = state.sessionStatuses[session.id]?.type ?: "idle"
                val statusTag = when (status) {
                    "busy", "retry" -> " ●"
                    else -> ""
                }
                FilterChip(
                    selected = state.currentSessionID == session.id,
                    onClick = { vm.selectSession(session.id) },
                    label = { Text(session.title.ifBlank { session.id.take(8) } + statusTag) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = sessionTitleInput,
                onValueChange = vm::setSessionTitleInput,
                label = { Text("New session title") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = { vm.createSession() }, enabled = state.isConnected) {
                Text("Create")
            }
            OutlinedButton(onClick = { vm.renameCurrentSession() }, enabled = state.currentSessionID != null) {
                Text("Rename")
            }
            OutlinedButton(onClick = { vm.deleteCurrentSession() }, enabled = state.currentSessionID != null) {
                Text("Delete")
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

        HorizontalDivider()

        val sessionPermissions = state.pendingPermissions.filter { it.sessionID == state.currentSessionID }
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

        val currentTodos = state.currentSessionID?.let { state.todosBySession[it] }.orEmpty()
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

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                            Text("(no parts)")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = vm::setChatInput,
                label = { Text("Message") },
                modifier = Modifier.weight(1f),
                keyboardActions = KeyboardActions(onSend = { vm.sendMessage() }),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            IconButton(onClick = { vm.sendMessage() }, enabled = state.currentSessionID != null && state.isConnected) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
            OutlinedButton(onClick = { vm.abortCurrentSession() }, enabled = state.currentSessionID != null) {
                Text("Abort")
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
                    content.type == "text" -> Text(content.content.orEmpty())
                    else -> Text("Binary file")
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: AppViewModel, state: AppState) {
    val settings by vm.settingsForm.collectAsState()
    val ssh by vm.sshForm.collectAsState()
    val sshStatus by vm.sshStatus.collectAsState()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
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
            Button(onClick = { vm.applySettingsAndReconnect() }) { Text("Apply & Connect") }
            OutlinedButton(onClick = { vm.refreshAll() }) { Text("Refresh") }
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
            Button(onClick = { vm.connectSshTunnel() }) { Text("Connect SSH") }
            OutlinedButton(onClick = { vm.disconnectSshTunnel() }) { Text("Disconnect SSH") }
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

        Spacer(modifier = Modifier.height(8.dp))
        Text("Server Version: ${state.serverVersion ?: "-"}")
        Text("Current Session: ${state.currentSessionID ?: "-"}")
        Text("Sessions: ${state.sessions.size}")
        Text("Agents: ${state.agents.size}")
    }
}
