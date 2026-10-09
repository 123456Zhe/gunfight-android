"""Launches the real reference server (zd-2d-gunfight/network.py) with lightweight
stubs for its game-only imports (pygame/constants/utils), so the JVM client can
perform a true UDP handshake against the actual Python implementation.

GUNFIGHT_REFERENCE_NETWORK  path to that repo's network.py (else reference/network.py)
GUNFIGHT_TEST_PORT          UDP port, 0 picks a free one (default 0)
GUNFIGHT_HELPER_WORLD=1     also build a fake door/item world and drive the 20Hz
                            broadcast, for pickup/door integration tests
"""

import importlib.util
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import pygame as pg  # the stub next to this file

REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", ".."))
NETWORK_PY = (
    os.environ.get("GUNFIGHT_REFERENCE_NETWORK")
    or os.path.join(REPO_ROOT, "reference", "network.py")
)

if not os.path.isfile(NETWORK_PY):
    print("SKIP reference network.py not found: %s" % NETWORK_PY, flush=True)
    sys.exit(3)

TEST_PORT = int(os.environ.get("GUNFIGHT_TEST_PORT", "0"))
WORLD = os.environ.get("GUNFIGHT_HELPER_WORLD") == "1"

spec = importlib.util.spec_from_file_location("refnet", NETWORK_PY)
m = importlib.util.module_from_spec(spec)
sys.modules["refnet"] = m
spec.loader.exec_module(m)

m.SERVER_PORT = TEST_PORT  # network.py reads this global when binding
nm = m.NetworkManager(is_server=True, server_name="IntegTest", player_name="Server")
if not getattr(nm, "connected", False):
    print("ERROR reference server failed to start: %s" % getattr(nm, "connection_error", "?"), flush=True)
    sys.exit(4)


class FakeDoor:
    def __init__(self, did, x, y, w, h):
        self.id = did
        self.original_rect = pg.Rect(x, y, w, h)


class FakeItem:
    RESPAWN_TIME = 5.0

    def __init__(self, iid, x, y):
        self.id = iid
        self.is_active = True
        self.last_pickup_time = 0.0
        self.respawn_time_remaining = 0.0
        self.pos = pg.Vector2(x, y)

    def can_pickup(self, player_id):
        return self.is_active

    def get_effect(self, player):
        return {"message": "test-effect"}

    def get_state(self):
        return {
            "id": self.id,
            "type": "HEALTH_PACK",
            "pos": [self.pos.x, self.pos.y],
            "is_active": self.is_active,
            "respawn_time_remaining": self.respawn_time_remaining,
        }


class FakePlayer:
    """Stand-in for an AI player: the server applies melee damage through the game instance."""

    def __init__(self, pid, x, y):
        self.id = pid
        self.pos = pg.Vector2(x, y)
        self.health = 100
        self.armor = 0
        self.is_dead = False
        self.respawn_time = 0
        self.damage_boost_multiplier = 1.0

    def take_damage(self, damage, respawn_time=0):
        self.health = max(0, self.health - damage)
        if self.health <= 0:
            self.is_dead = True
            self.respawn_time = time.time() + (respawn_time or 3)
        return self.is_dead


class FakeItemManager:
    def __init__(self):
        self.items = {}

    def get_state(self):
        return {"items": [i.get_state() for i in self.items.values()]}


class FakeMap:
    def __init__(self):
        self.doors = []


class FakeGame:
    def __init__(self):
        self.game_map = FakeMap()
        self.item_manager = FakeItemManager()
        self.player = None
        self.other_players = {}
        self.ai_players = {}
        self.grenades = []


if WORLD:
    game = FakeGame()
    nm.game_instance = game
    orig_update_door = nm._update_door

    def traced_update_door(door_data, sender_id=None):
        orig_update_door(door_data, sender_id)
        if isinstance(door_data, dict):
            state = door_data.get("state", {})
            print("DOOR-SEEN id=%s progress=%s version=%s" % (
                door_data.get("door_id"), state.get("animation_progress"), state.get("version")
            ), flush=True)

    nm._update_door = traced_update_door

print("READY port=%d" % nm.socket.getsockname()[1], flush=True)

created = False
try:
    while True:
        if WORLD:
            if not created:
                with nm.lock:
                    pids = [p for p in list(nm.players.keys()) if p != 1]
                    pos = list(nm.players[pids[0]]["pos"]) if pids else None
                if pos:
                    game.game_map.doors.append(FakeDoor(0, pos[0] + 40, pos[1], 80, 20))
                    game.game_map.doors.append(FakeDoor(1, pos[0] + 900, pos[1] + 900, 80, 20))
                    game.item_manager.items[1] = FakeItem(1, pos[0], pos[1])
                    with nm.lock:
                        nm.players[3] = {
                            "pos": [pos[0] + 30, pos[1]],
                            "angle": 180.0,
                            "health": 100,
                            "ammo": 30,
                            "armor": 0,
                            "is_dead": False,
                            "name": "Dummy",
                            "weapon_type": "gun",
                            "grenades": 0,
                            "protection_end": 0,
                            "respawn_time": 0,
                        }
                    game.ai_players[3] = FakePlayer(3, pos[0] + 30, pos[1])
                    print("WORLD doors=2 items=1 dummy=3 at %.1f %.1f" % (pos[0], pos[1]), flush=True)
                    created = True
            try:
                nm.update_and_broadcast()
            except Exception as exc:  # keep the socket alive for the assertions
                print("BCAST-ERR %r" % (exc,), flush=True)
        time.sleep(0.02)
except KeyboardInterrupt:
    pass
finally:
    nm.stop()
