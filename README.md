# Gunfight Android

Kotlin 原生客户端 for [zd-2d-gunfight](https://github.com/123456Zhe/zd-2d-gunfight)（2D 俯视角多人枪战，Python + Pygame 服务端，UDP 联机）。

复用现有 UDP 协议（见 `PROTOCOL_SPEC.md`），无 WebView，无第三方网络库。

## 功能

- **Phase 1（观战）**：连接服务器，俯视角实时渲染玩家/子弹/手雷/道具/门
- **Phase 2（可玩）**：双摇杆操作（左移动 / 右瞄准+开火）、HUD（血量/护甲/弹药/手雷）、手雷按钮、自动换弹、推门自动开、相机跟随

## 工程结构

```
gunfight-android/
├── protocol/          # 纯 Kotlin 协议模块（JVM 可测，无 Android 依赖）
│   └── src/main/kotlin/com/gunfight/protocol/
│       ├── UdpClient.kt    # UDP 收发、握手、保活
│       ├── Model.kt        # 状态快照（玩家/子弹/手雷/道具/门）
│       ├── Messages.kt     # C→S 消息构造（player_update/request_bullet/...）
│       ├── GameMap.kt      # grid3x3 地图确定性复刻（碰撞用）
│       └── ...
├── app/               # Android 应用
│   └── src/main/kotlin/com/gunfight/client/
│       ├── MainActivity.kt # 连接界面
│       ├── GameView.kt     # 游戏视图（渲染 + 双摇杆 + HUD）
│       └── GameSettings.kt # 显示参数
└── PROTOCOL_SPEC.md   # 协议规格（逐项核对过 reference/network.py）
```

## 构建

标准 Gradle 工程（minSdk 26，targetSdk 34）。但在受限沙盒里 Gradle daemon 的 socket IPC 可能被掐，
备用方案是手动工具链（`build.sh`）：`aapt2 → kotlinc → d8 → zipalign → apksigner`。

```bash
./build.sh   # 输出 APK 到 workspace/your_files/GunfightSpectate.apk（需配好 ANDROID_HOME 与 kotlinc）
```

## 协议要点

- UDP/IPv4，端口 5555，一包一 JSON（UTF-8）
- 握手：`server_probe` → `server_info` → `connect_request` → `connect_response`
- 服务端 20Hz 广播状态（大包按 1200B 拆分，客户端逐条 upsert）
- 客户端 20Hz 上报 `player_update`（位置），开火走 `request_bullet`（seq 去重）
- 服务端以 UDP 源地址绑定身份，`player_id`/`owner` 字段一律被覆盖
