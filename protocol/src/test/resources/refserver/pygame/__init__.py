"""Stub pygame package: only Vector2/Rect shapes the server code touches."""


class Vector2:
    def __init__(self, *a):
        if len(a) == 1 and isinstance(a[0], (list, tuple)):
            self.x, self.y = float(a[0][0]), float(a[0][1])
        elif len(a) == 2:
            self.x, self.y = float(a[0]), float(a[1])
        else:
            self.x = self.y = 0.0


class Rect:
    def __init__(self, *a, **k):
        pass
