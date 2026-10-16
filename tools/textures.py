"""Procedural 16x16 block textures for Epic World.

Run: python tools/textures.py   (writes src/main/resources/assets/epicworld/textures/block)
Every texture is generated from a fixed seed, so re-running gives identical files.
"""
import math
import os
import random

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "epicworld", "textures", "block")
os.makedirs(OUT, exist_ok=True)


def rng(name):
    return random.Random(sum(ord(c) * (i + 1) for i, c in enumerate(name)))


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(color, f):
    return tuple(clamp(c * f) for c in color[:3]) + ((color[3],) if len(color) > 3 else (255,))


def mix(a, b, t):
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def value_noise(r, size=16, cells=4):
    grid = [[r.random() for _ in range(cells + 1)] for _ in range(cells + 1)]
    for i in range(cells + 1):
        grid[i][cells] = grid[i][0]
        grid[cells][i] = grid[0][i]
    out = [[0.0] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            fx = x / size * cells
            fy = y / size * cells
            ix, iy = int(fx), int(fy)
            tx, ty = fx - ix, fy - iy
            tx = tx * tx * (3 - 2 * tx)
            ty = ty * ty * (3 - 2 * ty)
            a = grid[iy][ix] + (grid[iy][ix + 1] - grid[iy][ix]) * tx
            b = grid[iy + 1][ix] + (grid[iy + 1][ix + 1] - grid[iy + 1][ix]) * tx
            out[y][x] = a + (b - a) * ty
    return out


def save(name, img):
    img.save(os.path.join(OUT, name + ".png"))


def stone(name, base, dark, light, speck=0.12, cells=4, veins=None, polished=False):
    r = rng(name)
    n1 = value_noise(r, cells=cells)
    n2 = value_noise(r, cells=8)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            v = n1[y][x] * 0.65 + n2[y][x] * 0.35
            if polished:
                v = 0.5 + (v - 0.5) * 0.35
            c = mix(dark, light, v)
            if r.random() < speck:
                c = shade(c, 0.9 if r.random() < 0.5 else 1.08)
            img.putpixel((x, y), c)
    if veins:
        for color, count in veins:
            for _ in range(count):
                x = r.uniform(0, 16)
                y = r.uniform(0, 16)
                angle = r.uniform(0, math.pi)
                for step in range(r.randint(8, 18)):
                    px, py = int(x) % 16, int(y) % 16
                    img.putpixel((px, py), color)
                    angle += r.uniform(-0.6, 0.6)
                    x += math.cos(angle)
                    y += math.sin(angle) * 0.6
    if polished:
        for i in range(16):
            img.putpixel((i, 0), shade(light, 1.05))
            img.putpixel((0, i), shade(light, 1.05))
            img.putpixel((i, 15), shade(dark, 0.92))
            img.putpixel((15, i), shade(dark, 0.92))
    save(name, img)


def bands(name, colors, wave=0.6):
    r = rng(name)
    n = value_noise(r, cells=4)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            t = (y + math.sin(x * wave) * 1.5 + n[y][x] * 3) / 16 * len(colors) * 1.5
            c = colors[int(t) % len(colors)]
            if r.random() < 0.1:
                c = shade(c, 0.94)
            img.putpixel((x, y), c)
    save(name, img)


def log_side(name, base, dark):
    r = rng(name)
    img = Image.new("RGBA", (16, 16))
    for x in range(16):
        col = r.uniform(0.85, 1.12)
        for y in range(16):
            v = col * (0.95 + 0.1 * math.sin(y * 0.9 + x * 2.3))
            c = shade(base, v)
            if x % 4 == 0 or r.random() < 0.08:
                c = dark
            img.putpixel((x, y), c)
    save(name, img)


def log_top(name, bark, ring, core):
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5))
            if d > 6.5:
                c = bark
            else:
                c = ring if int(d) % 2 == 0 else core
            img.putpixel((x, y), c)
    save(name, img)


def planks(name, base):
    r = rng(name)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        row = y // 4
        for x in range(16):
            v = 1.0 + 0.06 * math.sin(x * 0.8 + row * 3) + r.uniform(-0.04, 0.04)
            c = shade(base, v)
            if y % 4 == 3:
                c = shade(base, 0.7)
            if (x + row * 5) % 16 == 0:
                c = shade(base, 0.75)
            img.putpixel((x, y), c)
    save(name, img)


def leaves(name, light, dark):
    r = rng(name)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            if r.random() < 0.82:
                c = mix(dark, light, r.random())
                # Drooping streaks: willow leaves hang in vertical strands.
                if (x + (y // 5)) % 3 == 0:
                    c = shade(c, 0.85)
                img.putpixel((x, y), c)
    save(name, img)


def strands(name, color, tip_color, tip=False, glow=None):
    r = rng(name)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for sx in (3, 6, 8, 11, 13):
        length = 16 if not tip else r.randint(8, 14)
        x = sx
        for y in range(length):
            if r.random() < 0.25:
                x += r.choice((-1, 1))
            x = max(1, min(14, x))
            c = color if y < length - 2 else tip_color
            img.putpixel((x, y), shade(c, r.uniform(0.85, 1.1)))
            if glow and r.random() < 0.18:
                img.putpixel((x, y), glow)
    save(name, img)


def cross_plant(name, stem, leaf, height=16, head=None):
    r = rng(name)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for blade in range(6):
        x = 2 + blade * 2 + r.randint(0, 1)
        top = 16 - r.randint(int(height * 0.6), height)
        lean = r.choice((-1, 0, 1))
        for y in range(15, top - 1, -1):
            px = x + (lean if y < top + 4 else 0)
            img.putpixel((max(0, min(15, px)), y), shade(leaf if blade % 2 else stem, r.uniform(0.85, 1.1)))
    if head:
        for y in range(1, 8):
            for x in range(7, 10):
                img.putpixel((x, y), shade(head, 0.85 if x == 9 else 1.0))
        img.putpixel((8, 0), shade(head, 0.7))
    save(name, img)


def sapling(name):
    r = rng(name)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    trunk = (96, 82, 52, 255)
    for y in range(9, 16):
        img.putpixel((8, y), trunk)
    leaf = (120, 160, 60, 255)
    for x in range(3, 14):
        for y in range(2, 9):
            if (x - 8) ** 2 / 30 + (y - 5) ** 2 / 10 < 1 and r.random() < 0.8:
                img.putpixel((x, y), shade(leaf, r.uniform(0.75, 1.15)))
    for x in (4, 6, 10, 12):
        for y in range(8, 8 + r.randint(2, 5)):
            img.putpixel((x, y), shade(leaf, 0.85))
    save(name, img)


def spike(name, thickness, up):
    widths = {"tip_merge": (1, 2), "tip": (1, 3), "frustum": (3, 5), "middle": (4, 6), "base": (5, 7)}
    narrow, wide = widths[thickness]
    r = rng(name)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    base = (206, 196, 168, 255)
    for y in range(16):
        t = y / 15
        half = narrow + (wide - narrow) * t
        if thickness in ("tip", "tip_merge"):
            half = max(0.5, half * (y / 15) ** 0.8) if y > 2 else (0.5 if y > 0 else 0)
        for x in range(16):
            if abs(x - 7.5) <= half:
                c = shade(base, 0.8 + 0.3 * (1 - abs(x - 7.5) / max(half, 0.5)) * r.uniform(0.9, 1.05))
                img.putpixel((x, y), c)
    if not up:
        img = img.transpose(Image.FLIP_TOP_BOTTOM)
    save(name, img)


def glowworms(name):
    r = rng(name)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for _ in range(26):
        x, y = r.randrange(16), r.randrange(16)
        bright = r.random() < 0.4
        img.putpixel((x, y), (150, 245, 255, 255) if bright else (70, 190, 220, 230))
    for _ in range(10):
        x, y = r.randrange(16), r.randrange(16)
        img.putpixel((x, y), (40, 90, 110, 160))
    save(name, img)


def main():
    # Willow swamp
    log_side("willow_log", (88, 84, 58), (60, 56, 38))
    log_top("willow_log_top", (70, 66, 46), (176, 160, 112), (150, 134, 92))
    planks("willow_planks", (168, 150, 104))
    leaves("willow_leaves", (156, 186, 84), (88, 126, 46))
    sapling("willow_sapling")
    strands("spanish_moss", (150, 158, 132), (176, 182, 160))
    strands("spanish_moss_tip", (150, 158, 132), (176, 182, 160), tip=True)
    cross_plant("cattail_bottom", (82, 128, 52), (104, 150, 64))
    cross_plant("cattail_top", (82, 128, 52), (104, 150, 64), height=12, head=(110, 70, 40))
    cross_plant("water_grass", (60, 130, 72), (84, 156, 88), height=11)
    stone("silt", (118, 104, 86), (92, 80, 64), (140, 126, 106), speck=0.25)
    # Marble caves
    stone("marble", (236, 234, 228), (214, 212, 206), (250, 249, 245), speck=0.05,
          veins=[((196, 196, 200, 255), 2)])
    stone("gray_marble", (170, 172, 176), (142, 144, 150), (196, 198, 202), speck=0.05,
          veins=[((226, 226, 230, 255), 2)])
    stone("blue_marble", (196, 214, 226), (168, 190, 206), (222, 234, 242), speck=0.05,
          veins=[((150, 176, 198, 255), 2)])
    stone("veined_marble", (232, 230, 224), (212, 210, 204), (248, 247, 242), speck=0.04,
          veins=[((70, 140, 160, 255), 2), ((176, 132, 80, 255), 1), ((120, 120, 128, 255), 1)])
    stone("polished_marble", (240, 238, 232), (226, 224, 218), (252, 251, 248), speck=0.02, polished=True)
    # Glowing caves
    stone("limestone", (204, 194, 166), (180, 170, 142), (224, 216, 190), speck=0.15)
    bands("wet_limestone", [(170, 168, 150, 255), (150, 150, 136, 255), (186, 184, 164, 255), (140, 142, 130, 255)])
    stone("mossy_limestone", (170, 176, 120), (110, 140, 70), (210, 200, 168), speck=0.2, cells=3)
    for thickness in ("tip_merge", "tip", "frustum", "middle", "base"):
        spike("limestone_spike_up_" + thickness, thickness, True)
        spike("limestone_spike_down_" + thickness, thickness, False)
    glowworms("glowworms")
    strands("glowing_threads", (90, 160, 180), (170, 250, 255), glow=(190, 255, 255, 255))
    strands("glowing_threads_tip", (90, 160, 180), (170, 250, 255), tip=True, glow=(190, 255, 255, 255))


if __name__ == "__main__":
    main()
