package com.gunfight.protocol

import kotlin.math.*

/** Axis-aligned rect for wall collision. */
data class FRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    fun intersects(o: FRect): Boolean =
        x < o.x + o.w && x + w > o.x && y < o.y + o.h && y + h > o.y
}

/**
 * Deterministic grid3x3 map replica (mirrors Python map.py).
 * Walls are fixed; door panels rotate (progress -1..1, angle = progress * 150 deg).
 * Door live state (progress) is fed from server door_update packets.
 */
class GameMap {
    companion object {
        const val ROOM_SIZE = 600.0
        const val WALL_THICKNESS = 20.0
        const val DOOR_SIZE = 80.0
        const val DOOR_OPEN_ANGLE = 150.0
        const val PLAYER_RADIUS = 20.0
        const val PLAYER_SPEED = 300.0
    }

    val walls = ArrayList<FRect>()
    val doors = ArrayList<DoorGeom>()

    init {
        generateDoors()
        generateWalls()
    }

    private fun generateDoors() {
        var id = 0
        // horizontal doors (connect rooms left-right): panel spans Y
        for (row in 0..2) for (col in 0..1) {
            val dx = (col + 1) * ROOM_SIZE - WALL_THICKNESS
            val dy = row * ROOM_SIZE + (ROOM_SIZE - DOOR_SIZE) / 2
            val swing = if ((row + col) % 2 == 0) 1 else -1
            doors.add(DoorGeom(id++, FRect(dx, dy, WALL_THICKNESS, DOOR_SIZE), false, swing))
        }
        // vertical doors (connect rooms up-down): panel spans X
        for (row in 0..1) for (col in 0..2) {
            val dx = col * ROOM_SIZE + (ROOM_SIZE - DOOR_SIZE) / 2
            val dy = (row + 1) * ROOM_SIZE - WALL_THICKNESS
            val swing = if ((row + col) % 2 == 0) 1 else -1
            doors.add(DoorGeom(id++, FRect(dx, dy, DOOR_SIZE, WALL_THICKNESS), true, swing))
        }
    }

    private fun generateWalls() {
        val m = ROOM_SIZE * 3
        val t = WALL_THICKNESS
        walls.add(FRect(0.0, 0.0, m, t))
        walls.add(FRect(0.0, m - t, m, t))
        walls.add(FRect(0.0, 0.0, t, m))
        walls.add(FRect(m - t, 0.0, t, m))
        val doorRects = doors.map { it.original }
        // internal vertical walls (col 1..2), avoiding doors
        for (col in 1..2) {
            val wx = col * ROOM_SIZE - t
            for (row in 0..2) {
                walls.addAll(segmentsAvoidingDoors(FRect(wx, row * ROOM_SIZE, t, ROOM_SIZE), doorRects, vertical = true))
            }
        }
        // internal horizontal walls (row 1..2), avoiding doors
        for (row in 1..2) {
            val wy = row * ROOM_SIZE - t
            for (col in 0..2) {
                walls.addAll(segmentsAvoidingDoors(FRect(col * ROOM_SIZE, wy, ROOM_SIZE, t), doorRects, vertical = false))
            }
        }
    }

    private fun segmentsAvoidingDoors(wall: FRect, doorRects: List<FRect>, vertical: Boolean): List<FRect> {
        val overlapping = doorRects.filter { wall.intersects(it) }
        if (overlapping.isEmpty()) return listOf(wall)
        val out = ArrayList<FRect>()
        if (vertical) {
            var cy = wall.y
            for (d in overlapping.sortedBy { it.y }) {
                if (cy < d.y) out.add(FRect(wall.x, cy, wall.w, d.y - cy))
                cy = d.y + d.h
            }
            if (cy < wall.y + wall.h) out.add(FRect(wall.x, cy, wall.w, wall.y + wall.h - cy))
        } else {
            var cx = wall.x
            for (d in overlapping.sortedBy { it.x }) {
                if (cx < d.x) out.add(FRect(cx, wall.y, d.x - cx, wall.h))
                cx = d.x + d.w
            }
            if (cx < wall.x + wall.w) out.add(FRect(cx, wall.y, wall.x + wall.w - cx, wall.h))
        }
        return out
    }

    /** Feed server door state (from door_update packets). */
    fun updateDoorState(doorId: Int, progress: Double) {
        doors.getOrNull(doorId)?.progress = progress.coerceIn(-1.0, 1.0)
    }

    /** True if a circle at (x,y) with radius r hits any wall or door panel. */
    fun collides(x: Double, y: Double, r: Double): Boolean {
        val pr = FRect(x - r, y - r, r * 2, r * 2)
        for (w in walls) if (pr.intersects(w)) return true
        for (d in doors) if (d.collidesCircle(x, y, r)) return true
        return false
    }

    /** Move with slide: try full step, then x-only, then y-only. Returns new position. */
    fun moveWithCollision(x: Double, y: Double, dx: Double, dy: Double, r: Double): Pair<Double, Double> {
        if (!collides(x + dx, y + dy, r)) return (x + dx) to (y + dy)
        if (!collides(x + dx, y, r)) return (x + dx) to y
        if (!collides(x, y + dy, r)) return x to (y + dy)
        return x to y
    }
}

/** One door: deterministic geometry + live progress from server. */
class DoorGeom(
    val id: Int,
    val original: FRect,
    val isVertical: Boolean,
    val swing: Int
) {
    val panelLength: Double
    val panelThickness: Double
    val hingeX: Double
    val hingeY: Double
    val baseDirX: Double
    val baseDirY: Double
    val baseNormX: Double
    val baseNormY: Double

    @Volatile var progress: Double = 0.0

    init {
        if (isVertical) {
            panelLength = original.w; panelThickness = original.h
            hingeX = if (swing > 0) original.x else original.x + original.w
            hingeY = original.y + original.h / 2
            baseDirX = if (swing > 0) 1.0 else -1.0; baseDirY = 0.0
            baseNormX = 0.0; baseNormY = 1.0
        } else {
            panelLength = original.h; panelThickness = original.w
            hingeX = original.x + original.w / 2
            hingeY = if (swing > 0) original.y else original.y + original.h
            baseDirX = 0.0; baseDirY = if (swing > 0) 1.0 else -1.0
            baseNormX = 1.0; baseNormY = 0.0
        }
    }

    /** Panel corners at current progress (mirrors Python get_corners). */
    fun corners(): List<Pair<Double, Double>> {
        val rad = Math.toRadians(progress * GameMap.DOOR_OPEN_ANGLE)
        val c = cos(rad); val s = sin(rad)
        val dx = baseDirX * c - baseDirY * s
        val dy = baseDirX * s + baseDirY * c
        val nx = baseNormX * c - baseNormY * s
        val ny = baseNormX * s + baseNormY * c
        val ht = panelThickness / 2
        val ax = hingeX - nx * ht; val ay = hingeY - ny * ht
        val bx = hingeX + nx * ht; val by = hingeY + ny * ht
        return listOf(
            ax to ay,
            ax + dx * panelLength to ay + dy * panelLength,
            bx + dx * panelLength to by + dy * panelLength,
            bx to by
        )
    }

    /** Circle vs rotated panel (OBB) overlap test. */
    fun collidesCircle(cx: Double, cy: Double, r: Double): Boolean {
        val rad = Math.toRadians(progress * GameMap.DOOR_OPEN_ANGLE)
        val c = cos(rad); val s = sin(rad)
        val ux = baseDirX * c - baseDirY * s
        val uy = baseDirX * s + baseDirY * c
        val vx = baseNormX * c - baseNormY * s
        val vy = baseNormX * s + baseNormY * c
        // panel center
        val px = hingeX + ux * panelLength / 2
        val py = hingeY + uy * panelLength / 2
        // circle center in panel local coords
        val lx = cx - px; val ly = cy - py
        val lu = lx * ux + ly * uy
        val lv = lx * vx + ly * vy
        val cu = lu.coerceIn(-panelLength / 2, panelLength / 2)
        val cv = lv.coerceIn(-panelThickness / 2, panelThickness / 2)
        val ddx = lu - cu; val ddy = lv - cv
        return ddx * ddx + ddy * ddy < r * r
    }
}
