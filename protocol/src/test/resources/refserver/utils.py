"""Stub for reference server's game-geometry helpers (unused on the
probe/connect/heartbeat path exercised by the JVM integration test)."""


def friendly_fire_enabled(*args, **kwargs):
    return False


def has_line_of_sight(*args, **kwargs):
    return True


def angle_difference(a, b):
    return (a - b + 180) % 360 - 180


def dprint(*args, **kwargs):
    pass
