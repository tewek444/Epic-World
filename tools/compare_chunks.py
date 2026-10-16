"""Compares block states of every chunk in an original region folder with a later copy.

Usage: python -I compare_chunks.py <original region dir> <new region dir>
Only chunks present in the original are checked; sections are compared by Y (palette + packed data decoded).
"""
import os, re, struct, sys, zlib, gzip, io


def read_nbt(buf):
    f = io.BytesIO(buf)

    def rd(n):
        return f.read(n)

    def payload(t):
        if t == 1: return struct.unpack('>b', rd(1))[0]
        if t == 2: return struct.unpack('>h', rd(2))[0]
        if t == 3: return struct.unpack('>i', rd(4))[0]
        if t == 4: return struct.unpack('>q', rd(8))[0]
        if t == 5: return struct.unpack('>f', rd(4))[0]
        if t == 6: return struct.unpack('>d', rd(8))[0]
        if t == 7:
            n = struct.unpack('>i', rd(4))[0]; return rd(n)
        if t == 8:
            n = struct.unpack('>H', rd(2))[0]; return rd(n).decode('utf-8', 'replace')
        if t == 9:
            et = rd(1)[0]; n = struct.unpack('>i', rd(4))[0]
            return [payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = rd(1)[0]
                if tt == 0: return out
                ln = struct.unpack('>H', rd(2))[0]; name = rd(ln).decode('utf-8', 'replace')
                out[name] = payload(tt)
        if t == 11:
            n = struct.unpack('>i', rd(4))[0]; return list(struct.unpack('>%di' % n, rd(4 * n)))
        if t == 12:
            n = struct.unpack('>i', rd(4))[0]; return list(struct.unpack('>%dq' % n, rd(8 * n)))
        raise ValueError(t)

    t = rd(1)[0]
    ln = struct.unpack('>H', rd(2))[0]; rd(ln)
    return payload(t)


def chunks(path):
    data = open(path, 'rb').read()
    for i in range(1024):
        off = struct.unpack('>I', data[i * 4:i * 4 + 4])[0]
        if not off: continue
        sec = (off >> 8) * 4096
        length = struct.unpack('>I', data[sec:sec + 4])[0]
        comp = data[sec + 4]
        raw = data[sec + 5:sec + 4 + length]
        if comp == 2: raw = zlib.decompress(raw)
        elif comp == 1: raw = gzip.decompress(raw)
        elif comp == 3: pass
        else: continue
        yield i, read_nbt(raw)


def blocks(section):
    bs = section.get('block_states')
    if not bs: return None
    palette = [p.get('Name', '?') + str(sorted(p.get('Properties', {}).items())) for p in bs['palette']]
    data = bs.get('data')
    if not data: return [palette[0]] * 4096
    bits = max(4, (len(palette) - 1).bit_length())
    per = 64 // bits
    out = []
    mask = (1 << bits) - 1
    for v in data:
        v &= (1 << 64) - 1
        for k in range(per):
            if len(out) == 4096: break
            out.append(palette[(v >> (k * bits)) & mask])
    return out


def main(old_dir, new_dir):
    checked = differ = missing = 0
    for name in sorted(os.listdir(old_dir)):
        if not re.match(r'r\.-?\d+\.-?\d+\.mca$', name): continue
        if os.path.getsize(os.path.join(old_dir, name)) < 8192: continue
        new_path = os.path.join(new_dir, name)
        new = dict(chunks(new_path)) if os.path.exists(new_path) else {}
        for i, tag in chunks(os.path.join(old_dir, name)):
            if tag.get('Status', '').replace('minecraft:', '') != 'full': continue
            checked += 1
            other = new.get(i)
            if other is None:
                missing += 1; continue
            a = {s['Y']: blocks(s) for s in tag.get('sections', [])}
            b = {s['Y']: blocks(s) for s in other.get('sections', [])}
            for y, blk in a.items():
                if blk is None: continue
                if b.get(y) != blk:
                    differ += 1
                    if differ <= 10: print('differs:', name, 'chunk', i, 'section', y)
                    break
    print('checked', checked, 'full chunks; differing', differ, '; missing', missing)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
