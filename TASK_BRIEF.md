# 任务：zd-2d-gunfight 安卓原生客户端（Kotlin）— Phase 1

## 背景
- `zd-2d-gunfight` 是 2D 俯视角多人枪战游戏：Python + Pygame 服务端，UDP 局域网联机。
- 协议规格：`PROTOCOL_SPEC.md`（已按 `reference/network.py` 逐项核对，是主要依据）。
- `reference/network.py` 是协议的 source of truth（3074 行），`reference/settings.json` 是地图/数值配置。
- 要求：**Kotlin 原生客户端，复用现有 UDP 协议，不要 WebView 套壳**。画面几何级一致即可，不要求像素级一致。

## 本阶段目标（Phase 1：连上 + 看得见）
工作目录：`/home/hatch/workspace/gunfight-android/`

1. **搭工程**：标准 Android Gradle 工程（Kotlin，minSdk 26，targetSdk 34）。本机没有 Android SDK，
   先装 cmdline-tools（`https://dl.google.com/android/repository/commandlinetools-linux-*.zip`），
   接受 licenses，装 platform-tools、platform android-34、build-tools。用 `local.properties` 或
   `ANDROID_HOME`/`ANDROID_SDK_ROOT` 指向 SDK。网络走环境变量里的 https_proxy。
2. **网络层**（纯 Kotlin/JVM 可单元测试，建议独立 module 如 `:protocol`）：
   - UDP/IPv4，默认端口 5555，一包一 JSON（UTF-8）。
   - 握手：`server_probe` → `server_info` → `connect_request` → `connect_response` → 初始状态。
   - 接收 20Hz 状态广播（玩家/子弹/手雷/道具），解析并更新本地状态模型。
   - 1Hz heartbeat 保活；5 秒无包判超时断线。
   - 坐标系：y-down；角度 0°=+x，90°=上，逆时针为正。
   - `combat_feedback`：kill 广播 / hit 单播（Phase 1 可只解析不展示）。
3. **观战渲染**（`:app`，Android Canvas 自绘 View，不用游戏引擎）：
   - 俯视角战场：墙体（矩形）、玩家（圆 + 朝向线 + 头顶血条 + 名字）、子弹（小圆点/短线）、手雷、道具。
   - 颜色/尺寸从 settings.json 取（对应 Python 客户端的配色）。
   - 视角：跟随第一个玩家，或双指缩放+拖动自由视角（二选一，先实现跟随）。
   - 左上角显示连接状态 + FPS。
4. **主界面**：输入服务器 IP（默认 127.0.0.1）+ 端口 + 玩家名，点"连接"进入观战。

## 约束
- **只做 Phase 1**：不要做双摇杆输入、射击、移动（那是 Phase 2，会另起任务）。
- 代码注释用英文；最终汇报用中文。
- 省 token：能复用就复用，不要过度设计。

## 验证（必须）
1. `./gradlew assembleDebug` 编译通过。
2. 协议层 JUnit 测试：各类消息编解码 round-trip；握手状态机。
3. 联调用 `reference/network.py` 起 Python 服务端（`python3 -c` 或直接跑服务端 main，
   看 network.py 的 `__main__`/测试入口），客户端用 JVM 测试或插桩方式完成一次真实 UDP 握手并收到状态广播。
   没有 emulator 就用纯 JVM 测试覆盖网络层，UI 只保证编译+基本逻辑。

## 汇报
完成后中文汇报：工程结构、装了哪些 SDK 组件、验证结果（编译/测试/联调）、APK 位置（如果打出来了）、
Phase 2 的建议。遇到阻塞超过 3 次重试就停下汇报，不要无限烧 token。
