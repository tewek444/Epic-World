"""Procedural 16x16 block textures for Epic World.

Run: python tools/textures.py   (writes src/main/resources/assets/epicworld/textures/block)
Every texture is generated from a fixed seed, so re-running gives identical files.
Existing files are never overwritten (they may have been edited or replaced by hand); only missing textures are
written. Set EPICWORLD_TEXTURES_FORCE=1 to regenerate everything.
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


FORCE = os.environ.get("EPICWORLD_TEXTURES_FORCE") == "1"


ITEM_OUT = os.path.join(OUT, "..", "item")
os.makedirs(ITEM_OUT, exist_ok=True)


def save_item(name, img):
    path = os.path.join(ITEM_OUT, name + ".png")
    if os.path.exists(path) and not FORCE:
        return
    img.save(path)
    print("wrote item", name)


def save(name, img):
    path = os.path.join(OUT, name + ".png")
    if os.path.exists(path) and not FORCE:
        return
    img.save(path)
    print("wrote", name)


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


def save_double(name, img, force=False):
    """Splits a 16x32 drawing into the _top and _bottom textures of a two-block plant."""
    global FORCE
    old = FORCE
    FORCE = FORCE or force
    save(name + "_top", img.crop((0, 0, 16, 16)))
    save(name + "_bottom", img.crop((0, 16, 16, 32)))
    FORCE = old


def stems(name, stem, leaf, heights, head=None, plume=None, ragged=False, force=False):
    """
    A clump of a two-block plant drawn as one 16x32 picture, so stems run on across the seam. Stems have different
    heights (in pixels from the ground: 6 = 0.4 block, 12 = 0.75, 20 = 1.25, 30 = almost 2 blocks); leaves are long
    blades curving outwards. {@code head}: cattail spikes (several, at different heights); {@code plume}: feathery reed
    tops; {@code ragged}: dry, broken heads with loose fluff.
    """
    r = rng(name)
    img = Image.new("RGBA", (16, 32), (0, 0, 0, 0))
    xs = [1, 3, 5, 7, 8, 10, 12, 14]
    r.shuffle(xs)
    for i, height in enumerate(heights):
        x = xs[i % len(xs)]
        lean = r.choice((-1, 0, 1))
        top = 32 - height
        for y in range(31, top - 1, -1):
            px = x + (lean if y < top + height * 0.35 else 0)
            if 0 <= px < 16:
                img.putpixel((px, y), shade(stem, r.uniform(0.85, 1.12)))
        if head and height >= 18:
            # Brown spike a little below the tip, two pixels wide, with the bare tip above it.
            length = 5 + r.randint(0, 2)
            start = top + 2
            hx = x + (lean if start < top + height * 0.35 else 0)
            for y in range(start, min(31, start + length)):
                for dx in (0, 1):
                    px = hx + dx - (1 if hx >= 15 else 0)
                    c = shade(head, (0.82 if dx else 1.0) * r.uniform(0.9, 1.08))
                    if ragged and r.random() < 0.3:
                        c = shade(head, 1.35)
                    img.putpixel((max(0, min(15, px)), y), c)
            if ragged:
                for _ in range(3):
                    fx = max(0, min(15, hx + r.choice((-1, 2))))
                    fy = start + r.randint(0, length - 1)
                    img.putpixel((fx, fy), shade(head, 1.5))
        if plume and height >= 16:
            # Feathery panicle drooping to one side.
            side = r.choice((-1, 1))
            for k in range(6):
                for spread in range(0, 2 + k // 2):
                    px = x + (lean if top < top + height * 0.35 else 0) + side * spread
                    py = top + k
                    if 0 <= px < 16 and r.random() < 0.8:
                        img.putpixel((px, py), shade(plume, r.uniform(0.85, 1.15)))
    # Leaves: long blades from the ground, curving out; some long, some short.
    for blade in range(5):
        x = r.choice((2, 4, 6, 9, 11, 13))
        length = r.choice((8, 12, 16, 22))
        direction = r.choice((-1, 1))
        for k in range(length):
            y = 31 - k
            px = x + int(direction * (k / length) ** 2 * 3)
            if 0 <= px < 16:
                img.putpixel((px, y), shade(leaf, r.uniform(0.85, 1.1) * (0.9 if k % 4 == 0 else 1.0)))
    save_double(name, img, force)


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


def cracked_mud(name):
    """Dried mud: plates separated by a network of cracks (edges of a tiling Voronoi pattern)."""
    r = rng(name)
    n = value_noise(r, cells=4)
    plate = (120, 92, 66, 255)
    crack = (66, 48, 34, 255)
    rim = (150, 122, 92, 255)
    points = [(r.uniform(0, 16), r.uniform(0, 16)) for _ in range(5)]
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            ds = sorted(min(((x + 0.5 - px + ox) ** 2 + (y + 0.5 - py + oy) ** 2) ** 0.5
                            for ox in (-16, 0, 16) for oy in (-16, 0, 16)) for px, py in points)
            edge = ds[1] - ds[0]
            if edge < 0.9:
                c = crack
            elif edge < 1.8:
                c = rim
            else:
                c = shade(plate, 0.88 + 0.2 * n[y][x] + r.uniform(-0.03, 0.03))
            img.putpixel((x, y), c)
    save(name, img)


def bricks(name, base, mortar, rows=4):
    r = rng(name)
    n = value_noise(r, cells=4)
    img = Image.new("RGBA", (16, 16))
    h = 16 // rows
    for y in range(16):
        row = y // h
        offset = 4 if row % 2 else 0
        for x in range(16):
            if y % h == h - 1 or (x + offset) % 8 == 7:
                c = mortar
            else:
                brick = ((x + offset) // 8) * 7 + row * 3
                c = shade(base, 0.9 + 0.12 * ((brick * 37) % 5) / 4 + 0.1 * n[y][x] + r.uniform(-0.03, 0.03))
            img.putpixel((x, y), c)
    save(name, img)


MARBLE = (236, 234, 228)


def marble_pillar(name):
    """Fluted column: vertical grooves with a light ridge between them."""
    r = rng(name)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            flute = x % 4
            f = 0.86 if flute == 0 else 1.04 if flute == 2 else 0.97
            img.putpixel((x, y), shade(MARBLE, f * r.uniform(0.97, 1.02)))
    for x in range(16):
        img.putpixel((x, 0), shade(MARBLE, 0.84))
        img.putpixel((x, 15), shade(MARBLE, 0.84))
    save(name, img)


def marble_pillar_top(name):
    r = rng(name)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5))
            f = 0.85 if d > 6.5 else 0.93 if int(d) in (2, 5) else 1.03
            img.putpixel((x, y), shade(MARBLE, f * r.uniform(0.98, 1.02)))
    save(name, img)


def chiseled_marble(name):
    """A framed panel with a carved rosette."""
    r = rng(name)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            edge = min(x, y, 15 - x, 15 - y)
            f = 0.84 if edge == 0 else 1.06 if edge == 1 else 0.9 if edge == 2 else 1.0
            dx, dy = x - 7.5, y - 7.5
            rr = (dx * dx + dy * dy) ** 0.5
            if 2.2 < rr < 3.2 or (rr < 4.6 and abs(abs(dx) - abs(dy)) < 0.8):
                f *= 0.86
            img.putpixel((x, y), shade(MARBLE, f * r.uniform(0.98, 1.02)))
    save(name, img)


def cattail_torch(name):
    """Tall torch: green-brown stem, the burning spike at the top."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in (7, 8):
            if y <= 1:
                c = (255, 230, 120, 255) if x == 7 else (255, 196, 70, 255)
            elif y <= 5:
                c = (96, 52, 26, 255) if (x + y) % 2 else (140, 70, 30, 255)
                if y == 2:
                    c = (230, 120, 40, 255)
            else:
                c = (102, 128, 58, 255) if x == 7 else (84, 108, 46, 255)
            img.putpixel((x, y), c)
    save(name, img)


def glowworm_jar(name, top=False):
    r = rng(name)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    glass = (200, 230, 236, 110)
    rim = (230, 245, 250, 200)
    for y in range(16):
        for x in range(16):
            if top:
                if 4 <= x <= 11 and 4 <= y <= 11:
                    img.putpixel((x, y), (150, 110, 70, 255) if 6 <= x <= 9 and 6 <= y <= 9 else rim)
                continue
            if x in (0, 15) or y == 15:
                img.putpixel((x, y), rim)
            else:
                img.putpixel((x, y), glass)
    if not top:
        for _ in range(20):
            x, y = r.randrange(2, 14), r.randrange(4, 14)
            img.putpixel((x, y), (150, 245, 255, 255) if r.random() < 0.5 else (80, 200, 230, 230))
    save(name, img)


def roasted_root(name):
    """Item: a roasted, split cattail root."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    r = rng(name)
    for i in range(11):
        x, y = 3 + i, 12 - i
        for w in (-1, 0, 1):
            c = (196, 150, 92, 255) if w == 0 else (122, 78, 42, 255)
            if r.random() < 0.2:
                c = shade(c, 0.7)
            img.putpixel((max(0, min(15, x + w)), max(0, min(15, y))), c)
    for x, y in ((2, 13), (3, 14), (1, 13)):
        img.putpixel((x, y), (90, 60, 34, 255))
    save_item(name, img)


def slaked_lime(name):
    """Item: a small heap of white powder."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    r = rng(name)
    for y in range(6, 15):
        half = (y - 5) * 0.75
        for x in range(16):
            if abs(x - 7.5) <= half:
                f = 0.86 if abs(x - 7.5) > half - 1 or y == 14 else r.uniform(0.95, 1.05)
                img.putpixel((x, y), shade((236, 236, 228), f))
    save_item(name, img)


def main():
    # Willow swamp
    log_side("willow_log", (88, 84, 58), (60, 56, 38))
    log_top("willow_log_top", (70, 66, 46), (176, 160, 112), (150, 134, 92))
    planks("willow_planks", (168, 150, 104))
    leaves("willow_leaves", (156, 186, 84), (88, 126, 46))
    sapling("willow_sapling")
    strands("spanish_moss", (150, 158, 132), (176, 182, 160))
    strands("spanish_moss_tip", (150, 158, 132), (176, 182, 160), tip=True)
    # Two-block clumps: cattail (several spikes), reed (feathery tops) and their dry versions.
    stems("cattail", (88, 132, 56), (112, 158, 66), [6, 12, 20, 24, 27, 30, 18, 9], head=(104, 64, 36))
    stems("reed", (120, 150, 78), (132, 168, 84), [10, 16, 22, 26, 30, 29, 14, 7], plume=(150, 112, 124))
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
    # Marble building blocks, products of the swamp and the caves
    marble_pillar("marble_pillar")
    marble_pillar_top("marble_pillar_top")
    chiseled_marble("chiseled_marble")
    bricks("marble_bricks", (236, 234, 228, 255), (196, 194, 190, 255))
    cattail_torch("cattail_torch")
    glowworm_jar("glowworm_jar")
    glowworm_jar("glowworm_jar_top", top=True)
    roasted_root("roasted_cattail_root")
    slaked_lime("slaked_lime")
    # Dry rivers
    cracked_mud("cracked_mud")
    bands("siltstone", [(146, 134, 116, 255), (132, 120, 104, 255), (156, 144, 124, 255), (124, 114, 100, 255)], wave=0.25)
    stone("polished_siltstone", (150, 138, 120), (138, 126, 110), (162, 150, 130), speck=0.03, polished=True)
    bricks("siltstone_bricks", (148, 136, 118, 255), (104, 94, 82, 255))
    stems("dry_reeds", (190, 166, 112), (204, 182, 126), [10, 16, 22, 26, 30, 29, 14, 7], plume=(216, 204, 178))
    stems("dry_cattail", (170, 146, 98), (188, 164, 112), [6, 12, 20, 24, 27, 30, 18, 9], head=(122, 88, 58), ragged=True)


if __name__ == "__main__":
    main()
