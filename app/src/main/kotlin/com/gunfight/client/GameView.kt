package com.gunfight.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.gunfight.protocol.*
import kotlin.math.*

/** Playable view: twin-stick controls, HUD, FOV-culled world, camera follows the local player. */
class GameView(ctx: Context, val settings: GameSettings) : View(ctx) {

    @Volatile var client: GameClient? = null

    /** Chat button was tapped: the activity shows its input row. */
    var onChatToggle: (() -> Unit)? = null

    private val gameMap = GameMap()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        color = Color.WHITE
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
    private var lastPickupScanMs = 0L
    private var pendingPickupId = -1
    private var pendingPickupMs = 0L
    private var pickupBackoffMs = 0L
    private var lastHitSeenMs = 0L
    private var lastPruneMs = 0L

    // ---- joysticks ----
    private val leftStick = Stick()
    private val rightStick = Stick()
    private val stickRadius = 130f
    private val deadZone = 14f
    private var grenadeBtnX = 0f
    private var grenadeBtnY = 0f
    private val grenadeBtnR = 70f
    private var meleeBtnX = 0f
    private var meleeBtnY = 0f
    private val meleeBtnR = 60f
    private var chatBtnX = 0f
    private var chatBtnY = 0f
    private val chatBtnR = 60f

    private var lastMeleeMs = 0L
    private var meleeEndMs = 0L
    private var lastMeleeHeavy = false
    private var meleeHoldPointer = -1
    private var meleeHoldStartMs = 0L
    private var meleeHeavyFired = false
    private val meleeHoldThresholdMs = 350L
    private val arcRect = RectF()

    private var lastFrameNs = System.nanoTime()
    private var fps = 0.0

    /** Smoothed positions for remote players (server only sends 20Hz). */
    private class Smooth(var x: Double, var y: Double, var angle: Double)

    private val remote = HashMap<Int, Smooth>()

    /** Bullet id -> receive time, for 20Hz extrapolation. */
    private val bulletSeen = HashMap<Int, Long>()

    private class HitMark(val x: Double, val y: Double, val damage: Double, val recvMs: Long)

    private val hitMarks = ArrayList<HitMark>()

    private val palette = intArrayOf(
        Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW,
        Color.rgb(255, 165, 0), Color.rgb(128, 0, 128), Color.CYAN
    )

    private val teamPalette = intArrayOf(
        Color.rgb(80, 160, 255), Color.rgb(255, 120, 120),
        Color.rgb(120, 220, 140), Color.rgb(240, 200, 90)
    )

    private fun playerColor(id: Int): Int =
        if (id == 0) Color.WHITE else palette[Math.floorMod(id, 7)]

    private fun teamColor(teamId: Int): Int = teamPalette[Math.floorMod(teamId, teamPalette.size)]

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
                if (hypot(x - grenadeBtnX, y - grenadeBtnY) <= grenadeBtnR * 1.3f) {
                    throwGrenade()
                    return true
                }
                if (hypot(x - meleeBtnX, y - meleeBtnY) <= meleeBtnR * 1.4f) {
                    // tap fires light on release, holding past the threshold fires heavy only
                    meleeHoldPointer = id
                    meleeHoldStartMs = System.currentTimeMillis()
                    meleeHeavyFired = false
                    return true
                }
                if (hypot(x - chatBtnX, y - chatBtnY) <= chatBtnR * 1.4f) {
                    onChatToggle?.invoke()
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
                if (id == meleeHoldPointer) {
                    // released without a heavy: that was a tap (or heavy was still on cooldown)
                    if (e.actionMasked != MotionEvent.ACTION_CANCEL && !meleeHeavyFired) {
                        meleeAttack(false)
                    }
                    meleeHoldPointer = -1
                }
            }
        }
        return true
    }

    private fun throwGrenade() {
        val c = client ?: return
        val me = c.snapshot.players[myId] ?: return
        if (me.isDead || me.grenades <= 0) return
        val rad = Math.toRadians(myAngle)
        val dx = cos(rad); val dy = -sin(rad)
        c.sendGrenade(myX + dx * 30, myY + dy * 30, dx, dy)
    }

    /** Melee swing: candidates are pre-filtered locally (range/angle/LOS), the server re-validates. */
    private fun meleeAttack(heavy: Boolean) {
        val c = client ?: return
        val me = c.snapshot.players[myId] ?: return
        if (me.isDead) return
        val now = System.currentTimeMillis()
        val cooldown = if (heavy) settings.heavyMeleeCooldownMs else settings.meleeCooldownMs
        // the server grants 10% tolerance on its cooldown check
        if (now - lastMeleeMs < (cooldown * 0.95).toLong()) return
        lastMeleeMs = now
        meleeEndMs = now + 220
        val range = (if (heavy) settings.heavyMeleeRange else settings.meleeRange) + settings.playerRadius
        val half = (if (heavy) settings.heavyMeleeAngle else settings.meleeAngle) / 2 + 15
        val targets = ArrayList<Int>()
        for (p in c.snapshot.players.values) {
            if (p.id == myId || p.isDead) continue
            val dx = p.posX - myX
            val dy = p.posY - myY
            if (hypot(dx, dy) > range) continue
            if (Angles.diff(myAngle, Angles.of(dx, dy)) > half) continue
            if (!Vision.hasLineOfSight(myX, myY, p.posX, p.posY, gameMap.walls, gameMap.doors)) continue
            targets.add(p.id)
            if (targets.size >= 20) break
        }
        lastMeleeHeavy = heavy
        c.sendMelee(myAngle, targets, heavy)
    }

    /** A hold past the threshold becomes a heavy swing; a tap stays light (fired on release). */
    private fun updateMeleeHold(nowMs: Long) {
        if (meleeHoldPointer < 0 || meleeHeavyFired) return
        if (nowMs - meleeHoldStartMs < meleeHoldThresholdMs) return
        if (nowMs - lastMeleeMs < (settings.heavyMeleeCooldownMs * 0.95).toLong()) return
        meleeHeavyFired = true
        meleeAttack(true)
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
        if (myId < 0) {
            myId = c.handshake.clientId
            c.snapshot.localPlayerId = myId
        }
        val snap = c.snapshot

        updateMeleeHold(nowMs)

        if (nowMs - lastPruneMs >= 500) {
            lastPruneMs = nowMs
            snap.pruneStalePlayers(nowMs)
        }

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

        if (initialized && me != null && !me.isDead && c.connected) {
            updateLocal(dt, nowMs, c, me)
        }

        render(canvas, snap, me, dt, nowMs)
        postInvalidateOnAnimation()
    }

    private fun updateLocal(dt: Double, nowMs: Long, c: GameClient, me: PlayerState) {
        // --- movement (left stick, linear response outside the dead zone) ---
        val (mdx, mdy) = leftStick.vec(stickRadius)
        val mLen = hypot(mdx, mdy)
        var wantX = 0.0
        var wantY = 0.0
        if (mLen > deadZone) {
            val mag = min(1.0, ((mLen - deadZone) / (stickRadius - deadZone)).toDouble())
            val speed = settings.playerSpeed * mag
            wantX = (mdx / mLen) * speed * dt
            wantY = (mdy / mLen) * speed * dt
        }

        // --- aim (right stick) ---
        val (adx, ady) = rightStick.vec(stickRadius)
        val aLen = hypot(adx, ady)
        if (aLen > deadZone) {
            myAngle = Math.toDegrees(atan2((-ady).toDouble(), adx.toDouble()))
        }

        // --- reload: ammo is server-authoritative, this only gates local fire ---
        if (reloading && nowMs >= reloadEndMs) reloading = false
        if (!reloading && me.ammo <= 0) {
            reloading = true
            reloadEndMs = nowMs + settings.reloadMs
        }

        // --- move with collision; auto-open doors we are pushing against ---
        if (wantX != 0.0 || wantY != 0.0) {
            val (nx, ny) = gameMap.moveWithCollision(myX, myY, wantX, wantY, settings.playerRadius)
            if (nx == myX && ny == myY) tryOpenDoor(c, nowMs)
            myX = nx; myY = ny
        }

        // --- fire ---
        shooting = false
        if (aLen > stickRadius * 0.35 && !reloading && me.ammo > 0 &&
            nowMs - lastFireMs >= settings.fireIntervalMs
        ) {
            val dx = (adx / aLen).toDouble()
            val dy = (ady / aLen).toDouble()
            val rad = Math.toRadians(myAngle)
            val mdx2 = cos(rad); val mdy2 = -sin(rad)
            c.sendFire(myX + mdx2 * 28, myY + mdy2 * 28, dx, dy)
            lastFireMs = nowMs
            shooting = true
        }

        // --- walk-over item pickup ---
        tryPickup(c, nowMs)

        // --- report state 20Hz ---
        if (nowMs - lastSentMs >= 50) {
            c.sendPlayerUpdate(myX, myY, myAngle, shooting, reloading, nowMs < meleeEndMs, myAngle)
            lastSentMs = nowMs
        }
    }

    /** Walk-over pickup: nearest active item in range, retried until the server confirms. */
    private fun tryPickup(c: GameClient, nowMs: Long) {
        if (nowMs < pickupBackoffMs || nowMs - lastPickupScanMs < 120) return
        lastPickupScanMs = nowMs
        val snap = c.snapshot
        if (pendingPickupId >= 0) {
            val confirmed = snap.lastPickupItemId == pendingPickupId ||
                snap.items[pendingPickupId]?.active == false
            if (confirmed) {
                pendingPickupId = -1
            } else if (nowMs - pendingPickupMs > 1500) {
                // server rejected (out of range): pause before trying the next item
                pendingPickupId = -1
                pickupBackoffMs = nowMs + 600
            } else {
                return
            }
        }
        val reach = settings.pickupRange - 5
        var best: ItemState? = null
        var bestDist = reach * reach
        for (it in snap.items.values) {
            if (!it.active) continue
            val dx = it.x - myX
            val dy = it.y - myY
            val d2 = dx * dx + dy * dy
            if (d2 <= bestDist) {
                bestDist = d2
                best = it
            }
        }
        val target = best ?: return
        pendingPickupId = target.id
        pendingPickupMs = nowMs
        c.sendItemPickup(target.id)
    }

    /** If we are pushing against a closed door, open it away from us. */
    private fun tryOpenDoor(c: GameClient, nowMs: Long) {
        if (nowMs - lastDoorSyncMs < 500) return
        for (d in gameMap.doors) {
            if (abs(d.progress) >= 0.9) continue
            val ox = d.original.x + d.original.w / 2
            val oy = d.original.y + d.original.h / 2
            if (hypot(myX - ox, myY - oy) > 120) continue
            val rad = Math.toRadians(GameMap.DOOR_OPEN_ANGLE)
            val cosA = cos(rad); val sinA = sin(rad)
            val epx = d.hingeX + (d.baseDirX * cosA - d.baseDirY * sinA) * d.panelLength
            val epy = d.hingeY + (d.baseDirX * sinA + d.baseDirY * cosA) * d.panelLength
            val emx = d.hingeX + (d.baseDirX * cosA + d.baseDirY * sinA) * d.panelLength
            val emy = d.hingeY + (-d.baseDirX * sinA + d.baseDirY * cosA) * d.panelLength
            val distPlus = hypot(myX - epx, myY - epy)
            val distMinus = hypot(myX - emx, myY - emy)
            val target = if (distPlus < distMinus) -1.0 else 1.0
            d.progress = target
            val serverVersion =
                (c.snapshot.doors[d.id.toString()]?.get("version") as? Number)?.toInt() ?: 0
            c.sendDoorUpdate(d.id, target, serverVersion)
            lastDoorSyncMs = nowMs
            break
        }
    }

    // ================= render =================

    private fun render(canvas: Canvas, snap: GameSnapshot, me: PlayerState?, dt: Double, nowMs: Long) {
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
        drawItems(canvas, snap)
        drawGrenades(canvas, snap)
        drawBullets(canvas, snap, nowMs)
        drawPlayers(canvas, snap, me, dt, nowMs)
        drawExplosions(canvas, snap, nowMs)
        drawHitMarks(canvas, snap, nowMs)
        canvas.restore()

        drawHud(canvas, w, h, me, snap, nowMs)
        drawSticks(canvas)
    }

    /** Visible = inside the local player's FOV with line of sight, same as the desktop client. */
    private fun visible(x: Double, y: Double): Boolean {
        if (!initialized) return true
        return Vision.isVisible(
            myX, myY, myAngle, x, y, settings.fovDeg,
            gameMap.walls, gameMap.doors
        )
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
        paint.color = settings.color("door", Color.rgb(160, 120, 60))
        for (d in gameMap.doors) {
            val corners = d.corners()
            path.reset()
            path.moveTo(corners[0].first.toFloat(), corners[0].second.toFloat())
            for (i in 1..3) path.lineTo(corners[i].first.toFloat(), corners[i].second.toFloat())
            path.close()
            canvas.drawPath(path, paint)
        }
        linePaint.color = Color.WHITE
        canvas.drawRect(0f, 0f, mapSize, mapSize, linePaint)
    }

    private fun drawPlayers(canvas: Canvas, snap: GameSnapshot, me: PlayerState?, dt: Double, nowMs: Long) {
        val r = settings.playerRadius.toFloat()
        val alive = HashSet<Int>()
        val lerp = min(1.0, dt * 14.0)
        val myTeam = me?.teamId
        for (p in snap.players.values.sortedBy { it.id }) {
            val isMe = p.id == myId
            val teammate = myTeam != null && p.teamId == myTeam
            if (!isMe) {
                alive.add(p.id)
                val s = remote.getOrPut(p.id) { Smooth(p.posX, p.posY, p.angle) }
                s.x += (p.posX - s.x) * lerp
                s.y += (p.posY - s.y) * lerp
                var d = Angles.normalize(p.angle - s.angle)
                if (d > 180.0) d -= 360.0
                s.angle = Angles.normalize(s.angle + d * lerp)
                if (!teammate && !visible(p.posX, p.posY)) continue
            }
            val s = remote[p.id]
            val x = if (isMe && initialized) myX else if (isMe) p.posX else (s?.x ?: p.posX)
            val y = if (isMe && initialized) myY else if (isMe) p.posY else (s?.y ?: p.posY)
            val ang = if (isMe && initialized) myAngle else if (isMe) p.angle else (s?.angle ?: p.angle)
            val col = if (p.isDead) settings.color("dead", Color.GRAY)
            else if (isMe) Color.WHITE
            else if (p.teamId != null) teamColor(p.teamId)
            else playerColor(p.id)
            paint.style = Paint.Style.FILL
            paint.color = col
            canvas.drawCircle(x.toFloat(), y.toFloat(), r, paint)
            if (isMe) {
                linePaint.color = Color.YELLOW
                linePaint.strokeWidth = 3f
                canvas.drawCircle(x.toFloat(), y.toFloat(), r + 6, linePaint)
                linePaint.strokeWidth = 4f
            }
            val rad = Math.toRadians(ang)
            val dx = cos(rad); val dy = -sin(rad)
            linePaint.color = Color.WHITE
            canvas.drawLine(
                x.toFloat(), y.toFloat(),
                (x + dx * (r + 14)).toFloat(), (y + dy * (r + 14)).toFloat(), linePaint
            )
            val mine = isMe && nowMs < meleeEndMs
            if (p.meleeAttacking || mine) {
                val swing = if (mine) myAngle else p.meleeDirection
                val heavySwing = mine && lastMeleeHeavy
                val half = ((if (heavySwing) settings.heavyMeleeAngle else settings.meleeAngle) / 2).toFloat()
                val radius = if (heavySwing) 62f else 72f
                arcRect.set(
                    (x - radius).toFloat(), (y - radius).toFloat(),
                    (x + radius).toFloat(), (y + radius).toFloat()
                )
                linePaint.color = Color.argb(210, 255, 240, 120)
                linePaint.strokeWidth = 6f
                canvas.drawArc(arcRect, -swing.toFloat() - half, half * 2, false, linePaint)
                linePaint.strokeWidth = 4f
                linePaint.color = Color.WHITE
            }
            if (p.isDead) {
                if (p.respawnTime > 0) {
                    val left = p.respawnTime - nowMs / 1000.0
                    if (left > 0) {
                        textPaint.color = Color.LTGRAY
                        canvas.drawText("%.1f".format(left), (x - 14).toFloat(), (y + 10).toFloat(), textPaint)
                        textPaint.color = Color.WHITE
                    }
                }
            } else {
                val hpFrac = (p.health.coerceIn(0, 100)) / 100f
                paint.color = if (hpFrac > 0.5) Color.GREEN else Color.RED
                canvas.drawRect(
                    (x - r).toFloat(), (y - r - 18).toFloat(),
                    (x - r + 2 * r * hpFrac).toFloat(), (y - r - 10).toFloat(), paint
                )
                textPaint.color = Color.WHITE
                canvas.drawText(p.name, (x - r).toFloat(), (y - r - 24).toFloat(), textPaint)
            }
        }
        remote.keys.retainAll(alive)
    }

    private fun drawBullets(canvas: Canvas, snap: GameSnapshot, nowMs: Long) {
        val r = settings.bulletRadius.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val keep = HashSet<Int>()
        for (b in snap.bullets) {
            keep.add(b.id)
            val seen = bulletSeen.getOrPut(b.id) { if (b.recvMs > 0) b.recvMs else nowMs }
            val age = ((nowMs - seen) / 1000.0).coerceIn(0.0, 0.15)
            val x = b.x + b.dx * Game.BULLET_SPEED * age
            val y = b.y + b.dy * Game.BULLET_SPEED * age
            canvas.drawCircle(x.toFloat(), y.toFloat(), r, paint)
        }
        bulletSeen.keys.retainAll(keep)
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
            if (!visible(it.x, it.y)) continue
            paint.color = itemColor(it.type)
            canvas.drawRect(
                (it.x - 10).toFloat(), (it.y - 10).toFloat(),
                (it.x + 10).toFloat(), (it.y + 10).toFloat(), paint
            )
        }
    }

    private fun drawExplosions(canvas: Canvas, snap: GameSnapshot, nowMs: Long) {
        linePaint.strokeWidth = 4f
        for (e in snap.explosions) {
            val age = (nowMs - e.recvMs) / 1000.0
            if (age > 0.5) continue
            val t = (age / 0.5).toFloat()
            linePaint.color = Color.argb((220 * (1 - t)).toInt(), 255, 165, 0)
            canvas.drawCircle(e.x.toFloat(), e.y.toFloat(), 12f + 110f * t, linePaint)
        }
    }

    private fun drawHitMarks(canvas: Canvas, snap: GameSnapshot, nowMs: Long) {
        val hit = snap.lastHit
        if (hit != null && hit.recvMs != lastHitSeenMs) {
            lastHitSeenMs = hit.recvMs
            if (hit.targetPosX != null && hit.targetPosY != null) {
                hitMarks.add(HitMark(hit.targetPosX, hit.targetPosY, hit.damage, hit.recvMs))
                while (hitMarks.size > 8) hitMarks.removeAt(0)
            }
        }
        hitMarks.removeAll { nowMs - it.recvMs > 800 }
        if (hitMarks.isEmpty()) return
        textPaint.textSize = 34f
        textPaint.color = Color.rgb(255, 220, 0)
        for (m in hitMarks) {
            val t = ((nowMs - m.recvMs) / 800.0).toFloat()
            val y = m.y - 30 - 40 * t
            canvas.drawText("-" + m.damage.toInt(), (m.x - 16).toFloat(), y.toFloat(), textPaint)
        }
        textPaint.textSize = 30f
        textPaint.color = Color.WHITE
    }

    private fun drawHud(canvas: Canvas, w: Float, h: Float, me: PlayerState?, snap: GameSnapshot, nowMs: Long) {
        paint.style = Paint.Style.FILL
        val hpFrac = ((me?.health ?: 100).coerceIn(0, 100)) / 100f
        paint.color = Color.rgb(60, 60, 60)
        canvas.drawRect(16f, 16f, 316f, 44f, paint)
        paint.color = if (hpFrac > 0.5) Color.GREEN else if (hpFrac > 0.25) Color.rgb(255, 165, 0) else Color.RED
        canvas.drawRect(16f, 16f, 16f + 300f * hpFrac, 44f, paint)
        textPaint.color = Color.WHITE
        canvas.drawText("" + (me?.health ?: 100), 330f, 42f, textPaint)
        val armor = me?.armor ?: 0
        paint.color = Color.rgb(60, 60, 60)
        canvas.drawRect(16f, 52f, 316f, 72f, paint)
        paint.color = Color.rgb(80, 160, 255)
        canvas.drawRect(16f, 52f, 16f + 300f * (armor.coerceIn(0, 100) / 100f), 72f, paint)

        textPaint.textSize = 64f
        textPaint.color = if ((me?.ammo ?: 1) > 0) Color.WHITE else Color.RED
        canvas.drawText("" + (me?.ammo ?: 0), 16f, 140f, textPaint)
        textPaint.textSize = 30f
        textPaint.color = Color.WHITE
        canvas.drawText("/ " + settings.magazineSize, 110f, 140f, textPaint)
        canvas.drawText("雷 x" + (me?.grenades ?: 0), 16f, 180f, textPaint)
        me?.teamId?.let { canvas.drawText("队伍 " + it, 160f, 180f, textPaint) }

        if (me?.isDead == true) {
            textPaint.textSize = 56f
            textPaint.color = Color.RED
            val left = if (me.respawnTime > 0) me.respawnTime - nowMs / 1000.0 else 0.0
            val msg = if (left > 0) "阵亡 %.1f 秒后复活".format(left) else "阵亡，等待复活"
            canvas.drawText(msg, w / 2 - 240f, h / 2, textPaint)
            textPaint.textSize = 30f
            textPaint.color = Color.WHITE
        }

        snap.kills.takeLast(3).forEachIndexed { i, k ->
            canvas.drawText(k.attackerName + " 击杀了 " + k.targetName, 16f, 220f + i * 36f, textPaint)
        }

        var chatY = h - 20f
        for (line in snap.chat.takeLast(4).reversed()) {
            val age = nowMs - line.recvMs
            textPaint.color = Color.argb(if (age < 8000) 230 else 90, 220, 220, 220)
            val prefix = if (line.playerId == 0) "" else line.name + ": "
            canvas.drawText(prefix + line.text, 16f, chatY, textPaint)
            chatY -= 34f
        }
        textPaint.color = Color.WHITE

        val conn = if (client?.connected == true) "●" else "○"
        canvas.drawText(conn + " " + fps.toInt() + "fps", w - 190f, 42f, textPaint)

        grenadeBtnX = w - 110f
        grenadeBtnY = 150f
        val haveGrenade = (me?.grenades ?: 0) > 0
        paint.color = Color.argb(if (haveGrenade) 120 else 50, 255, 165, 0)
        canvas.drawCircle(grenadeBtnX, grenadeBtnY, grenadeBtnR, paint)
        textPaint.textSize = 44f
        textPaint.color = if (haveGrenade) Color.WHITE else Color.GRAY
        canvas.drawText("雷", grenadeBtnX - 22f, grenadeBtnY + 16f, textPaint)

        meleeBtnX = w - 110f
        meleeBtnY = 300f
        val heavyGateMs = (settings.heavyMeleeCooldownMs * 0.95).toLong()
        val heavyGateReady = nowMs - lastMeleeMs >= heavyGateMs
        val meleeReady = me?.isDead != true &&
            nowMs - lastMeleeMs >= (settings.meleeCooldownMs * 0.95).toLong()
        val holding = meleeHoldPointer >= 0 && !meleeHeavyFired
        val holdElapsed = if (holding) nowMs - meleeHoldStartMs else 0L
        val holdArmed = holding && holdElapsed >= meleeHoldThresholdMs && heavyGateReady
        paint.color = Color.argb(if (meleeReady) 120 else 50, 200, 200, 220)
        canvas.drawCircle(meleeBtnX, meleeBtnY, meleeBtnR, paint)
        if (holding) {
            val frac = (holdElapsed.toFloat() / meleeHoldThresholdMs.toFloat()).coerceIn(0f, 1f)
            linePaint.strokeWidth = 8f
            linePaint.color = if (holdArmed) Color.rgb(255, 220, 80) else Color.argb(200, 200, 200, 200)
            arcRect.set(
                meleeBtnX - meleeBtnR - 10f, meleeBtnY - meleeBtnR - 10f,
                meleeBtnX + meleeBtnR + 10f, meleeBtnY + meleeBtnR + 10f
            )
            canvas.drawArc(arcRect, -90f, 360f * frac, false, linePaint)
            linePaint.strokeWidth = 4f
        }
        textPaint.textSize = 40f
        textPaint.color = if (meleeReady) Color.WHITE else Color.GRAY
        canvas.drawText("刀", meleeBtnX - 20f, meleeBtnY + 14f, textPaint)
        if (holding && holdElapsed >= meleeHoldThresholdMs) {
            textPaint.textSize = 26f
            textPaint.color = if (heavyGateReady) Color.rgb(255, 220, 80) else Color.GRAY
            canvas.drawText(if (heavyGateReady) "重击" else "冷却", meleeBtnX - 84f, meleeBtnY + 8f, textPaint)
            textPaint.textSize = 40f
            textPaint.color = Color.WHITE
        }

        chatBtnX = w - 110f
        chatBtnY = 430f
        paint.color = Color.argb(90, 120, 200, 255)
        canvas.drawCircle(chatBtnX, chatBtnY, chatBtnR, paint)
        textPaint.color = Color.WHITE
        canvas.drawText("聊", chatBtnX - 20f, chatBtnY + 14f, textPaint)

        textPaint.textSize = 30f
        textPaint.color = Color.WHITE
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
