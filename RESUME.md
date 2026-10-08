# RESUME（2026-10-07 19:15）：上一轮被 VM 重置中断，从这里继续

## 背景
上一轮 muse 已工作约 30 分钟（todo：协议层+JUnit 测试已完成，观战 UI 进行中），
在跑 Gradle 构建时 VM 被重置：后台进程全灭，`/opt/gradle-8.7` 和 `/opt/android-sdk`
（都装在 /opt 下，已丢失），`/tmp` 被清空，SSH 隧道已重建。
`~/workspace/gunfight-android/` 下的工程文件都还在。

## 继续做的事（不要重做已完成的）
1. 先读一遍现有代码（`protocol/` 和 `app/` 下全部 .kt），确认完成度。
2. 重装 Gradle 和 Android SDK（cmdline-tools → platform-tools、android-34、build-tools），
   建议这次装到 `~/android-sdk`（home 目录持久，VM 重置不丢），并更新 `local.properties`。
   网络走环境变量里的 https_proxy。
3. 补完没写完的部分（重点看 `SpectateView.kt` 是否完整：墙体/玩家/子弹/手雷/道具绘制、
   跟随视角、连接状态+FPD 显示）。
4. `./gradlew assembleDebug` 编译通过；跑 `protocol` 模块的 JUnit 测试。
5. 联调：用 `reference/network.py` 起 Python 服务端，在 JVM 测试里完成一次真实 UDP
   握手并收到状态广播（参考已有的 `UdpLoopbackTest.kt` 思路）。
6. 如无意外，打出 debug APK 并注明路径。

## 约束（沿用 TASK_BRIEF.md）
- 只做 Phase 1，不做输入/射击/双摇杆。
- 阻塞超 3 次重试就停下汇报，不要无限烧 token。
- 完成后中文汇报：补了哪些、验证结果（编译/测试/联调）、APK 位置。
