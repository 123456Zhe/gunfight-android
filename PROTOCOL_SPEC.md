# zd-2d-gunfight 安卓原生客户端 · 网络协议规格

> 依据 `123456Zhe/zd-2d-gunfight` 仓库 `network.py`（3074 行，含 2026-10-07 安全加固）逐行整理。
> 适用服务端版本：commit `239cf29`（2026-10-07）及之后。
> 设计原则：**服务端权威**——伤害、弹药、复活位置、拾取效果全部由服务端结算，客户端是"渲染器 + 输入采集器"，不做任何权威判定。
> 画面目标为几何级一致（位置/尺寸/颜色/布局相同），不要求像素级一致。

---

## 1. 传输层

| 项目 | 说明 |
|---|---|
| 协议 | UDP / IPv4（`AF_INET, SOCK_DGRAM`），无 TCP、无 TLS |
| 默认端口 | `5555`（`settings.json → network.server_port`） |
| 消息编码 | JSON 文本，UTF-8。`json.dumps(data).encode()`；注意 Python 默认 `ensure_ascii=True`，中文会被转义为 `\uXXXX`，解析器必须能处理 |
| 包边界 | 每个 UDP 数据报 = 一条完整 JSON 消息，无分片/组包机制 |
| 大小端 | 不适用（文本协议） |
| 接收上限 | `BUFFER_SIZE = 65536`（`network.buffer_size`）。超限包被 `recvfrom` **静默截断**，无报错（已知坑） |
| socket 超时 | 服务端/客户端 `settimeout(1.0)` |

**通用信封**（除 `server_probe` 外所有消息）：

```json
{ "type": "<消息类型>", "data": { ... } }
```

`type` 为字符串，`data` 为对象（部分消息无 `data` 键）。非法 JSON、非 dict、缺 `type` 的包直接丢弃。

---

## 2. 连接生命周期

### 2.1 局域网发现（server_probe）

- 客户端向目标 IP 的 `5555` 端口发送**纯文本**（非 JSON）：`server_probe`
- 服务端回复纯文本：`server_info:<JSON>`，JSON 内容：
  ```json
  { "id": "<uuid>", "name": "服务器名", "players": 3, "max_players": 10, "version": "1.0" }
  ```
- 客户端用 `id`（uuid）去重（同一服务器可能有多个 IP 可达）
- Python 客户端实现是逐 IP 扫描子网（`ThreadPoolExecutor`），安卓端可照做或改用 UDP 广播（服务端同样会响应，未确认广播是否被防火墙拦截）

### 2.2 连接握手

1. 客户端发送：`{"type": "connect_request", "player_name": "<玩家名>"}`（服务端也兼容纯文本 `"connect_request"`）
2. 服务端：
   - 房间满（`len(players) >= MAX_PLAYERS=10`）→ 回 `{"type": "room_full"}`（无 data），拒绝
   - 分配 player_id：优先取回收池 `recycled_ids` 中的最小值，否则 `next_new_id` 递增（服务端自己固定为 **1**）
   - 记录 `clients[UDP源地址] = player_id`，`client_last_seen[地址] = now`
   - 初始化玩家数据（出生点 `get_safe_spawn_pos()`，出生保护 `protection_end = now + RESPAWN_PROTECTION`）
   - 回 `{"type": "connect_response", "client_id": <id>, "server_name": "<名>", "server_time": <float>}`
   - 依次向新客户端单播：`init_players`（全量玩家）、逐个 `door_update`（全部门状态）、`item_update`（全量道具）、`chat_history`（最近聊天）
   - 广播系统聊天消息 `"<玩家名> 加入了游戏"`
3. 客户端收到 `connect_response` 即视为连接成功（`CONNECTION_TIMEOUT = 10.0s` 内无响应则报"连接超时"）

### 2.3 身份绑定（安全加固后，必读）

- 服务端**以 UDP 源地址绑定身份**：`sender_id = clients.get(addr)`。所有 C→S 游戏消息中的 `player_id`/`owner`/`attacker_id` 字段**一律被来源地址覆盖**，包内自称的 ID 无效。
- **未知地址门禁**：从未 `connect` 过的地址发来的任何游戏消息（`heartbeat` 除外）直接丢弃。`server_probe` / `connect_request` 走独立分支不受影响。
- 客户端**只接受来自已连接服务器地址** `(server_ip, 5555)` 的包，局域网内其他主机注入的状态包被丢弃。

### 2.4 保活与超时

| 方向 | 行为 |
|---|---|
| C→S 心跳 | `{"type":"heartbeat","data":{"player_id":<id>,"timestamp":<float>}}`，每 `HEARTBEAT_INTERVAL = 1.0s` 一次 |
| S→C 心跳回应 | `{"type":"heartbeat_response","data":{"timestamp":<float>}}` |
| 服务端清理 | 每 2s 检查，`now - client_last_seen > CLIENT_TIMEOUT = 5.0s` → 踢出：回收 player_id、删玩家数据、清战斗校验状态、广播系统消息 `"<名> 离开了游戏"` |
| 客户端掉线判定 | `now - last_server_response > CLIENT_TIMEOUT` → `connected=False`，停止 |
| 主动断开 | **无显式 disconnect 消息**，靠超时 |

### 2.5 管理员踢出

- 服务端单播 `{"type":"kick","data":{"reason":"<原因>"}}` 给目标地址，随后清理其数据
- 客户端收到后断开并显示原因

---

## 3. C→S 消息全表

> 校验列 = 服务端在 `_handle_*` 中的权威校验。校验失败 = 静默丢弃（无错误回包）。

### 3.1 player_update（位置/状态上报）

- **方向**：C→S（客户端每帧发送，约 60Hz）；S→C 为 20Hz 广播（见 4.2）
- **格式**：`{"type":"player_update","data":{"<pid字符串>":{...}}}`（只发自己这一项）
- **字段**（player.py `Player.update` 构造）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `pos` | [float, float] | 位置（像素，y-down） |
| `angle` | float | 朝向（度，见第 5 节） |
| `health` | int | 生命（上报值被服务端覆盖，仅占位） |
| `ammo` | int | 弹药（上报值被服务端覆盖） |
| `armor` | int | 护甲（上报值被服务端覆盖） |
| `is_reloading` | bool | 换弹中 |
| `shooting` | bool | 正在射击 |
| `is_dead` / `death_time` / `respawn_time` / `is_respawning` | bool/float | 死亡状态（上报值被服务端覆盖） |
| `name` | string | 玩家名（允许更新） |
| `melee_attacking` | bool | 近战攻击中 |
| `melee_direction` | float | 近战攻击方向（度） |
| `weapon_type` | string | `"gun"` / `"melee"` |
| `is_aiming` | bool | 瞄准中（右键） |
| `is_making_sound` / `sound_volume` | bool / float | 是否发出声音/音量（AI 感知用） |
| `speed_boost_end_time` / `damage_boost_end_time` | float | buff 结束时间戳（上报值被服务端覆盖） |
| `grenades` | int | 手雷数（上报值被服务端覆盖） |

- **服务端校验**：只接受 `pid == sender_id` 的条目（防冒用他人）；`pos` 钳制到 `[0, ROOM_SIZE*3]`；**速度校验**：位移/dt > `PLAYER_SPEED*1.5*1.5` 视为瞬移作弊，本次位置被忽略；`health/is_dead/death_time/respawn_time/is_respawning/armor/speed_boost_end_time/damage_boost_end_time/grenades/ammo` 恢复服务端权威值。

### 3.2 request_bullet（开火请求）

- **格式**：`{"type":"request_bullet","data":{"pos":[x,y],"dir":[dx,dy],"owner":<pid>,"seq":<int>}}`
- `pos`：子弹出生点（枪口）；`dir`：方向向量（可不归一化，服务端会归一化）；`seq`：客户端自增序号（`_next_fire_seq`，从 1 开始）
- **频率**：每次扣扳机一次；客户端对未确认请求做**重传**：0.1s 后重发，最多 2 次，之后丢弃
- **服务端校验**（`_handle_bullet_request`）：`owner = sender_id` 强制覆盖；玩家必须存活；**射速**：`now - 上次开火 < BULLET_COOLDOWN*0.9` 拒绝；**弹药权威**：`ammo<=0` 时必须经过 `RELOAD_TIME` 换弹（服务端计时），否则拒绝；**出生点**：距玩家位置 ≤ `PLAYER_RADIUS + BULLET_RADIUS + PLAYER_SPEED*0.25`；**方向**：非零向量；**seq 去重**：每个玩家保留最近 256 个序号，已见序号直接丢弃（防重传产生重复子弹）
- 通过后服务端：`ammo -= 1`，生成子弹 `{id, pos, dir, owner, time, last_update}`，`id = next_bullet_id` 递增

### 3.3 melee_attack（近战请求）

- **格式**：`{"type":"melee_attack","data":{"attacker_id":<pid>,"direction":<度>,"targets":[pid...],"is_heavy":<bool>}}`
- `targets` 为客户端候选目标列表（服务端**全部重算**，上限取前 20 个）
- **服务端校验**：`attacker_id = sender_id`；存活；冷却 `MELEE_COOLDOWN` / `HEAVY_MELEE_COOLDOWN`（*0.9 容差）；对每个 target：距离 ≤ `melee_range + PLAYER_RADIUS`，角度差 ≤ `melee_angle/2 + 15°`，`has_line_of_sight` 通过；伤害 `MELEE_DAMAGE`（重击 ×1.5）

### 3.4 request_grenade（投掷手雷）

- **格式**：`{"type":"request_grenade","data":{"pos":[x,y],"dir":[dx,dy],"owner":<pid>,"seq":<int>}}`
- **频率**：每次投掷一次；重传机制同开火（间隔 0.12s，最多 2 次）
- **服务端校验**：`owner = sender_id`；存活；seq 去重；**手雷数权威** `grenades > 0`，通过后 `-1`
- 手雷物理（服务端模拟）：直线运动 + 摩擦衰减 + 撞墙反弹，引信到时爆炸，范围伤害按距离线性衰减（`damage * max(0.1, 1-dist/radius)`），需视线可达，队友免伤（受 `friendly_fire` 开关控制）

### 3.5 respawn（复活请求）

- **格式**：`{"type":"respawn","data":{"player_id":<pid>,"pos":[x,y]}}`
- **服务端校验**：`player_id = sender_id`；**必须已死亡**（活人发包直接丢弃，防刷血）；**`pos` 被服务端 `get_safe_spawn_pos()` 覆盖**，客户端上报坐标无效；重置战斗校验状态（换弹计时、速度校验基线）
- 另：服务端在 `respawn_time` 到达时**自动复活**（`check_player_respawns`，每帧检查），客户端通常不需要主动发此消息

### 3.6 item_pickup（拾取道具）

- **格式**：`{"type":"item_pickup","data":{"player_id":<pid>,"item_id":<int>}}`
- **服务端校验**：`player_id = sender_id`；道具存在且 `is_active`；`can_pickup` 冷却通过；**距离** ≤ `ITEMS_PICKUP_RANGE`（防隔空拾取）；**效果由服务端 `item.get_effect()` 查表生成**，客户端不上报 effect
- 通过后服务端广播 `item_pickup`（见 4.6）+ `item_update`

### 3.7 door_update（门状态同步）

- **格式**：`{"type":"door_update","data":{"door_id":<int>,"state":{...}}}`
- `door_id` = `game_map.doors` 列表下标（int）
- `state`（`Door.get_state()`）：
  ```json
  {"is_open":false,"is_opening":false,"is_closing":false,
   "animation_progress":0.0,"swing_velocity":0.0,"version":<int>}
  ```
  `animation_progress` ∈ [-1.0, 1.0]（门板开度，负值表示反向开）；`swing_velocity` 为角速度；`version` 为状态版本号（单调递增）
- **频率**：手动推门时每 0.05s（`DOOR_SYNC_INTERVAL`）同步一次，松手时强制同步一次
- **服务端校验**：`door_id` 有效；**发起者必须在门附近**（距门中心 ≤ `PLAYER_RADIUS*3 + max(宽,高)/2 + 20`），防隔墙刷门；通过后转发给所有客户端

### 3.8 chat_message（聊天）

- **格式**：`{"type":"chat_message","data":{"player_id":<pid>,"player_name":"<名>","message":"<内容>","timestamp":<float>,"is_team_chat":<bool>}}`
- **服务端处理**：截断 `MAX_CHAT_LENGTH = 50` 字符；**0.5s 限频**；相同内容 2s 去重；`player_id` 被 `sender_id` 覆盖
- 以 `.` 开头的消息转为**服务端命令**（见 3.9）；`is_team_chat=true` 时只转发给同队成员（`team_id` 随 `player_update` 同步，无独立团队消息）
- 击杀/加入/离开等系统消息以 `player_id = 0, player_name = "[系统]"` 广播

### 3.9 游戏内 `.` 命令

聊天框输入 `.xxx` 即执行。权限：`is_admin = (player_id == 1)`（服务端玩家）。

| 命令 | 权限 | 说明 |
|---|---|---|
| `.kick <ID> [原因]` | admin | 踢出玩家（发 `kick` 消息并清理） |
| `.list` / `.players` | 所有人 | 在线玩家列表 |
| `.broadcast <消息>` | admin | 系统公告 |
| `.heal <ID\|all> [血量]` | admin | 回血（上限 100） |
| `.respawn <ID\|all>` | admin | 复活死亡玩家 |
| `.tp <ID\|all> <x> <y>` | admin | 传送（坐标钳制到地图内） |
| `.kill` | 所有人 | 自杀（1000 点伤害，自己为攻击者） |
| `.weapon` | admin | 切换自己武器 gun/melee |
| `.ammo` | admin | 补满弹药 |
| `.speed [倍率0.5-2.0] [秒数1-30]` | admin | 临时移速 buff |
| `.addai [easy\|normal\|hard] [aggressive\|defensive\|tactical\|stealthy\|team\|random]` | admin | 添加 AI 玩家 |
| `.removeai <ID\|all>` | admin | 移除 AI |
| `.listai` | 所有人 | AI 列表 |
| `.createteam [名]` / `.jointeam <ID>` / `.leaveteam` | 所有人 | 团队管理 |
| `.team [add\|join\|leave\|delete\|list]` / `.teaminfo` / `.listteams` | 所有人 | 团队信息（delete 需队长或 admin） |
| `.invite <ID>` / `.teaminvite` | 队长 | 邀请入队 |
| `.teamchat` / `.tc` / `.all` / `.global` | 所有人 | 切换队伍/全局聊天模式 |
| `.help` | 所有人 | 帮助（admin 看到完整版） |

### 3.10 heartbeat

见 2.4。`{"type":"heartbeat","data":{"player_id":<pid>,"timestamp":<float>}}`，1Hz。

### 3.11 客户端绝不能发的消息

- `hit_damage`：**任何来源直接丢弃**（伤害只由服务端 `_simulate_bullets` / `_handle_melee_attack` / 手雷爆炸内部调用 `_handle_damage` 结算，从不经网络）
- 不要伪造 `player_id`/`owner`/`attacker_id`：一律以 UDP 源地址为准，伪造无效

---

## 4. S→C 消息全表

### 4.1 连接相关

| type | data | 说明 |
|---|---|---|
| `connect_response` | `{client_id, server_name, server_time}` | 连接成功，`client_id` 为分配的 player_id |
| `room_full` | 无 | 房间满（10 人），拒绝连接 |
| `kick` | `{reason}` | 被踢出，客户端应断开并提示 |
| `heartbeat_response` | `{timestamp}` | 心跳回应，客户端更新 `last_server_response` |

### 4.2 player_update（20Hz 全量广播）

`{"type":"player_update","data":{"<pid字符串>":{...}}}` —— **注意 key 是字符串**（JSON 对象键）。

字段与 3.1 相同，但此处为**服务端权威值**：`health/ammo/armor/is_dead/death_time/respawn_time/is_respawning/grenades/speed_boost_end_time/damage_boost_end_time` 以此为准覆盖本地。`team_id` 也随此同步（无独立团队消息）。AI 玩家额外可能带 `is_walking/is_making_sound/sound_volume`。

服务端每 0.05s（`last_broadcast`，20Hz）广播一次。

### 4.3 bullets_update（20Hz）

`{"type":"bullets_update","data":[...]}`，数组元素：

```json
{"id":<int>,"pos":[x,y],"dir":[dx,dy],"owner":<pid>,"time":<float>,"last_update":<float>}
```

- `id`：服务端 `next_bullet_id` 递增分配；`dir` 为归一化方向；`time` 为生成时间戳
- 子弹 3.0s 过期；客户端收到后**整体替换**本地子弹列表（`_update_bullets`）
- 子弹模拟在服务端每帧推进：`pos += dir * BULLET_SPEED * dt`，细分步进防穿透（步长 ≤8px）；撞墙/撞门/出界/命中即销毁

### 4.4 grenade_update（20Hz）

`{"type":"grenade_update","data":[...]}`，数组元素（`ThrownGrenade.get_state()`）：

```json
{"id":<int>,"pos":[x,y],"velocity":[vx,vy],"owner_id":<pid>,
 "spawn_time":<float>,"exploded":<bool>,
 "explosion_pos":[x,y] | null}
```

- 客户端用 `id` 匹配本地对象做状态校正（`apply_state`）；服务端列表中消失的手雷视为**已爆炸**，客户端应播放爆炸特效（Python 客户端以此设置 `last_grenade_explosion = {pos, time}`）

### 4.5 door_update（事件驱动）

格式同 3.7。服务端收到合法更新后转发给所有客户端；新连接时逐个单播全部门状态。

### 4.6 item_update / item_pickup

- `item_update`：20Hz 广播，`{"type":"item_update","data":{"items":[...]}}`，元素：
  ```json
  {"id":<int>,"type":"HEALTH_PACK|AMMO_BOX|ARMOR|SPEED_BOOST|DAMAGE_BOOST|GRENADE",
   "pos":[x,y],"is_active":<bool>,"respawn_time_remaining":<float>}
  ```
- `item_pickup`：拾取成功时广播，`{"type":"item_pickup","data":{"player_id":<pid>,"item_id":<int>,"effect":{...}}}`，`effect` 为服务端生成的道具效果（客户端直接 `apply_item_effect(effect)`）

### 4.7 chat_message / chat_history

- `chat_message`：`{"type":"chat_message","data":{"player_id":<pid>,"player_name":"<名>","message":"<内容>","timestamp":<float>,"is_team_chat":<bool>}}`（广播时通常不带 `is_team_chat`，见 `broadcast_chat_message`）
- `chat_history`：新连接时单播，`{"type":"chat_history","data":{"messages":[{player_id,player_name,message,timestamp}...]}}`（最近 `MAX_CHAT_MESSAGES=10` 条）

### 4.8 combat_feedback（战斗事件：hitmarker / kill feed 数据源）

`{"type":"combat_feedback","data":{...}}`：

```json
{"kind":"hit|kill","attacker_id":<pid>,"target_id":<pid>,
 "attacker_name":"<名>","target_name":"<名>",
 "damage":<float>,"damage_type":"bullet|melee|grenade|suicide",
 "target_pos":[x,y] | null,"time":<float>}
```

- **分发规则**：`kind=kill` → 广播给**所有**客户端（kill feed 数据源）；`kind=hit` → 仅单播给 **attacker 所在地址**（hitmarker/伤害数字数据源）
- 击杀同时还会有一条系统聊天消息 `"<A> 击杀了 <B>！"`（`player_id=0`）
- 出生保护期内（`protection_end`）的目标免疫伤害，无事件
- 同一 attacker/target/type 组合 0.1s 内去重

### 4.9 respawn（复活广播）

`{"type":"respawn","data":{"player_id":<pid>,"pos":[x,y],"health":100}}` —— 服务端自动复活时广播；客户端用此更新玩家位置与状态（含 `protection_end = now + RESPAWN_PROTECTION` 出生保护）。

---

## 5. 坐标与单位约定

| 项目 | 约定 |
|---|---|
| 坐标系 | 像素，**y-down**（屏幕向下为 +y，与 Android Canvas 一致，无需翻转） |
| 地图尺寸 | `ROOM_SIZE × 3 = 1800 × 1800` px（`ROOM_SIZE=600`） |
| 角度 | **度**（float）。`0°` = +x（东/右），`90°` = 上（北/屏幕上方），`180°` = 西，`-90°` = 下；**逆时针为正**（屏幕视觉）。换算公式：`angle = degrees(atan2(-dy, dx))`（y-down 坐标系） |
| 时间 | 秒，float（`time.time()` epoch 秒）；持续时间均为秒 |
| 速度 | px/s |
| 玩家碰撞 | 半径 `PLAYER_RADIUS = 20` 的圆（子弹命中判定用 `PLAYER_RADIUS×2` 的 AABB 近似） |
| 子弹 | 半径 `BULLET_RADIUS`，速度 `BULLET_SPEED` px/s |

---

## 6. 常量来源（settings.json）

安卓客户端应**直接复用同一份 `settings.json`**（仓库已保证"唯一数据源"，`build.py` 会打包进发行版）。关键键名：

| 键 | 默认值 | 用途 |
|---|---|---|
| `network.server_port` | 5555 | UDP 端口 |
| `network.max_players` | 10 | 房间人数上限 |
| `network.buffer_size` | 65536 | 接收缓冲 |
| `network.heartbeat_interval` | 1.0 | 心跳间隔（秒） |
| `network.client_timeout` | 5.0 | 超时踢出（秒） |
| `network.connection_timeout` | 10.0 | 连接等待（秒） |
| `game.player_speed` | 300 | 移速 px/s（速度校验上限 = ×1.5×1.5） |
| `game.player_radius` | 20 | 玩家半径 |
| `game.bullet_speed` | 800 | 子弹速度 |
| `game.bullet_cooldown` | 0.15 | 射速间隔（服务端校验 ×0.9 容差） |
| `game.bullet_damage` | 20 | 子弹伤害 |
| `game.magazine_size` | 30 | 弹匣 |
| `game.reload_time` | 2.0 | 换弹时间 |
| `game.respawn_time` | 3.0 | 复活等待（`game_rules['respawn_time']` 可覆盖） |
| `game.respawn_protection` | 2.0 | 出生保护秒数（无敌） |
| `map.room_size` | 600 | 房间边长 |
| `map.door_animation_speed` | 2.0 | 自动开关门动画速度 |
| `map.door_push_accel` | 8.0 | 推门角加速度 |
| `map.door_damping` | 2.0 | 门摆动阻尼 |
| `map.door_max_speed` | 6.0 | 门摆动角速度上限 |
| `aiming.sensitivity` | 1 | 鼠标灵敏度（安卓触屏映射时参考） |
| `aiming.turn_speed` | 540 | 瞄准转向速度上限（度/秒） |
| `chat.max_length` | 50 | 聊天字数上限 |
| `chat.max_messages` | 10 | 历史保留条数 |
| `items.types.grenade.*` | — | 手雷参数（初速/引信/摩擦等） |

> 读取规则与服务端一致：`settings.json` > `config.py` 默认值 > 硬编码回退。安卓端建议实现同样的三级回退。

---

## 7. 客户端实现注意事项

### 7.1 权威边界（红线）
- `hit_damage` **永远不要发**（服务端直接丢弃）。
- 不要在包里伪造 `player_id` / `owner` / `attacker_id`——服务端以 UDP 源地址为准，伪造无效。
- 以下字段客户端只做**渲染**，以上报为准：`health`、`ammo`、`armor`、`grenades`、`is_dead`、`death_time`、`respawn_time`、复活 `pos`、拾取 `effect`、伤害数值。
- 开火/近战/投弹/拾取/复活/推门：客户端只发**请求**，以服务端是否实际生效（后续广播状态）为准。

### 7.2 本地预测建议
- **移动**：本地立即响应输入（WASD/虚拟摇杆），每帧发 `player_update`；收到服务端 20Hz 广播后做位置校正（建议插值平滑，不要瞬移）。
- **开火**：本地可立即播枪口特效+音效，但**弹道以服务端 `bullets_update` 为准**（服务端有射速/弹药校验，非法开火会被静默拒绝）。
- **子弹渲染**：`bullets_update` 20Hz，中间帧可按 `dir * BULLET_SPEED` 外推。
- **门**：本地推门有物理反馈，`door_update` 到达后以 `version` 大者为准（`state_version` 单调递增）。

### 7.3 重传与可靠性
- `request_bullet` / `request_grenade` 带 `seq`，客户端 0.1s/0.12s 后重发，最多 2 次；服务端按序号去重，**不会**产生重复子弹。
- 超过重传次数后请求被静默丢弃——开火可能"没反应"，属协议已知行为（L9），客户端可给玩家一个极小的本地反馈以掩盖。
- `player_update` / 心跳无重传，靠高频发送掩盖丢包。

### 7.4 断线与重连
- 无重连协议：掉线后必须重新走 `connect_request` 完整握手，会拿到**新 player_id**（旧 ID 被回收）。
- NAT 环境下 UDP 源端口变化会被服务端视为**未知地址**，消息被丢弃——等同于掉线，需重连。

### 7.5 已知坑
1. `BUFFER_SIZE` 截断**无报错**：包超过 65536 字节会被静默截断导致 JSON 解析失败（玩家多、道具多时 `player_update` 全量广播可能变大，注意监控包大小）。
2. JSON 对象 key 的 int 会变 string：`player_update` 的 pid key、`doors` 字典 key，解析时要 `int()` 转换。
3. Python `json.dumps` 默认转义非 ASCII：`ensure_ascii=True`，中文聊天是 `\uXXXX` 形式，Kotlin 的 `JSONObject`/`kotlinx.serialization` 正常解析即可。
4. 客户端**只收**服务器地址的包：安卓端 `recvfrom` 后必须校验来源 `(ip, 5555)`。
5. 服务端广播 20Hz，渲染侧建议做插值，否则 10 人同屏会有肉眼可见的顿挫。
6. `melee_direction` / `direction` 字段单位是度，遵循第 5 节角度约定。
7. 观战模式建议：先实现**只读客户端**（连上只收 `player_update`/`bullets_update`/grenade/door/item 并渲染），验证协议解析与渲染管线，再加输入。这是风险最低的验证路径。

---

## 8. 未覆盖 / 需运行时确认清单

- [x] ~~`game.respawn_time` / `game.respawn_protection` 键名~~ → 已确认为 `game.respawn_time`（3.0s）、`game.respawn_protection`（2.0s）
- [ ] `.speed` 命令的 `speed_boost_multiplier` 只存在玩家对象属性上，**未进网络玩家字典**——远程客户端可能看不到他人的加速状态（需运行时抓包确认）
- [ ] `melee_attacking` / `melee_direction` 在远程客户端的渲染语义（Python 客户端用它触发近战挥砍动画，`weapons.py:161` 按 `attack_direction` 画弧线）
- [ ] `is_making_sound` / `sound_volume` 的具体计算方式（AI 感知用，安卓端如需"被听见"指示器需复刻）
- [ ] `item_update` 中 `respawn_time_remaining` 的语义（道具重生倒计时，客户端是否需要画）
- [ ] `server_probe` 用 UDP 广播地址（如 `255.255.255.255`）是否可达——Python 客户端是逐 IP 单播扫描，未验证广播
- [ ] `connect_request` 纯文本形式的兼容分支是否仍被使用（代码保留，实际客户端已发 JSON 版）
- [ ] 大包场景（10 人 + 大量子弹）下 `player_update` 全量广播的实际字节数（BUFFER_SIZE 风险量化）
- [ ] `door_update` 在 `self.doors` 字典中的 key 类型（int vs str）在多次转发后是否稳定
- [ ] `chat_history` 的 `MAX_CHAT_MESSAGES * 2` 截断逻辑与客户端 `MAX_CHAT_MESSAGES` 显示条数的对应关系
