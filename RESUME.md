# 交接（2026-10-08 20:15）：Phase 2 可玩版已推 GitHub，待用户真机验证

## 当前状态
- 仓库：`https://github.com/123456Zhe/gunfight-android`（公开），main 最新提交 `8962269`
  （`init: Gunfight Android Kotlin client (Phase 2 playable)`），32 个文件。
- 本地工程 `~/workspace/gunfight-android/`，分支 main 跟踪 origin/main。
- APK（Phase 2 可玩版）：`~/workspace/goals/zd-2d-gunfight-optimization-and-android-client/files/GunfightSpectate.apk`
  （2026-10-08 20:00 构建，~709KB，包名 com.gunfight.client，minSdk26/targetSdk34，debug 签名）。
- **待用户真机验证**（用户已收到最新包，未回复）：左摇杆移动时角色是否跟着走、右摇杆推出后是否能看到子弹、
  碰墙/过门是否正常。

## 已实现（Phase 2，GameView 替代观战视图）
- 横屏锁屏（manifest landscape），相机跟随 handshake.clientId 对应的本机玩家。
- 左摇杆移动、右摇杆瞄准（推出一定距离连续开火）、右上角"雷"按钮投手雷。
- HUD：血量/护甲/弹药/手雷/击杀播报；空弹自动换弹；阵亡显示等待复活。
- 客户端本地模拟移动（GameMap.kt 确定性复刻 Python grid3x3：28 墙/12 门，前六个墙坐标与 Python 一致；
  门板 OBB 圆碰撞），20Hz 上报 player_update；开火走 request_bullet（自增 seq 去重）；手雷走 request_grenade；
  碰门自动发 door_update。
- **重要渲染修正**（2026-10-08）：本机玩家必须画在本地模拟位置 myX/myY（之前画服务端旧位置，
  相机又跟本地走 → 看起来"只能移动视角"）。其他玩家用服务端状态。

## 服务端协议硬约束（实测结论，不要违反）
- 服务端是权威的：客户端第一包直接跳到任意坐标会被判瞬移打回，必须从服务端出生点开始渐进移动
  （已验证：出生点 [841,395]，20 次 ×15px 上报后服务端广播坐标 MATCH）。
- `player_update` 必须 ≤1200B（服务端 UDP_SAFE_PAYLOAD 拆包，commit aaa7d4b）。
- 云服：`root@68.64.177.154`，`/opt/gunfight-server/`，systemd `gunfight-server`，
  UDP 0.0.0.0:5555，服务名 `Gunfight云服`。
  SSH 必须同时加 `-i /home/hatch/.ssh/id_ed25519 -o UserKnownHostsFile=/home/hatch/.ssh/known_hosts`。
  云服代码不是 git clone，改主仓后需 scp 部署。

## 构建链（Gradle 在 hatch 沙盒跑不起来，别再试）
- Gradle daemon 的 socket IPC 被沙盒掐掉，--no-daemon 也一样。走手动工具链：
  `aapt2 → kotlinc → d8 → zipalign → apksigner`，脚本 `build.sh` 一键执行。
- kotlinc 1.9.24 在 `~/kotlin-compiler/`，Android SDK 在 `~/android-sdk/`，
  JDK 17 在 `~/jdk17/`（均在 home 下持久）。
- 沙盒禁 UDP，`UdpLoopbackTest` 本地跑不了（跳过即可）。

## 约束（用户拍板，长期有效）
- Kotlin 原生客户端，复用 UDP 协议，不要 WebView 套壳；画面几何级一致即可。
- GitHub 巡检发现问题可以汇报，但改代码前必须先征得用户确认。
- 工作日（法定节假日除外）8:00–18:00 用户在学校，不发通知类消息，18:30 后再发。
- TASK_BRIEF.md 是 Phase 1 原始任务书（历史记录，内容已过期：当时要求只做观战不做输入，
  实际 Phase 2 已全做完），仅保留备查，不要按它回退功能。
