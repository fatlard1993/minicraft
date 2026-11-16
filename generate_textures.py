#!/usr/bin/env python3
"""Generate Minicraft's textures: the bench's top, sides and arm, the miniature item, and the icon.

The bench is a spruce worktable three quarters of a block high, with a cutting mat on top, ruled
every four pixels the way a mat is ruled, and a drawer in its side. From a back corner an iron arm
holds a spyglass over the mat like a loupe: the spyglass is vanilla's own in-hand art and the arm
an iron bar's rod, laid side by side in one sheet because the block atlas does not take item
textures. The miniature is a cottage on a plinth: oak walls, a brick roof and
chimney, a door and a window, every colour taken from the vanilla texture of what it is made of.

Pure stdlib PNG reader and writer, as the rest of the suite, so it runs without Pillow, and
deterministic: re-running produces identical bytes. Source pixels come straight out of the
vanilla jar.

Usage: python3 generate_textures.py [path/to/minecraft.jar]
"""

import glob
import os
import struct
import sys
import zipfile
import zlib
HERE = os.path.dirname(os.path.abspath(__file__))

CLEAR = (0, 0, 0, 0)
_JAR = None


def minecraft_version():
    """The version this mod targets, so the sprite is cut from the same jar the
    mod is built against rather than whatever happens to be cached."""
    path = os.path.join(HERE, "gradle.properties")
    if not os.path.exists(path):
        return None
    for line in open(path):
        key, sep, value = line.partition("=")
        if sep and key.strip() == "minecraft_version":
            return value.strip()
    return None


def find_jar():
    """Loom caches the remapped Minecraft jars after a build; that is where the
    vanilla art comes from. Override with an argument or $MINECRAFT_JAR."""
    global _JAR
    if _JAR:
        return _JAR
    if len(sys.argv) > 1:
        _JAR = sys.argv[1]
        return _JAR
    if os.environ.get("MINECRAFT_JAR"):
        _JAR = os.environ["MINECRAFT_JAR"]
        return _JAR
    cache = os.path.expanduser("~/.gradle/caches/fabric-loom")
    names = ("minecraft-merged.jar", "minecraft-client.jar")
    found = []
    version = minecraft_version()
    if version:
        for name in names:
            found += glob.glob(os.path.join(cache, version, name))
    if not found:
        for name in names:
            found += glob.glob(os.path.join(cache, "*", name))
    if not found:
        sys.exit("no cached Minecraft jar found: build the mod once, "
                 "or pass a jar path as the first argument")
    _JAR = max(found, key=os.path.getmtime)
    return _JAR


def vanilla(name):
    """Read assets/minecraft/textures/<name> out of the vanilla jar."""
    with zipfile.ZipFile(find_jar()) as jar:
        return decode_png(jar.read("assets/minecraft/textures/" + name))


def decode_png(data):
    """Minimal PNG reader: no interlacing, every colour type and bit depth
    vanilla actually ships. Returns rows of RGBA tuples."""
    pos = 8
    idat = b""
    width = height = depth = ctype = None
    palette = trns = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            width, height, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert interlace == 0, "interlaced PNG not supported"
        elif tag == b"PLTE":
            palette = body
        elif tag == b"tRNS":
            trns = body
        elif tag == b"IDAT":
            idat += body
        elif tag == b"IEND":
            break

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    stride = (width * channels * depth + 7) // 8
    step = max(1, (channels * depth) // 8)
    raw = zlib.decompress(idat)
    out = bytearray(stride * height)
    prev = bytearray(stride)
    p = 0
    for y in range(height):
        filt = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if filt == 1:
            for i in range(step, stride):
                line[i] = (line[i] + line[i - step]) & 0xFF
        elif filt == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif filt == 3:
            for i in range(stride):
                a = line[i - step] if i >= step else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif filt == 4:
            for i in range(stride):
                a = line[i - step] if i >= step else 0
                b = prev[i]
                c = prev[i - step] if i >= step else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line

    pixels = []
    if depth < 8:
        per = 8 // depth
        mask = (1 << depth) - 1
        for y in range(height):
            base = y * stride
            row = []
            for x in range(width):
                i = x * channels
                value = (out[base + i // per] >> (8 - depth * (i % per + 1))) & mask
                if ctype == 3:
                    r, g, b = palette[value * 3:value * 3 + 3]
                    a = trns[value] if trns and value < len(trns) else 255
                    row.append((r, g, b, a))
                else:
                    v = value * 255 // mask
                    row.append((v, v, v, 255))
            pixels.append(row)
        return pixels

    for y in range(height):
        base = y * stride
        row = []
        for x in range(width):
            i = base + x * channels
            if ctype == 6:
                row.append(tuple(out[i:i + 4]))
            elif ctype == 2:
                row.append((out[i], out[i + 1], out[i + 2], 255))
            elif ctype == 4:
                row.append((out[i], out[i], out[i], out[i + 1]))
            elif ctype == 0:
                row.append((out[i], out[i], out[i], 255))
            else:
                r, g, b = palette[out[i] * 3:out[i] * 3 + 3]
                a = trns[out[i]] if trns and out[i] < len(trns) else 255
                row.append((r, g, b, a))
        pixels.append(row)
    return pixels


def write_png(path, pixels):
    """pixels: rows of RGBA tuples."""
    height = len(pixels)
    width = len(pixels[0])
    raw = b"".join(b"\x00" + b"".join(bytes(px) for px in row) for row in pixels)

    def chunk(tag, body):
        c = tag + body
        return struct.pack(">I", len(body)) + c + struct.pack(">I", zlib.crc32(c))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    print("wrote %s (%dx%d)" % (path, width, height))


def scale(pixels, n):
    """Nearest neighbour only: these are pixel textures, never smooth them."""
    return [[px for px in row for _ in range(n)] for row in pixels for _ in range(n)]


def blank(size=16):
    return [[CLEAR] * size for _ in range(size)]


def stamp(sprite, art, left, top):
    """Lay art onto the sprite at (left, top); transparent source pixels leave
    the sprite alone."""
    for y, row in enumerate(art):
        for x, px in enumerate(row):
            if px[3] and 0 <= top + y < len(sprite) and 0 <= left + x < len(sprite[0]):
                sprite[top + y][left + x] = px
    return sprite


def shade(px, factor):
    """Darken (or brighten) a pixel, alpha untouched."""
    return tuple(min(255, int(c * factor)) for c in px[:3]) + (px[3],)


BLOCK = os.path.join(HERE, "src/main/resources/assets/minicraft-justfatlard/textures/block")
ITEM = os.path.join(HERE, "src/main/resources/assets/minicraft-justfatlard/textures/item")
ICON = os.path.join(HERE, "src/main/resources/assets/minicraft-justfatlard/icon.png")


def average(pixels):
    opaque = [px for row in pixels for px in row if px[3]]
    n = len(opaque)
    return tuple(sum(px[i] for px in opaque) // n for i in range(3)) + (255,)


def bench_top():
    planks = vanilla("block/spruce_planks.png")
    mat = vanilla("block/green_wool.png")
    out = [row[:] for row in planks]
    for y in range(2, 14):
        for x in range(2, 14):
            px = shade(mat[y][x], 0.85)
            # Ruled every four pixels, and a darker edge where the mat meets the wood.
            if (x - 2) % 4 == 0 or (y - 2) % 4 == 0:
                px = shade(px, 1.3)
            if x in (2, 13) or y in (2, 13):
                px = shade(mat[y][x], 0.6)
            out[y][x] = px
    return out


def bench_side():
    """The body is 12 pixels high and its sides take the top 12 rows: a lip of stripped spruce
    under the mat, the drawer, and a darker plinth along the floor."""
    planks = vanilla("block/spruce_planks.png")
    lip = vanilla("block/stripped_spruce_log.png")
    iron = average(vanilla("block/iron_block.png"))
    out = [row[:] for row in planks]
    for y in range(0, 2):
        for x in range(16):
            out[y][x] = shade(lip[y][x], 0.8)
    for y in range(3, 9):
        for x in range(3, 13):
            if y in (3, 8) or x in (3, 12):
                out[y][x] = shade(planks[y][x], 0.55)
    for x in (7, 8):
        out[5][x] = iron
        out[6][x] = shade(iron, 0.75)
    for y in range(10, 12):
        for x in range(16):
            out[y][x] = shade(planks[y][x], 0.7)
    return out


def bench_arm():
    """One sheet for the arm and the spyglass it holds. Columns 0-1 are vanilla's spyglass_model
    as it is, so the spyglass's elements keep vanilla's own UVs; columns 4-5 are an iron bar's rod,
    for the arm; columns 8-11 rows 0-3 a patch of iron block for the clamps. The rest stays clear
    and nothing samples it."""
    spyglass = vanilla("item/spyglass_model.png")
    bars = vanilla("block/iron_bars.png")
    block = vanilla("block/iron_block.png")
    out = blank()
    for y in range(16):
        for x in (0, 1):
            out[y][x] = spyglass[y][x]
        for x in (4, 5):
            out[y][x] = bars[y][x + 3]
    for y in range(4):
        for x in range(8, 12):
            out[y][x] = shade(block[y + 1][x - 7], 0.68)
    return out


def miniature():
    out = blank()
    stone = vanilla("block/smooth_stone.png")
    oak = vanilla("block/oak_planks.png")
    bricks = vanilla("block/bricks.png")
    door = average(vanilla("block/spruce_planks.png"))
    glass = (168, 206, 228, 255)
    for x in range(1, 15):
        out[13][x] = shade(stone[13][x], 1.15)
        out[14][x] = stone[14][x]
        out[15][x] = shade(stone[15][x], 0.8)
    for y in range(7, 13):
        for x in range(3, 13):
            px = oak[y][x]
            out[y][x] = shade(px, 0.75) if x in (3, 12) else px
    for y in range(9, 13):
        for x in (7, 8):
            out[y][x] = shade(door, 0.55)
    for y in (9, 10):
        for x in (4, 5, 10, 11):
            out[y][x] = glass
    roof = {6: (2, 13), 5: (3, 12), 4: (4, 11), 3: (5, 10), 2: (6, 9)}
    for y, (left, right) in roof.items():
        for x in range(left, right + 1):
            px = bricks[y][x]
            out[y][x] = shade(px, 0.7) if x in (left, right) or y == 6 else px
    for y in (1, 2, 3):
        for x in (10, 11):
            out[y][x] = shade(bricks[y][x], 0.8)
    return out


def main():
    write_png(os.path.join(BLOCK, "bench_top.png"), bench_top())
    write_png(os.path.join(BLOCK, "bench_side.png"), bench_side())
    write_png(os.path.join(BLOCK, "bench_arm.png"), bench_arm())
    item = miniature()
    write_png(os.path.join(ITEM, "miniature.png"), item)
    write_png(ICON, scale(item, 8))


if __name__ == "__main__":
    main()
