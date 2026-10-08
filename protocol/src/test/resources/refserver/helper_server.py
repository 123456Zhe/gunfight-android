"""Launches the real reference server (reference/network.py) with lightweight
stubs for its game-only imports (pygame/constants/utils), so the JVM client
can perform a true UDP handshake against the actual Python implementation."""

import importlib.util
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", ".."))
NETWORK_PY = os.path.join(REPO_ROOT, "reference", "network.py")

spec = importlib.util.spec_from_file_location("refnet", NETWORK_PY)
m = importlib.util.module_from_spec(spec)
sys.modules["refnet"] = m
spec.loader.exec_module(m)

nm = m.NetworkManager(is_server=True, server_name="IntegTest", player_name="Server")
print("READY port=%d" % m.SERVER_PORT, flush=True)
try:
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    pass
finally:
    nm.stop()
