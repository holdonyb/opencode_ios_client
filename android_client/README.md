# OpenCode Android Client 使用说明

Android 端实现（Jetpack Compose + Kotlin），用于连接 OpenCode 服务、管理会话、查看文件和设置语音/SSH。

## 1. 环境准备

### 必需
- Android SDK（已配置 `adb`、`emulator`）
- JDK 17
- 可用的 OpenCode Server（本地或远端）

### 本机命令行（PowerShell）推荐环境变量

```powershell
$env:JAVA_HOME='E:\Android\tools\jdk17_tmp\extract\jdk-17.0.18+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

## 2. 编译 APK

在仓库根目录执行：

```powershell
cd android_client
.\gradlew.bat :app:assembleDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx2048m -Xms512m -XX:MaxMetaspaceSize=512m -XX:ReservedCodeCacheSize=256m -XX:HeapBaseMinAddress=4g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8" -Dkotlin.daemon.jvm.options=-Xmx512m
```

输出 APK：

`android_client/app/build/outputs/apk/debug/app-debug.apk`

## 3. 安装到模拟器

```powershell
E:\Android\Sdk\platform-tools\adb.exe devices
E:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## 4. App 内配置入口（重点）

启动 App 后，所有连接配置都在 `Settings` 页签。

### Server
- `Server URL`：OpenCode 服务地址
- `Username` / `Password`：可选，服务启用 Basic Auth 时填写
- 点击 `Apply & Connect` 生效并重连

### Speech recognition (AI Builder)
- 在 `Speech recognition` 里可选 `AI Builder` / `Doubao`
- `Speech Base URL`
- `Speech Token`（Doubao 模式下填写 API Key）
- Doubao 模式下额外填写 `Doubao Resource ID`（如 `volc.seedasr.auc`）
- `Custom Prompt` / `Terminology`
- 点 `Test speech` 测试连通性

Doubao 说明：
- 如果 `Speech Base URL` 是 `https://openspeech.bytedance.com`，客户端会调用 OpenClaw 同款 `submit/query` 接口。
- 如果是其它网关地址（如 AI Builder 代理），客户端走 OpenAI 兼容 `/v1/audio/transcriptions`。

### SSH Tunnel
- 填写 `SSH Host/Port/User` 和密码或私钥
- `Connect SSH` 建立隧道后，会自动把 URL 切到 `http://127.0.0.1:<localPort>` 并自动重连
- 若你手动改过 `Server URL`、账号密码，建议再点一次 `Apply & Connect` 固化配置

## 5. URL 该怎么填（最常用）

### 场景 A：Android 模拟器连你电脑上的 OpenCode
- `Server URL` 填：`http://10.0.2.2:4096`

说明：`127.0.0.1` 在模拟器里指向模拟器自己，不是电脑宿主机。

### 场景 B：真机连同一局域网电脑
- `Server URL` 填：`http://<你的电脑局域网IP>:4096`
- 例如：`http://192.168.1.20:4096`

### 场景 C：走 SSH 隧道
- 在 `SSH Tunnel` 配好后点 `Connect SSH`
- 自动得到 `http://127.0.0.1:<localPort>`
- 通常会自动重连；若你刚修改过账号密码，再点一次 `Apply & Connect`

## 6. Sessions 页面怎么用

`Sessions` 页签已支持：
- 按项目分组显示
- `session -> 子session` 展开/收起
- 点行切换当前会话
- 会话操作：`Create / Rename / Delete / Compact / Load older`

注意：
- 选中具体项目过滤时，`Create` 会被禁用
- 切回 `Server default` 后可创建新会话

## 7. 网络策略说明（这次已修复）

AndroidManifest 已开启：

- `android:usesCleartextTraffic="true"`

所以 `http://` 地址不再被 Android 网络安全策略拦截。

## 8. 常见问题

### Q1: 连接不上但没有 cleartext 报错
- 通常是地址不可达（端口未开、服务未启动、IP 填错）
- 先在服务端确认 `opencode serve --port 4096` 已运行

### Q2: 模拟器里填 `127.0.0.1:4096` 连不上
- 改成 `10.0.2.2:4096`（除非你确实在 App 内用了 SSH 隧道）

### Q3: SSH 已连接但 App 还是旧地址
- 再点一次 `Apply & Connect`

## 9. OpenCode 的 SSH 远程部署（推荐两种）

先说明安卓端 SSH 的实际行为：
- App 会建立本地转发：`手机本地 localPort -> SSH 远端 127.0.0.1:remotePort`
- 对应代码见：
  - `android_client/app/src/main/kotlin/ai/opencode/mobile/android/ssh/AndroidSshTunnelManager.kt`

### 方案 A：直接把 OpenCode 部署在 VPS（最简单）

#### VPS 上
1. 安装并确认 `opencode` 命令可用（`opencode --help`）。
2. 启动服务（建议只监听本机）：

```bash
OPENCODE_SERVER_PASSWORD='your-password' opencode serve --hostname 127.0.0.1 --port 4096
```

3. VPS 本机自检：

```bash
curl -u any:your-password http://127.0.0.1:4096/
```

#### Android App 上
- `Settings -> SSH Tunnel`
  - `SSH Host`: 你的 VPS 域名/IP
  - `SSH Port`: `22`
  - `SSH User`: VPS 用户
  - `Remote Port`: OpenCode 在 VPS 上监听的端口（例如 `5096`）
  - `Local Port`: `14096`（默认即可）
- 点 `Connect SSH`
- 回到上方 `Server` 区域，确认 URL 已变成 `http://127.0.0.1:14096`
- 填 `Username/Password`（如果服务端开了 Basic Auth）
- 点 `Apply & Connect`

### 方案 B：OpenCode 在家里机器，VPS 只做跳板

#### 家里机器
1. 启动 OpenCode：

```bash
OPENCODE_SERVER_PASSWORD='your-password' opencode serve --hostname 127.0.0.1 --port 4096
```

2. 建立反向隧道到 VPS（把家里 4096 暴露到 VPS 的 18080）：

```bash
ssh -N -T -R 127.0.0.1:18080:127.0.0.1:4096 user@your-vps
```

#### Android App 上
- SSH 配置同上，但 `Remote Port` 改为 `18080`
- 点 `Connect SSH` 后，再点 `Apply & Connect`

### 稳定运行建议（Linux）
- OpenCode 用 `systemd` 常驻
- 反向隧道用 `autossh` + `systemd` 常驻
- 不要把 OpenCode 端口直接暴露公网（除非你已做 HTTPS + 严格认证）
