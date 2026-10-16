"""Top-down shaded relief of a world area from region files (1 px = 1 block), written as PNG without extra libraries.
Usage: relief.py <region dir> x0 z0 x1 z1 out.png [old region dir to outline old chunks]
Height is MOTION_BLOCKING_NO_LEAVES (water surface included); water is tinted blue, steep steps show as dark lines."""
import sys, os, zlib, struct
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import compare_chunks as c

region = sys.argv[1]
x0, z0, x1, z1 = map(int, sys.argv[2:6])
out = sys.argv[6]
oldregion = sys.argv[7] if len(sys.argv) > 7 else None
W, H = x1 - x0, z1 - z0


def load(reg):
    data = {}
    for rx in range(x0 >> 9, (x1 >> 9) + 1):
        for rz in range(z0 >> 9, (z1 >> 9) + 1):
            p = os.path.join(reg, 'r.%d.%d.mca' % (rx, rz))
            if os.path.exists(p):
                for i, t in c.chunks(p):
                    data[(rx * 32 + (i & 31), rz * 32 + (i >> 5))] = t
    return data


def hm(t, name):
    raw = t.get('Heightmaps', {}).get(name)
    if not raw: return None
    n = len(raw)
    bits = next(b for b in range(1, 33) if (256 + 64 // b - 1) // (64 // b) == n)
    per = 64 // bits
    miny = t.get('yPos', -4) * 16
    return [((raw[i // per] & ((1 << 64) - 1)) >> ((i % per) * bits) & ((1 << bits) - 1)) + miny - 1 for i in range(256)]


chunks = load(region)
old = set(load(oldregion).keys()) if oldregion else set()
top = [[None] * W for _ in range(H)]
wet = [[False] * W for _ in range(H)]
for (cx, cz), t in chunks.items():
    a = hm(t, 'MOTION_BLOCKING_NO_LEAVES')
    f = hm(t, 'OCEAN_FLOOR')
    if not a: continue
    for i in range(256):
        x = cx * 16 + (i & 15) - x0
        z = cz * 16 + (i >> 4) - z0
        if 0 <= x < W and 0 <= z < H:
            top[z][x] = a[i]
            wet[z][x] = f is not None and a[i] > f[i] and a[i] - f[i] < 200 and f[i] < a[i]
rows = []
for z in range(H):
    row = bytearray([0])
    for x in range(W):
        h = top[z][x]
        if h is None:
            row += bytes((40, 40, 40)); continue
        hl = top[z][x - 1] if x > 0 and top[z][x - 1] is not None else h
        hu = top[z - 1][x] if z > 0 and top[z - 1][x] is not None else h
        shade = max(-1.0, min(1.0, ((h - hl) + (h - hu)) / 6.0))
        if wet[z][x]:
            r, g, b = 40, 90, 180
        else:
            t = max(0, min(1, (h - 190) / 120))
            r, g, b = int(90 + 120 * t), int(150 + 60 * t), int(70 + 130 * t)
        k = 1 + 0.45 * shade
        if abs(h - hl) > 3 or abs(h - hu) > 3: k *= 0.45
        cx, cz = (x + x0) >> 4, (z + z0) >> 4
        if old and (cx, cz) in old and (((x + x0) & 15) == 0 or ((z + z0) & 15) == 0):
            r, g, b = 230, 60, 60
        row += bytes((max(0, min(255, int(r * k))), max(0, min(255, int(g * k))), max(0, min(255, int(b * k)))))
    rows.append(bytes(row))
def chunk(tag, data):
    return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 2, 0, 0, 0)) + \
      chunk(b'IDAT', zlib.compress(b''.join(rows), 6)) + chunk(b'IEND', b'')
open(out, 'wb').write(png)
print('wrote', out, W, 'x', H)
