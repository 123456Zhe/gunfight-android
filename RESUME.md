# 交接：README 所述问题已全部修复，待真机验证

## 当前状态（本仓库 main）

- 上次评审列出的 P0/P1/P2/P3 问题已全部处理，见下表；
- 验证方式（本机联网受限，Gradle 发行版拉不下来，因此用无 Gradle 路径验证）：
  - `protocol` 层 JVM 单测 31 项全过（含对着真实 Python `network.py` 的握手与拾取/推门互操作测试）；
  - `build.sh` 全链路跑通，产出 `dist/GunfightSpectate.apk`（~720KB，versionCode 2 / versionName 0.2-phase2）；
  - `./gradlew` 的 wrapper 已补上（jar + properties + gradlew），但本机无法下载 Gradle 8.7 发行版，
    该路径未在本机实测，首次跑要留意。
- 已推送到 GitHub `main`（`f1c4f7b` / `c98a551` / `655f5f6` 三个提交，远端 SHA 与本地完全一致）。
  本机 git-over-https 不稳：github.com 直连被 TLS 打断，本地代理时通时断。代理可用时
  `git -c http.proxy=http://127.0.0.1:7890 push origin main`；代理挂了但 api.github.com 通时，
  用 `~/toolchain/gh-api-push.py <sha> main`（走 REST API 重建同样的 tree/commit，因此远端 SHA
  与本地相同，然后快进 ref）。
- CI workflow（`.github/workflows/ci.yml`）写在本地但**尚未提交**：当前 gh token 缺 `workflow` scope，
  GitHub 拒绝推送 workflow 文件。给它加上 `workflow` scope（或换一个有 scope 的凭据）后即可一起提交。
- Gitea（68.64.177.154:3002）本次不可达（连接被拒绝），未做镜像推送。

## 已修复清单

| 级别 | 问题 | 处理 |
|---|---|---|
| P0 | 从不发 `item_pickup`，手雷/血包/护甲永远拿不到 | `Messages.buildItemPickup` + `GameClient.sendItemPickup` + GameView 走动自动拾取（35px 内最近道具，1.5s 未确认则退避重试） |
| P0 | 断线玩家不清理（幽灵） | `GameSnapshot.playerLastSeen` + `pruneStalePlayers`，2s 未出现在广播中即移除；本机玩家永不移除 |
| P1 | gradle.properties 提交了沙箱代理凭据 | 已删除（凭据仍在 git 历史里，**需要轮换**）；代理配置改放 `~/.gradle/gradle.properties` |
| P1 | manifest `package` 与 AGP 8 `namespace` 冲突 | manifest 去掉 `package`，`build.sh` 里为 aapt2 临时注入 |
| P1 | 没有 gradle wrapper，README 的 `./gradlew` 不成立 | 补 `gradlew` / `gradle/wrapper/*`；README 说明两条构建路径 |
| P1 | `build.sh` 路径写死、无版本号 | 改成基于脚本目录 + 环境变量（ANDROID_HOME/KOTLINC/JAVA_HOME/OUT_APK/VERSION_*），支持 kotlinc 或 kotlin-compiler-embeddable |
| P1 | 官方互操作测试在干净 clone 上必挂 | 改 `org.junit.Assume` 跳过 + `GUNFIGHT_REFERENCE_NETWORK` 指定参考实现；helper 端口改为系统分配 |
| P2 | 无视野裁剪（等同全图透视） | 新增 `Vision.kt`（FOV + 线段/矩形 + 线段/门板 OBB，逐行对齐 Python utils/map），敌人与道具都按可见性裁剪 |
| P2 | 门 version 用全局计数器 | 改为按门计数并从广播 `state.version` 播种（`sendDoorUpdate(id, progress, serverVersion)`） |
| P2 | 表现层薄 | 远端玩家位置/角度插值、子弹按 BULLET_SPEED 外推、命中伤害数字、手雷爆炸圈、复活倒计时、聊天/系统消息栏、击杀列表上限 40 |
| P2 | 超时/被踢没有 UI 反馈 | `GameClient.Listener.onTimeout/onKicked` + MainActivity 回连接界面并显示原因 |
| P3 | 击杀列表无限增长 | 上限 40（聊天 20、爆炸 12） |
| P3 | Json 大 double 被转成整数 | 只有 Int/Long 走整数分支 |
| P3 | `Handshake.state` 非 volatile | 加 `@Volatile` |
| P3 | 默认端口/数值硬编码 | 默认端口、FOV、弹匣、换弹/射速、拾取范围、玩家速度全部读 assets/settings.json |
| P3 | 无 LICENSE | 补 GPL-3.0（与主仓一致） |
| P3 | RESUME 泄露云服地址/SSH | 已移除，运维细节不进公开仓库 |

## 追加一批（近战 / 聊天 / 队伍）

| 项 | 处理 |
|---|---|
| 近战 | 点按（松手判定）出轻击；按住 ≥350ms 只出重击（蓄力环提示，重击冷却未就绪时显示"冷却"、松手退回轻击）；新增 buildMeleeAttack + GameClient.sendMelee + GameView "刀" 按钮：本地按 settings 的 melee.range/angle + 视线预筛 targets，服务端再校验冷却/距离/角度/视线；player_update 现在上报 melee_attacking/melee_direction/weapon_type=melee，挥砍画弧线 |
| 聊天/命令 | 新增 buildChatMessage + GameClient.sendChat；MainActivity 用 FrameLayout 叠一个输入框，点 HUD "聊" 唤出，.kill/.addai/.team/.help 等命令走同一通道 |
| 队伍 | PlayerState.teamId；队友无视 FOV 裁剪、按队伍配色，HUD 显示 "队伍 N" |

互操作测试同步扩展：world helper 里放一个假 AI 玩家（距出生点 30px），Kotlin 客户端发 melee_attack 后
断言服务端把它打到 60 血（settings 的 40 伤害）并回 hit 事件；chat_message 能收到自己的广播。
现在共 35 项测试全过。

## 仍需注意

- `gradle.properties` 里的旧代理密码已随 git 历史公开，**应尽快作废/更换**；
- `ReferenceServerTest` / `PickupInteropTest` 依赖外部参考实现，默认跳过属预期行为；
- 近战（melee）与聊天输入仍未实现：协议支持但客户端没有对应输入；
- 队伍（team）未实现，因此"队友始终可见"这条视野规则在本客户端不会触发；
- 本机 `~/zd-2d-gunfight/settings.json` 未提交地把端口改成了 5556，而仓库内 settings.json 是 5555；
  连本地服时在客户端里手填端口。
