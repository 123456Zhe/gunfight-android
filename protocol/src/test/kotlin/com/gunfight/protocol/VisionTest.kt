package com.gunfight.protocol

import org.junit.Assert.*
import org.junit.Test

/** FOV / line-of-sight culling must match Python utils.is_visible. */
class VisionTest {
    private val map = GameMap()

    @Test fun fieldOfViewRespectsFacing() {
        // 0 deg = +x, 120 deg FOV => +-60 deg
        assertTrue(Vision.inFieldOfView(100.0, 100.0, 0.0, 200.0, 100.0, 120.0))
        assertTrue(Vision.inFieldOfView(100.0, 100.0, 0.0, 200.0, 0.0, 120.0))
        assertFalse(Vision.inFieldOfView(100.0, 100.0, 0.0, 100.0, 200.0, 120.0))
        assertFalse(Vision.inFieldOfView(100.0, 100.0, 0.0, 0.0, 100.0, 120.0))
        assertTrue(Vision.inFieldOfView(100.0, 100.0, 0.0, 100.0, 100.0, 120.0))
        // aimed FOV is narrow: 30 deg => +-15
        assertTrue(Vision.inFieldOfView(100.0, 100.0, 0.0, 200.0, 110.0, 30.0))
        assertFalse(Vision.inFieldOfView(100.0, 100.0, 0.0, 200.0, 160.0, 30.0))
    }

    @Test fun angleDifferenceWraps() {
        assertEquals(20.0, Angles.diff(350.0, 10.0), 1e-9)
        assertEquals(180.0, Angles.diff(0.0, 180.0), 1e-9)
        assertEquals(0.0, Angles.diff(90.0, 90.0), 1e-9)
    }

    @Test fun wallBlocksLineOfSight() {
        // inner vertical wall sits at x in [580, 600] for the row 0 rooms except the door gap
        val px = 100.0
        val py = 30.0 // along the top border wall lane
        assertFalse(Vision.segmentIntersectsRect(0.0, 0.0, 100.0, 10.0, FRect(40.0, 40.0, 20.0, 20.0)))
        assertTrue(Vision.segmentIntersectsRect(0.0, 10.0, 100.0, 10.0, FRect(40.0, 0.0, 20.0, 40.0)))
        assertFalse(Vision.hasLineOfSight(0.0, 600.0, 1800.0, 600.0, map.walls, map.doors))
        assertTrue(Vision.hasLineOfSight(100.0, 100.0, 200.0, 100.0, map.walls, map.doors))
        // sanity: the probe points above are not accidentally inside a wall
        assertFalse(map.collides(px, py, 1.0))
    }

    @Test fun closedDoorBlocksButOpenDoorDoesNot() {
        val door = map.doors[0] // horizontal door at x 580..600, y 260..340
        door.progress = 0.0
        assertFalse(
            "closed door must block sight across the doorway",
            Vision.hasLineOfSight(500.0, 300.0, 700.0, 300.0, map.walls, map.doors)
        )
        door.progress = 1.0
        assertTrue(
            "open door must not block sight",
            Vision.hasLineOfSight(500.0, 300.0, 700.0, 300.0, map.walls, map.doors)
        )
        door.progress = 0.0
    }
}
