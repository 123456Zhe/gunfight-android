# Gunfight Android

Kotlin 原生客户端 for [zd-2d-gunfight](https://github.com/123456Zhe/zd-2d-gunfight)
（2D 俯视角多人枪战，Python + Pygame 服务端，UDP 联机）。

复用现有 UDP 协议（见 `PROTOCOL_SPEC.md`），无 WebView，无第三方网络库、无游戏引擎。

## 功能

- 双摇杆操作（左移动 / 右瞄准+开火）、手雷按钮、自动换弹、推门自动开、相机跟随
- HUD：血量/护甲/弹药/手雷/击杀播报/聊天（含加入离开等系统消息）/阵亡复活倒计时
- 走动自动拾取道具（医疗包/弹药/护甲/加速/手雷），走门自动上交 door_update
- 近战（刀）按钮：点按轻击、长按到冷却读满后升级为重击（HUD 有蓄力环）；
  本地预筛候选目标，服务端权威校验冷却/距离/角度/视线后才结算伤害
- 聊天/命令输入框（支持中文），可用 .help .kill .addai .team 等游戏内命令
- 队伍：队友无视视野裁剪始终可见、按队伍配色，HUD 显示所属队伍
- 视野裁剪：按桌面端同样的 FOV(120°) + 视线遮挡判断敌人与道具是否可见
- 远端玩家位置插值、子弹按速度外推、命中伤害数字、手雷爆炸圈
- 断线/被踢自动回到连接界面并提示原因

## 工程结构

```
gunfight-android/
├── protocol/          # 纯 Kotlin 协议模块（JVM 可测，无 Android 依赖）
│   └── src/main/kotlin/com/gunfight/protocol/
│       ├── UdpClient.kt    # UDP 收发、握手、保活、输入上行
│       ├── Model.kt        # 状态快照（玩家/子弹/手雷/道具/门/聊天/爆炸）
│       ├── Messages.kt     # C→S 消息构造（player_update/request_bullet/item_pickup/...）
│       ├── GameMap.kt      # grid3x3 地图确定性复刻（碰撞用）
│       ├── Vision.kt       # FOV + 视线遮挡（复刻 Python utils.is_visible）
│       └── Json.kt         # 零依赖 JSON（能解析 Python 的 \uXXXX 中文转义）
├── app/               # Android 应用
│   └── src/main/kotlin/com/gunfight/client/
│       ├── MainActivity.kt # 连接界面（记住上次 IP/端口/名字）
│       ├── GameView.kt     # 游戏视图（渲染 + 双摇杆 + HUD + 拾取）
│       └── GameSettings.kt # 显示/数值参数，读 assets/settings.json
├── build.sh           # 无 Gradle 的手动工具链构建（aapt2→kotlinc→d8→zipalign→apksigner）
└── PROTOCOL_SPEC.md   # 协议规格（逐项核对过 zd-2d-gunfight/network.py）
```

## 构建

### 方式 A：Gradle（标准，需要联网拉依赖）

```bash
echo "sdk.dir=$HOME/android-sdk" > local.properties   # 或在环境变量里给 ANDROID_HOME
./gradlew :app:assembleDebug        # APK: app/build/outputs/apk/debug/
./gradlew :protocol:test            # 协议层 JVM 单测
```

### 方式 B：build.sh（不依赖 Gradle/AGP，受限网络或 CI 沙盒里可用）

需要 Android SDK（`platforms;android-34` + `build-tools;34.0.0`）与 Kotlin 编译器：

```bash
export ANDROID_HOME=$HOME/android-sdk
export JAVA_HOME=/path/to/jdk17
export KOTLINC=/path/to/kotlinc          # 或者 KOTLIN_COMPILER_JAR=.../kotlin-compiler-embeddable-1.9.24.jar
./build.sh                                # 输出 dist/GunfightSpectate.apk
```

### 协议层互操作测试（对着真实 Python 服务端跑）

`protocol/src/test/kotlin/.../ReferenceServerTest.kt` 与 `PickupInteropTest.kt` 会启动
`refserver/helper_server.py`（带 pygame/constants/utils 桩），向真实 `network.py` 完成
UDP 握手、拾取道具、推门，并在客户端侧断言广播结果。参考实现不在本仓库内，默认跳过；
把 zd-2d-gunfight 的 `network.py` 指过来即可运行：

```bash
GUNFIGHT_REFERENCE_NETWORK=../zd-2d-gunfight/network.py ./gradlew :protocol:test
```

## 协议要点

- UDP/IPv4，默认端口 5555，一包一 JSON（UTF-8）
- 握手：`server_probe` → `server_info` → `connect_request` → `connect_response`
- 服务端 20Hz 广播状态（大包按 1200B 应用层拆分；客户端逐条 upsert，玩家 2s 未出现判掉线）
- 客户端 20Hz 上报 `player_update`（位置，每包 < 1200B），开火走 `request_bullet`（seq 去重）
- 服务端权威：伤害/弹药/复活位置/拾取效果/门板状态全部由服务端结算
- 服务端以 UDP 源地址绑定身份，`player_id`/`owner` 字段一律被覆盖

## 许可

GPL-3.0（与 zd-2d-gunfight 主仓库一致）。
