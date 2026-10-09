"""Stub pygame package: only Vector2/Rect shapes the server code touches."""

import math


class Vector2:
    def __init__(self, *a):
        if len(a) == 1 and isinstance(a[0], (list, tuple)):
            self.x, self.y = float(a[0][0]), float(a[0][1])
        elif len(a) == 1 and hasattr(a[0], "x"):
            self.x, self.y = float(a[0].x), float(a[0].y)
        elif len(a) == 2:
            self.x, self.y = float(a[0]), float(a[1])
        else:
            self.x = self.y = 0.0

    @staticmethod
    def _xy(o):
        if hasattr(o, "x"):
            return o.x, o.y
        return o[0], o[1]

    def __add__(self, o):
        x, y = self._xy(o)
        return Vector2(self.x + x, self.y + y)

    def __sub__(self, o):
        x, y = self._xy(o)
        return Vector2(self.x - x, self.y - y)

    def __mul__(self, s):
        return Vector2(self.x * s, self.y * s)

    __rmul__ = __mul__

    def __truediv__(self, s):
        return Vector2(self.x / s, self.y / s)

    def __neg__(self):
        return Vector2(-self.x, -self.y)

    def __iter__(self):
        yield self.x
        yield self.y

    def __getitem__(self, i):
        return (self.x, self.y)[i]

    def __eq__(self, o):
        if hasattr(o, "x"):
            return self.x == o.x and self.y == o.y
        return (self.x, self.y) == o

    def length(self):
        return math.hypot(self.x, self.y)

    def length_squared(self):
        return self.x * self.x + self.y * self.y

    def distance_to(self, o):
        x, y = self._xy(o)
        return math.hypot(self.x - x, self.y - y)

    def normalize(self):
        n = self.length()
        if n:
            self.x /= n
            self.y /= n
        return self

    def copy(self):
        return Vector2(self.x, self.y)

    def __repr__(self):
        return "Vector2(%r, %r)" % (self.x, self.y)


class Rect:
    def __init__(self, x=0, y=0, w=0, h=0, *a, **k):
        if isinstance(x, (list, tuple)):
            x, y, w, h = x[0], x[1], x[2], x[3]
        self.x, self.y, self.w, self.h = float(x), float(y), float(w), float(h)

    @property
    def left(self):
        return self.x

    @property
    def top(self):
        return self.y

    @property
    def right(self):
        return self.x + self.w

    @property
    def bottom(self):
        return self.y + self.h

    @property
    def width(self):
        return self.w

    @property
    def height(self):
        return self.h

    @property
    def centerx(self):
        return self.x + self.w / 2

    @property
    def centery(self):
        return self.y + self.h / 2

    @property
    def center(self):
        return Vector2(self.centerx, self.centery)

    def colliderect(self, o):
        return (self.x < o.x + o.w and self.x + self.w > o.x
                and self.y < o.y + o.h and self.y + self.h > o.y)

    def collidepoint(self, *p):
        x, y = (p[0], p[1]) if len(p) == 2 else (p[0][0], p[0][1])
        return self.x <= x <= self.x + self.w and self.y <= y <= self.y + self.h

    def clipline(self, *p):
        if len(p) == 2:
            (x1, y1), (x2, y2) = p
        else:
            x1, y1, x2, y2 = p
        t0, t1 = 0.0, 1.0
        dx, dy = x2 - x1, y2 - y1
        for pp, qq in ((-dx, x1 - self.x), (dx, self.x + self.w - x1),
                       (-dy, y1 - self.y), (dy, self.y + self.h - y1)):
            if pp == 0:
                if qq < 0:
                    return None
            else:
                t = qq / pp
                if pp < 0:
                    if t > t1:
                        return None
                    t0 = max(t0, t)
                else:
                    if t < t0:
                        return None
                    t1 = min(t1, t)
        if t0 > t1:
            return None
        return (x1 + t0 * dx, y1 + t0 * dy), (x1 + t1 * dx, y1 + t1 * dy)
