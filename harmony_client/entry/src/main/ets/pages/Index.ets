import { AppState, FileDiff, MessageModelInfo, Session, SshTunnelConfig } from '../model/Models'
import { AppController } from '../state/AppController'

@Entry
@Component
struct Index {
  @State selectedTab: number = 0
  @State state: AppState = {
    serverBaseUrl: 'http://127.0.0.1:4096',
    connected: false,
    sessions: [],
    sessionStatuses: {},
    messages: [],
    streamingPartTexts: {},
    projects: [],
    agents: [],
    pendingPermissions: [],
    todosBySession: {},
    filePath: '',
    fileNodes: [],
    fileStatuses: [],
    providerContextLimits: {},
    messageLimitBySession: {},
    hasMoreHistoryBySession: {},
    loadingOlderMessages: false,
    sessionDiffs: [],
    fileSearchQuery: '',
    fileSearchResults: [],
    speechBaseUrl: 'https://space.ai-builders.com/backend',
    speechToken: '',
    speechPrompt: 'Prefer snake_case filenames and keep code terms unchanged.',
    speechTerminology: '',
    speechConnectionOk: false,
    sshConfig: {
      enabled: false,
      host: '',
      port: 22,
      username: '',
      password: '',
      privateKey: '',
      passphrase: '',
      remotePort: 18080,
      localPort: 14096
    },
    sshStatus: 'Disconnected'
  }
  @State chatInput: string = ''
  @State newSessionTitle: string = ''
  @State settingsUrl: string = 'http://127.0.0.1:4096'
  @State settingsUsername: string = ''
  @State settingsPassword: string = ''
  @State selectedAgent: string = 'build'
  @State selectedModelIndex: number = 0
  @State filePathInput: string = ''
  @State fileSearchInput: string = ''
  @State showDiff: boolean = true
  @State speechBaseUrl: string = 'https://space.ai-builders.com/backend'
  @State speechToken: string = ''
  @State speechPrompt: string = 'Prefer snake_case filenames and keep code terms unchanged.'
  @State speechTerms: string = ''
  @State sshEnabled: boolean = false
  @State sshHost: string = ''
  @State sshPort: string = '22'
  @State sshUsername: string = ''
  @State sshPassword: string = ''
  @State sshPrivateKey: string = ''
  @State sshPassphrase: string = ''
  @State sshRemotePort: string = '18080'
  @State sshLocalPort: string = '14096'

  private controller: AppController = new AppController('http://127.0.0.1:4096')

  aboutToAppear(): void {
    this.controller.subscribe((next) => {
      this.state = next
      this.settingsUrl = next.serverBaseUrl
      this.filePathInput = next.filePath
      this.fileSearchInput = next.fileSearchQuery
      this.speechBaseUrl = next.speechBaseUrl
      this.speechToken = next.speechToken
      this.speechPrompt = next.speechPrompt
      this.speechTerms = next.speechTerminology
      this.selectedModelIndex = this.controller.selectedModelIndexForCurrentSession()
      this.syncSshFromState(next.sshConfig)
      if (next.agents.length > 0 && this.selectedAgent === 'build') {
        this.selectedAgent = next.agents[0].name
      }
    })
    this.controller.refreshAll()
  }

  aboutToDisappear(): void {
    this.controller.dispose()
  }

  build() {
    Column() {
      this.TopBar()

      if (this.selectedTab === 0) {
        this.ChatTab()
      } else if (this.selectedTab === 1) {
        this.FilesTab()
      } else {
        this.SettingsTab()
      }

      Blank()
      this.BottomTabs()
    }
    .width('100%')
    .height('100%')
    .padding(12)
  }

  @Builder
  private TopBar() {
    Row() {
      Text(this.selectedTab === 0 ? 'Chat' : this.selectedTab === 1 ? 'Files' : 'Settings')
        .fontSize(24)
        .fontWeight(FontWeight.Bold)
      Blank()
      Text(this.state.connected ? 'Connected' : 'Disconnected')
        .fontSize(14)
        .fontColor(this.state.connected ? '#2E7D32' : '#C62828')
    }
    .width('100%')
    .padding({ top: 6, bottom: 10 })

    if (this.state.connectionError) {
      Row() {
        Text(this.state.connectionError)
          .fontSize(12)
          .fontColor('#B71C1C')
      }
      .width('100%')
      .padding(8)
      .backgroundColor('#FFEBEE')
      .borderRadius(8)
    }
  }

  @Builder
  private ChatTab() {
    Column({ space: 8 }) {
      Text('Sessions').fontWeight(FontWeight.Medium)
      Scroll() {
        Row({ space: 8 }) {
          ForEach(this.state.sessions, (s: Session) => {
            const status = this.state.sessionStatuses[s.id]?.type ?? 'idle'
            const suffix = status === 'busy' || status === 'retry' ? ' ●' : ''
            Button(`${s.title.length > 0 ? s.title : s.id.substring(0, 8)}${suffix}`)
              .type(this.state.currentSessionID === s.id ? ButtonType.Capsule : ButtonType.Normal)
              .onClick(() => this.controller.selectSession(s.id))
          })
        }
      }
      .scrollable(ScrollDirection.Horizontal)
      .width('100%')

      Row({ space: 8 }) {
        TextInput({ text: this.newSessionTitle, placeholder: 'Session title' })
          .onChange((value: string) => this.newSessionTitle = value)
          .layoutWeight(1)
        Button('Create').onClick(() => this.controller.createSession(this.newSessionTitle))
        Button('Rename').onClick(() => this.controller.renameCurrentSession(this.newSessionTitle))
        Button('Delete').onClick(() => this.controller.deleteCurrentSession())
      }
      .width('100%')

      if (this.state.selectedProjectWorktree) {
        Text('Create is disabled when project filter is active.').fontSize(11).fontColor('#C62828')
      }

      Row({ space: 8 }) {
        Button('Compact').onClick(() => this.controller.summarizeCurrentSession())
        Button(this.state.loadingOlderMessages ? 'Loading older...' : 'Load older')
          .onClick(() => this.controller.loadOlderMessages())
      }
      .width('100%')

      Text('Model').fontWeight(FontWeight.Medium)
      Scroll() {
        Row({ space: 8 }) {
          ForEach(this.controller.getModelPresets(), (m: MessageModelInfo) => {
            const idx = this.controller.getModelPresets().findIndex((x) => x.providerID === m.providerID && x.modelID === m.modelID)
            Button(`${m.providerID}/${m.modelID}`)
              .type(this.selectedModelIndex === idx ? ButtonType.Capsule : ButtonType.Normal)
              .onClick(() => {
                this.selectedModelIndex = idx
                this.controller.setSelectedModelIndex(idx)
              })
          })
        }
      }
      .scrollable(ScrollDirection.Horizontal)
      .width('100%')

      Text('Agent').fontWeight(FontWeight.Medium)
      Scroll() {
        Row({ space: 8 }) {
          ForEach(this.state.agents.length > 0 ? this.state.agents : [{ name: 'build' }], (a) => {
            Button(a.name)
              .type(this.selectedAgent === a.name ? ButtonType.Capsule : ButtonType.Normal)
              .onClick(() => this.selectedAgent = a.name)
          })
        }
      }
      .scrollable(ScrollDirection.Horizontal)
      .width('100%')

      if (this.state.contextUsage) {
        Column({ space: 4 }) {
          Text('Context usage').fontWeight(FontWeight.Medium)
          Text(`${this.state.contextUsage.totalTokens}/${this.state.contextUsage.contextLimit} (${Math.floor(this.state.contextUsage.usageRatio * 100)}%)`)
          Text(`${this.state.contextUsage.providerID}/${this.state.contextUsage.modelID}`).fontSize(12)
          Text(`input=${this.state.contextUsage.inputTokens}, output=${this.state.contextUsage.outputTokens}, reasoning=${this.state.contextUsage.reasoningTokens}`).fontSize(12)
        }
        .padding(8)
        .backgroundColor('#F5F5F5')
        .borderRadius(8)
      } else if (this.state.providerConfigError) {
        Text(this.state.providerConfigError).fontSize(11).fontColor('#C62828')
      }

      if ((this.state.hasMoreHistoryBySession[this.state.currentSessionID ?? ''] ?? false) || this.state.loadingOlderMessages) {
        Text(this.state.loadingOlderMessages ? 'Loading older messages...' : 'Tap Load older for more history')
          .fontSize(11)
          .fontColor('#666666')
      }

      if (this.state.pendingPermissions.length > 0) {
        Text('Permissions').fontWeight(FontWeight.Medium)
        Column({ space: 8 }) {
          ForEach(this.state.pendingPermissions, (p) => {
            if (p.sessionID === this.state.currentSessionID) {
              Column({ space: 6 }) {
                Text(`Permission: ${p.permission ?? p.id}`)
                Row({ space: 8 }) {
                  Button('Allow once').onClick(() => this.controller.respondPermission(p.sessionID, p.id, 'once'))
                  Button('Always').onClick(() => this.controller.respondPermission(p.sessionID, p.id, 'always'))
                  Button('Reject').onClick(() => this.controller.respondPermission(p.sessionID, p.id, 'reject'))
                }
              }
              .padding(8)
              .backgroundColor('#F5F5F5')
              .borderRadius(8)
            }
          })
        }
      }

      List() {
        ForEach(this.state.messages, (msg) => {
          ListItem() {
            Column({ space: 4 }) {
              Text(`${msg.info.role} • ${msg.info.id.substring(0, 8)}`).fontSize(12)
              ForEach(msg.parts, (part) => {
                const key = `${part.messageID}:${part.id}`
                const stream = this.state.streamingPartTexts[key]
                if (part.type === 'text' || part.type === 'reasoning') {
                  Text(stream ? stream : (part.text ?? ''))
                } else if (part.type === 'tool') {
                  Text(`Tool: ${part.tool ?? ''}`)
                } else if (part.type === 'patch') {
                  Text('Patch')
                } else {
                  Text(`Part: ${part.type}`)
                }
              })
            }
            .padding(8)
          }
        })
      }
      .layoutWeight(1)
      .divider({ strokeWidth: 0.5 })

      Row({ space: 8 }) {
        TextInput({ text: this.chatInput, placeholder: 'Message' })
          .onChange((value: string) => this.chatInput = value)
          .layoutWeight(1)
        Button('Send').onClick(async () => {
          await this.controller.sendMessage(this.chatInput, this.selectedAgent)
          this.chatInput = ''
        })
        Button('Abort').onClick(() => this.controller.abortCurrentSession())
      }
      .width('100%')
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  private FilesTab() {
    Column({ space: 8 }) {
      Row({ space: 8 }) {
        TextInput({ text: this.filePathInput, placeholder: 'Path' })
          .onChange((value: string) => this.filePathInput = value)
          .layoutWeight(1)
        Button('Load').onClick(() => this.controller.loadFileList(this.filePathInput))
        Button('Root').onClick(() => this.controller.loadFileRoot())
      }

      Row({ space: 8 }) {
        TextInput({ text: this.fileSearchInput, placeholder: 'Search file' })
          .onChange((value: string) => this.fileSearchInput = value)
          .layoutWeight(1)
        Button('Search').onClick(() => this.controller.searchFiles(this.fileSearchInput))
      }

      if (this.state.fileSearchResults.length > 0) {
        Scroll() {
          Row({ space: 8 }) {
            ForEach(this.state.fileSearchResults, (path) => {
              Button(path).onClick(() => this.controller.openFile(path))
            })
          }
        }
        .scrollable(ScrollDirection.Horizontal)
      }

      Row({ space: 8 }) {
        List() {
          ForEach(this.state.fileNodes, (node) => {
            ListItem() {
              Row() {
                Text(node.type === 'directory' ? `[D] ${node.name}` : node.name)
                Blank()
                Text(this.findStatus(node.path)).fontSize(11)
              }
              .width('100%')
              .padding(6)
              .onClick(() => {
                if (node.type === 'directory') {
                  this.controller.loadFileList(node.path)
                  this.filePathInput = node.path
                } else {
                  this.controller.openFile(node.path)
                }
              })
            }
          })
        }
        .layoutWeight(1)

        Scroll() {
          Column({ space: 6 }) {
            const diff = this.selectedFileDiff()
            Text(this.state.selectedFilePath ?? 'No file selected').fontWeight(FontWeight.Medium)
            if (diff) {
              Row({ space: 8 }) {
                Button(this.showDiff ? 'Show content' : 'Show diff').onClick(() => this.showDiff = !this.showDiff)
              }
            }
            if (this.showDiff && diff) {
              Text('--- before ---').fontColor('#C62828').fontSize(12)
              Text(diff.before).fontSize(12)
              Text('+++ after +++').fontColor('#2E7D32').fontSize(12)
              Text(diff.after).fontSize(12)
            } else {
              Text(this.state.selectedFileContent?.type === 'text'
                ? (this.state.selectedFileContent?.content ?? '')
                : (this.state.selectedFilePath ? 'Binary file' : ''))
                .fontSize(12)
            }
          }
          .padding(8)
        }
        .layoutWeight(1)
        .height('100%')
      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  private SettingsTab() {
    Scroll() {
      Column({ space: 8 }) {
        TextInput({ text: this.settingsUrl, placeholder: 'Server URL' })
          .onChange((value: string) => this.settingsUrl = value)
        TextInput({ text: this.settingsUsername, placeholder: 'Username' })
          .onChange((value: string) => this.settingsUsername = value)
        TextInput({ text: this.settingsPassword, placeholder: 'Password' })
          .type(InputType.Password)
          .onChange((value: string) => this.settingsPassword = value)

        Row({ space: 8 }) {
          Button('Apply & Connect').onClick(() => this.controller.applySettings(this.settingsUrl, this.settingsUsername, this.settingsPassword))
          Button('Refresh').onClick(() => this.controller.refreshAll())
        }

        Text('Speech recognition').fontWeight(FontWeight.Medium)
        TextInput({ text: this.speechBaseUrl, placeholder: 'Speech Base URL' })
          .onChange((value: string) => this.speechBaseUrl = value)
        TextInput({ text: this.speechToken, placeholder: 'Speech Token' })
          .type(InputType.Password)
          .onChange((value: string) => this.speechToken = value)
        TextInput({ text: this.speechPrompt, placeholder: 'Speech Prompt' })
          .onChange((value: string) => this.speechPrompt = value)
        TextInput({ text: this.speechTerms, placeholder: 'Terminology' })
          .onChange((value: string) => this.speechTerms = value)
        Row({ space: 8 }) {
          Button('Apply speech').onClick(() => {
            this.controller.setSpeechConfig(this.speechBaseUrl, this.speechToken, this.speechPrompt, this.speechTerms)
          })
          Button('Test speech').onClick(() => this.controller.testSpeechConnection())
        }
        Text(this.state.speechConnectionOk ? 'Speech: Connected' : `Speech: ${this.state.speechConnectionError ?? 'Not tested'}`)
          .fontSize(12)

        Text('SSH tunnel').fontWeight(FontWeight.Medium)
        Row() {
          Text('Enable SSH')
          Blank()
          Toggle({ type: ToggleType.Switch, isOn: this.sshEnabled })
            .onChange((value: boolean) => this.sshEnabled = value)
        }
        TextInput({ text: this.sshHost, placeholder: 'SSH Host' })
          .onChange((value: string) => this.sshHost = value)
        Row({ space: 8 }) {
          TextInput({ text: this.sshPort, placeholder: 'Port' })
            .onChange((value: string) => this.sshPort = value)
            .layoutWeight(1)
          TextInput({ text: this.sshUsername, placeholder: 'Username' })
            .onChange((value: string) => this.sshUsername = value)
            .layoutWeight(1)
        }
        TextInput({ text: this.sshPassword, placeholder: 'Password' })
          .type(InputType.Password)
          .onChange((value: string) => this.sshPassword = value)
        TextInput({ text: this.sshPrivateKey, placeholder: 'Private key (optional)' })
          .onChange((value: string) => this.sshPrivateKey = value)
        TextInput({ text: this.sshPassphrase, placeholder: 'Passphrase (optional)' })
          .type(InputType.Password)
          .onChange((value: string) => this.sshPassphrase = value)
        Row({ space: 8 }) {
          TextInput({ text: this.sshRemotePort, placeholder: 'Remote Port' })
            .onChange((value: string) => this.sshRemotePort = value)
            .layoutWeight(1)
          TextInput({ text: this.sshLocalPort, placeholder: 'Local Port' })
            .onChange((value: string) => this.sshLocalPort = value)
            .layoutWeight(1)
        }
        Row({ space: 8 }) {
          Button('Apply SSH').onClick(() => this.applySshConfig())
          Button('Connect SSH').onClick(() => this.controller.connectSshTunnel())
          Button('Disconnect SSH').onClick(() => this.controller.disconnectSshTunnel())
        }
        Text(`SSH status: ${this.state.sshStatus}`).fontSize(12)

        Text('Projects').fontWeight(FontWeight.Medium)
        Button(this.state.selectedProjectWorktree ? 'Server default' : 'Server default (selected)')
          .onClick(() => this.controller.selectProject(undefined))

        ForEach(this.state.projects, (p) => {
          Button(p.worktree).onClick(() => this.controller.selectProject(p.worktree))
        })

        Text(`Server Version: ${this.state.serverVersion ?? '-'}`)
        Text(`Sessions: ${this.state.sessions.length}`)
        Text(`Agents: ${this.state.agents.length}`)
      }
      .width('100%')
      .padding({ bottom: 24 })
    }
    .scrollable(ScrollDirection.Vertical)
    .width('100%')
    .height('100%')
  }

  @Builder
  private BottomTabs() {
    Row() {
      Button('Chat').onClick(() => this.selectedTab = 0).type(this.selectedTab === 0 ? ButtonType.Capsule : ButtonType.Normal)
      Button('Files').onClick(() => this.selectedTab = 1).type(this.selectedTab === 1 ? ButtonType.Capsule : ButtonType.Normal)
      Button('Settings').onClick(() => this.selectedTab = 2).type(this.selectedTab === 2 ? ButtonType.Capsule : ButtonType.Normal)
    }
    .width('100%')
    .justifyContent(FlexAlign.SpaceEvenly)
    .padding({ top: 10 })
  }

  private findStatus(path: string): string {
    const item = this.state.fileStatuses.find((x) => x.path === path)
    return item?.status ?? ''
  }

  private selectedFileDiff(): FileDiff | undefined {
    const selected = this.state.selectedFilePath
    if (!selected) return undefined
    return this.state.sessionDiffs.find((d) => d.file === selected || selected.endsWith(d.file) || d.file.endsWith(selected))
  }

  private syncSshFromState(config: SshTunnelConfig): void {
    this.sshEnabled = config.enabled
    this.sshHost = config.host
    this.sshPort = `${config.port}`
    this.sshUsername = config.username
    this.sshPassword = config.password ?? ''
    this.sshPrivateKey = config.privateKey ?? ''
    this.sshPassphrase = config.passphrase ?? ''
    this.sshRemotePort = `${config.remotePort}`
    this.sshLocalPort = `${config.localPort}`
  }

  private applySshConfig(): void {
    const cfg: SshTunnelConfig = {
      enabled: this.sshEnabled,
      host: this.sshHost.trim(),
      port: parseInt(this.sshPort, 10) || 22,
      username: this.sshUsername.trim(),
      password: this.sshPassword,
      privateKey: this.sshPrivateKey,
      passphrase: this.sshPassphrase,
      remotePort: parseInt(this.sshRemotePort, 10) || 18080,
      localPort: parseInt(this.sshLocalPort, 10) || 14096
    }
    this.controller.setSshConfig(cfg)
  }
}
