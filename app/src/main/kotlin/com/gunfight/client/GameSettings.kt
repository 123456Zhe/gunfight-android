package com.gunfight.client

import android.content.Context
import com.gunfight.protocol.Json
import com.gunfight.protocol.obj

/** Loads bundled settings.json (copy of reference/settings.json) with hardcoded fallbacks. */
class GameSettings private constructor(val root: Map<String, Any?>) {

    fun section(name: String): Map<String, Any?> = root.obj(name)

    fun dbl(section: String, key: String, fb: Double): Double =
        (section(section)[key] as? Number)?.toDouble() ?: fb

    fun int(section: String, key: String, fb: Int): Int =
        (section(section)[key] as? Number)?.toInt() ?: fb

    /** Color as ARGB int; settings store [r,g,b] or [r,g,b,a]. */
    fun color(key: String, fb: Int): Int {
        val list = section("colors")[key] as? List<*> ?: return fb
        val c = list.map { (it as? Number)?.toInt() ?: 0 }
        val r = c.getOrElse(0) { 0 }
        val g = c.getOrElse(1) { 0 }
        val b = c.getOrElse(2) { 0 }
        val a = c.getOrElse(3) { 255 }
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    val mapSize: Double get() = dbl("map", "room_size", 600.0) * 3
    val wallThickness: Double get() = dbl("map", "wall_thickness", 20.0)
    val playerRadius: Double get() = dbl("game", "player_radius", 20.0)
    val bulletRadius: Double get() = dbl("game", "bullet_radius", 5.0)

    companion object {
        @Volatile private var cached: GameSettings? = null

        fun get(ctx: Context): GameSettings {
            cached?.let { return it }
            val root: Map<String, Any?> = try {
                ctx.assets.open("settings.json").bufferedReader().use { r ->
                    Json.parse(r.readText()) as? Map<String, Any?> ?: emptyMap()
                }
            } catch (_: Exception) {
                emptyMap()
            }
            return GameSettings(root).also { cached = it }
        }
    }
}
