import { AppState, Session } from '../model/Models'
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
    fileStatuses: []
  }
  @State chatInput: string = ''
  @State newSessionTitle: string = ''
  @State settingsUrl: string = 'http://127.0.0.1:4096'
  @State settingsUsername: string = ''
  @State settingsPassword: string = ''
  @State selectedAgent: string = 'build'
  @State filePathInput: string = ''

  private controller: AppController = new AppController('http://127.0.0.1:4096')

  aboutToAppear(): void {
    this.controller.subscribe((next) => {
      this.state = next
      this.settingsUrl = next.serverBaseUrl
      if (next.agents.length > 0 && this.selectedAgent === 'build') {
        this.selectedAgent = next.agents[0].name
      }
      if (this.filePathInput.length === 0) {
        this.filePathInput = next.filePath
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
    Column() {
      Text('Sessions').fontWeight(FontWeight.Medium)
      Scroll() {
        Row({ space: 8 }) {
          ForEach(this.state.sessions, (s: Session) => {
            Button(s.title.length > 0 ? s.title : s.id.substring(0, 8))
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
      .padding({ top: 8, bottom: 8 })

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

      if (this.state.pendingPermissions.length > 0) {
        Text('Permissions').fontWeight(FontWeight.Medium).margin({ top: 8 })
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
      .padding({ top: 8 })
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
          Column() {
            Text(this.state.selectedFilePath ?? 'No file selected').fontWeight(FontWeight.Medium)
            Text(this.state.selectedFileContent?.type === 'text'
              ? (this.state.selectedFileContent?.content ?? '')
              : (this.state.selectedFilePath ? 'Binary file' : ''))
              .fontSize(12)
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

        Text('Projects').fontWeight(FontWeight.Medium)
        Button(this.state.selectedProjectWorktree ? 'Server default' : 'Server default (selected)')
          .onClick(() => this.controller.selectProject(undefined))

        ForEach(this.state.projects, (p) => {
          Button(p.worktree).onClick(() => this.controller.selectProject(p.worktree))
        })

        Text(`Server Version: ${this.state.serverVersion ?? '-'}`)
        Text(`Sessions: ${this.state.sessions.length}`)
        Text(`Agents: ${this.state.agents.length}`)
        Text('SSH mode: External tunnel (V1)')
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
}
