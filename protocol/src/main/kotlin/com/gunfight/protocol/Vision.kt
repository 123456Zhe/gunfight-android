package com.gunfight.protocol

import kotlin.math.abs

/** Vision replication of Python utils.is_visible + map.Door.line_intersects.
 *
 * The server broadcasts every player to everyone, so the client must cull the
 * same way the desktop client does (field of view + line of sight), otherwise
 * an Android player sees through walls and around corners. */
object Vision {
    const val FOV_DEG = 120.0
    const val AIMED_FOV_DEG = 30.0
    const val RANGE = 300.0

    fun inFieldOfView(
        px: Double, py: Double, angleDeg: Double,
        tx: Double, ty: Double, fovDeg: Double
    ): Boolean {
        if (px == tx && py == ty) return true
        val target = Angles.of(tx - px, ty - py)
        return Angles.diff(angleDeg, target) <= fovDeg / 2.0
    }

    fun isVisible(
        px: Double, py: Double, angleDeg: Double,
        tx: Double, ty: Double, fovDeg: Double,
        walls: List<FRect>, doors: List<DoorGeom>
    ): Boolean {
        if (!inFieldOfView(px, py, angleDeg, tx, ty, fovDeg)) return false
        return hasLineOfSight(px, py, tx, ty, walls, doors)
    }

    fun hasLineOfSight(
        px: Double, py: Double, tx: Double, ty: Double,
        walls: List<FRect>, doors: List<DoorGeom>
    ): Boolean {
        for (w in walls) if (segmentIntersectsRect(px, py, tx, ty, w)) return false
        for (d in doors) if (segmentIntersectsQuad(px, py, tx, ty, d.corners())) return false
        return true
    }

    /** Liang-Barsky segment/AABB clip; equivalent to pygame Rect.clipline (inclusive). */
    fun segmentIntersectsRect(px: Double, py: Double, tx: Double, ty: Double, r: FRect): Boolean {
        var t0 = 0.0
        var t1 = 1.0
        val dx = tx - px
        val dy = ty - py
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(px - r.x, r.x + r.w - px, py - r.y, r.y + r.h - py)
        for (i in 0..3) {
            if (p[i] == 0.0) {
                if (q[i] < 0.0) return false
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0.0) {
                    if (t > t1) return false
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return false
                    if (t < t1) t1 = t
                }
            }
        }
        return t0 <= t1
    }

    /** Edge-crossing test, mirrors map.Door.line_intersects (which walks the 4 panel edges). */
    fun segmentIntersectsQuad(
        px: Double, py: Double, tx: Double, ty: Double,
        corners: List<Pair<Double, Double>>
    ): Boolean {
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            if (segmentsIntersect(px, py, tx, ty, a.first, a.second, b.first, b.second)) return true
        }
        return false
    }

    /** Mirrors Python map.segment_intersection (same epsilon and inclusive bounds). */
    fun segmentsIntersect(
        x1: Double, y1: Double, x2: Double, y2: Double,
        x3: Double, y3: Double, x4: Double, y4: Double
    ): Boolean {
        val denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(denom) < 1e-9) return false
        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom
        val u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / denom
        return t in 0.0..1.0 && u in 0.0..1.0
    }
}
