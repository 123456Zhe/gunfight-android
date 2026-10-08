package com.gunfight.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.gunfight.protocol.*
import kotlin.math.*

/** Phase 2 playable view: twin-stick controls, HUD, camera follows local player. */
class GameView(ctx: Context, val settings: GameSettings) : View(ctx) {

    @Volatile var client: GameClient? = null

    private val gameMap = GameMap()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val path = Path()

    // ---- local player simulation ----
    private var myId: Int = -1
    private var myX = 0.0
    private var myY = 0.0
    private var myAngle = 0.0
    private var initialized = false
    private var wasDead = false
    private var shooting = false
    private var reloading = false
    private var reloadEndMs = 0L
    private var lastFireMs = 0L
    private var lastSentMs = 0L
    private var lastDoorSyncMs = 0L

    // ---- joysticks ----
    private val leftStick = Stick()
    private val rightStick = Stick()
    private val stickRadius = 130f
    private var grenadeBtnX = 0f
    private var grenadeBtnY = 0f
    private val grenadeBtnR = 70f

    private var lastFrameNs = System.nanoTime()
    private var fps = 0.0

    private val palette = intArrayOf(
        Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW,
        Color.rgb(255, 165, 0), Color.rgb(128, 0, 128), Color.CYAN
    )
    private fun playerColor(id: Int): Int =
        if (id == 0) Color.WHITE else palette[Math.floorMod(id, 7)]

    private fun itemColor(type: String): Int = when (type) {
        "HEALTH_PACK" -> Color.GREEN
        "AMMO_BOX" -> Color.YELLOW
        "ARMOR" -> Color.rgb(173, 216, 230)
        "SPEED_BOOST", "DAMAGE_BOOST" -> Color.rgb(128, 0, 128)
        "GRENADE" -> Color.rgb(255, 165, 0)
        else -> Color.WHITE
    }

    // ================= touch =================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i); val y = e.getY(i)
                // grenade button first
                if (hypot(x - grenadeBtnX, y - grenadeBtnY) <= grenadeBtnR * 1.3f) {
                    throwGrenade()
                    return true
                }
                if (x < width / 2 && !leftStick.active) leftStick.start(id, x, y)
                else if (x >= width / 2 && !rightStick.active) rightStick.start(id, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    when (id) {
                        leftStick.pointerId -> leftStick.move(e.getX(i), e.getY(i))
                        rightStick.pointerId -> rightStick.move(e.getX(i), e.getY(i))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == leftStick.pointerId) leftStick.release()
                if (id == rightStick.pointerId) rightStick.release()
            }
        }
        return true
    }

    private fun throwGrenade() {
        val c = client ?: return
        val me = c.snapshot.players[myId] ?: return
        if (me.isDead || me.grenades <= 0) return
        val rad = Math.toRadians(myAngle)
        val dx = cos(rad); val dy = -sin(rad) // game angle -> y-down world
        c.sendGrenade(myX + dx * 30, myY + dy * 30, dx, dy)
    }

    // ================= game loop =================

    override fun onDraw(canvas: Canvas) {
        val nowNs = System.nanoTime()
        val dt = ((nowNs - lastFrameNs) / 1e9).coerceIn(0.0, 0.1)
        lastFrameNs = nowNs
        if (dt > 0) fps = fps * 0.9 + (1.0 / dt) * 0.1
        val nowMs = System.currentTimeMillis()

        val c = client
        if (c == null) {
            postInvalidateOnAnimation()
            return
        }
        if (myId < 0) myId = c.handshake.clientId
        val snap = c.snapshot

        // sync door states from server
        for ((idStr, state) in snap.doors) {
            val id = idStr.toIntOrNull() ?: continue
            val progress = (state["animation_progress"] as? Number)?.toDouble() ?: 0.0
            gameMap.updateDoorState(id, progress)
        }

        val me = if (myId >= 0) snap.players[myId] else null
        if (me != null) {
            if (!initialized || (wasDead && !me.isDead)) {
                myX = me.posX; myY = me.posY; myAngle = me.angle
                initialized = true
            }
            wasDead = me.isDead
        }

        if (initialized && me != null && !me.isDead) {
            updateLocal(dt, nowMs, c, me)
        }

        render(canvas, snap, me)
        postInvalidateOnAnimation()
    }

    private fun updateLocal(dt: Double, nowMs: Long, c: GameClient, me: PlayerState) {
        // --- movement (left stick) ---
        val (mdx, mdy) = leftStick.vec(stickRadius)
        val mLen = hypot(mdx, mdy)
        var wantX = 0.0
        var wantY = 0.0
        if (mLen > 10) {
            val speed = GameMap.PLAYER_SPEED * min(1.0, (mLen / stickRadius).toDouble())
            wantX = (mdx / stickRadius) * speed * dt
            wantY = (mdy / stickRadius) * speed * dt
        }

        // --- aim (right stick) ---
        val (adx, ady) = rightStick.vec(stickRadius)
        val aLen = hypot(adx, ady)
        if (aLen > 10) {
            myAngle = Math.toDegrees(atan2((-ady).toDouble(), adx.toDouble()))
        }

        // --- reload ---
        if (reloading && nowMs >= reloadEndMs) reloading = false
        if (!reloading && me.ammo <= 0 && me.ammo < 30) {
            reloading = true
            reloadEndMs = nowMs + 2000
        }

        // --- move with collision; auto-open doors we're pushing against ---
        if (wantX != 0.0 || wantY != 0.0) {
            val (nx, ny) = gameMap.moveWithCollision(
                myX, myY, wantX, wantY, GameMap.PLAYER_RADIUS
            )
            // blocked? maybe a closed door: try to open it
            if (nx == myX && ny == myY) tryOpenDoor(c, nowMs)
            myX = nx; myY = ny
        }

        // --- fire ---
        shooting = false
        if (aLen > stickRadius * 0.35 && !reloading && me.ammo > 0 && nowMs - lastFireMs >= 150) {
            val dx = (adx / aLen).toDouble()
            val dy = (ady / aLen).toDouble()
            val rad = Math.toRadians(myAngle)
            // muzzle along aim
            val mdx2 = cos(rad); val mdy2 = -sin(rad)
            c.sendFire(myX + mdx2 * 28, myY + mdy2 * 28, dx, dy)
            lastFireMs = nowMs
            shooting = true
        }

        // --- report state 20Hz ---
        if (nowMs - lastSentMs >= 50) {
            c.sendPlayerUpdate(myX, myY, myAngle, shooting, reloading)
            lastSentMs = nowMs
        }
    }

    /** If we're pushing against a closed door, open it away from us. */
    private fun tryOpenDoor(c: GameClient, nowMs: Long) {
        if (nowMs - lastDoorSyncMs < 500) return
        // find a closed door whose panel blocks us
        for (d in gameMap.doors) {
            if (abs(d.progress) >= 0.9) continue
            val cx = d.hingeX // approx: door center
            // use original rect center
            val ox = d.original.x + d.original.w / 2
            val oy = d.original.y + d.original.h / 2
            if (hypot(myX - ox, myY - oy) > 120) continue
            // open away from us: pick sign by free-end distance (mirror of Python open_away_from)
            val rad = Math.toRadians(GameMap.DOOR_OPEN_ANGLE)
            val cosA = cos(rad); val sinA = sin(rad)
            val epx = d.hingeX + (d.baseDirX * cosA - d.baseDirY * sinA) * d.panelLength
            val epy = d.hingeY + (d.baseDirX * sinA + d.baseDirY * cosA) * d.panelLength
            val emx = d.hingeX + (d.baseDirX * cosA + d.baseDirY * sinA) * d.panelLength
            val emy = d.hingeY + (-d.baseDirX * sinA + d.baseDirY * cosA) * d.panelLength
            val distPlus = hypot(myX - epx, myY - epy)
            val distMinus = hypot(myX - emx, myY - emy)
            val target = if (distPlus < distMinus) -1.0 else 1.0
            d.progress = target // snap open locally; server broadcast will confirm
            c.sendDoorUpdate(d.id, target)
            lastDoorSyncMs = nowMs
            break
        }
    }

    // ================= render =================

    private fun render(canvas: Canvas, snap: GameSnapshot, me: PlayerState?) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(settings.color("fog", Color.rgb(20, 20, 20)))

        val mapSize = (GameMap.ROOM_SIZE * 3).toFloat()
        val cx = if (initialized) myX.toFloat() else mapSize / 2
        val cy = if (initialized) myY.toFloat() else mapSize / 2
        val scale = (minOf(w, h) / 900f).coerceIn(0.2f, 3f)

        canvas.save()
        canvas.translate(w / 2, h / 2)
        canvas.scale(scale, scale)
        canvas.translate(-cx, -cy)

        drawMap(canvas, mapSize)
        drawDoors(canvas)
        drawItems(canvas, snap)
        drawGrenades(canvas, snap)
        drawBullets(canvas, snap)
        drawPlayers(canvas, snap)
        canvas.restore()

        drawHud(canvas, w, h, me, snap)
        drawSticks(canvas)
    }

    private fun drawMap(canvas: Canvas, mapSize: Float) {
        paint.style = Paint.Style.FILL
        paint.color = settings.color("vision_ground", Color.rgb(80, 80, 80))
        canvas.drawRect(0f, 0f, mapSize, mapSize, paint)
        paint.color = settings.color("gray", Color.rgb(100, 100, 100))
        for (wall in gameMap.walls) {
            canvas.drawRect(
                wall.x.toFloat(), wall.y.toFloat(),
                (wall.x + wall.w).toFloat(), (wall.y + wall.h).toFloat(), paint
            )
        }
        linePaint.color = Color.WHITE
        canvas.drawRect(0f, 0f, mapSize, mapSize, linePaint)
    }

    private fun drawDoors(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(160, 120, 60)
        for (d in gameMap.doors) {
            val corners = d.corners()
            path.reset()
            path.moveTo(corners[0].first.toFloat(), corners[0].second.toFloat())
            for (i in 1..3) path.lineTo(corners[i].first.toFloat(), corners[i].second.toFloat())
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun drawPlayers(canvas: Canvas, snap: GameSnapshot) {
        val r = settings.playerRadius.toFloat()
        for (p in snap.players.values.sortedBy { it.id }) {
            val isMe = p.id == myId
            // 本地玩家画在本地模拟位置（服务端位置有延迟/可能被拒）
            val x = if (isMe && initialized) myX.toFloat() else p.posX.toFloat()
            val y = if (isMe && initialized) myY.toFloat() else p.posY.toFloat()
            val ang = if (isMe && initialized) myAngle else p.angle
            val col = if (p.isDead) settings.color("dead", Color.GRAY)
            else if (isMe) Color.WHITE else playerColor(p.id)
            paint.style = Paint.Style.FILL
            paint.color = col
            canvas.drawCircle(x, y, r, paint)
            if (isMe) {
                linePaint.color = Color.YELLOW
                linePaint.strokeWidth = 3f
                canvas.drawCircle(x, y, r + 6, linePaint)
                linePaint.strokeWidth = 4f
            }
            val rad = Math.toRadians(ang)
            val dx = cos(rad); val dy = -sin(rad)
            linePaint.color = Color.WHITE
            canvas.drawLine(x, y, (x + dx * (r + 14)).toFloat(), (y + dy * (r + 14)).toFloat(), linePaint)
            val hpFrac = (p.health.coerceIn(0, 100)) / 100f
            paint.color = if (hpFrac > 0.5) Color.GREEN else Color.RED
            canvas.drawRect(x - r, y - r - 18, x - r + 2 * r * hpFrac, y - r - 10, paint)
            paint.color = Color.WHITE
            canvas.drawText(p.name, x - r, y - r - 24, paint)
        }
    }

    private fun drawBullets(canvas: Canvas, snap: GameSnapshot) {
        val r = settings.bulletRadius.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        for (b in snap.bullets) canvas.drawCircle(b.x.toFloat(), b.y.toFloat(), r, paint)
    }

    private fun drawGrenades(canvas: Canvas, snap: GameSnapshot) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(255, 165, 0)
        for (g in snap.grenades) {
            if (!g.exploded) canvas.drawCircle(g.x.toFloat(), g.y.toFloat(), 8f, paint)
        }
    }

    private fun drawItems(canvas: Canvas, snap: GameSnapshot) {
        paint.style = Paint.Style.FILL
        for (it in snap.items.values) {
            if (!it.active) continue
            paint.color = itemColor(it.type)
            canvas.drawRect(it.x.toFloat() - 10, it.y.toFloat() - 10, it.x.toFloat() + 10, it.y.toFloat() + 10, paint)
        }
    }

    private fun drawHud(canvas: Canvas, w: Float, h: Float, me: PlayerState?, snap: GameSnapshot) {
        paint.style = Paint.Style.FILL
        // HP bar
        val hpFrac = ((me?.health ?: 100).coerceIn(0, 100)) / 100f
        paint.color = Color.rgb(60, 60, 60)
        canvas.drawRect(16f, 16f, 316f, 44f, paint)
        paint.color = if (hpFrac > 0.5) Color.GREEN else if (hpFrac > 0.25) Color.rgb(255, 165, 0) else Color.RED
        canvas.drawRect(16f, 16f, 16f + 300f * hpFrac, 44f, paint)
        paint.color = Color.WHITE
        canvas.drawText("${me?.health ?: 100}", 330f, 42f, paint)
        // armor bar
        val armor = me?.armor ?: 0
        paint.color = Color.rgb(60, 60, 60)
        canvas.drawRect(16f, 52f, 316f, 72f, paint)
        paint.color = Color.rgb(80, 160, 255)
        canvas.drawRect(16f, 52f, 16f + 300f * (armor.coerceIn(0, 100) / 100f), 72f, paint)
        // ammo
        paint.textSize = 64f
        paint.color = if ((me?.ammo ?: 1) > 0) Color.WHITE else Color.RED
        canvas.drawText("${me?.ammo ?: 0}", 16f, 140f, paint)
        paint.textSize = 30f
        paint.color = Color.WHITE
        canvas.drawText("/ 30", 110f, 140f, paint)
        // grenades
        canvas.drawText("雷 x${me?.grenades ?: 0}", 16f, 180f, paint)
        // dead banner
        if (me?.isDead == true) {
            paint.textSize = 56f
            paint.color = Color.RED
            val msg = "阵亡，等待复活…"
            canvas.drawText(msg, w / 2 - 180f, h / 2, paint)
            paint.textSize = 30f
        }
        // kill feed
        paint.color = Color.WHITE
        val kills = snap.kills.takeLast(3)
        kills.forEachIndexed { i, k ->
            canvas.drawText("${k.attackerName} ⚔ ${k.targetName}", 16f, 220f + i * 36f, paint)
        }
        // fps + counters (debug, top-right)
        val conn = if (client?.connected == true) "●" else "○"
        paint.color = Color.WHITE
        canvas.drawText("$conn ${fps.toInt()}fps", w - 180f, 42f, paint)
        // grenade button
        grenadeBtnX = w - 110f
        grenadeBtnY = 150f
        paint.color = Color.argb(120, 255, 165, 0)
        canvas.drawCircle(grenadeBtnX, grenadeBtnY, grenadeBtnR, paint)
        paint.color = Color.WHITE
        paint.textSize = 44f
        canvas.drawText("雷", grenadeBtnX - 22f, grenadeBtnY + 16f, paint)
        paint.textSize = 30f
    }

    private fun drawSticks(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        for (s in listOf(leftStick, rightStick)) {
            if (!s.active) continue
            paint.color = Color.argb(80, 255, 255, 255)
            canvas.drawCircle(s.ox, s.oy, stickRadius, paint)
            val (dx, dy) = s.vec(stickRadius)
            paint.color = Color.argb(160, 255, 255, 255)
            canvas.drawCircle(s.ox + dx, s.oy + dy, 55f, paint)
        }
    }

    private class Stick {
        var pointerId: Int = -1
        var ox = 0f; var oy = 0f
        var x = 0f; var y = 0f
        val active get() = pointerId >= 0
        fun start(id: Int, sx: Float, sy: Float) {
            pointerId = id; ox = sx; oy = sy; x = sx; y = sy
        }
        fun move(sx: Float, sy: Float) { x = sx; y = sy }
        fun release() { pointerId = -1 }
        fun vec(radius: Float): Pair<Float, Float> {
            var dx = x - ox; var dy = y - oy
            val len = hypot(dx, dy)
            if (len > radius && len > 0) {
                dx = dx / len * radius; dy = dy / len * radius
            }
            return dx to dy
        }
    }
}
