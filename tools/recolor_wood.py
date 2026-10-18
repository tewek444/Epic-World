"""Willow wood textures recoloured from the vanilla oak ones (door, trapdoor, stripped log, signs, boats).

Run: python tools/recolor_wood.py <minecraft client jar>
(the jar NeoForm unpacks, e.g. ~/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar)

Each pixel's channels are scaled by the ratio of the willow planks' mean colour to the oak planks' mean colour, so
shading and details stay as in vanilla. Existing files are never overwritten (set EPICWORLD_TEXTURES_FORCE=1).
"""
import io
import os
import sys
import zipfile

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "epicworld", "textures")
FORCE = os.environ.get("EPICWORLD_TEXTURES_FORCE") == "1"

# vanilla path -> (epicworld path, extra brightness)
FILES = {
    "block/oak_door_top.png": ("block/willow_door_top.png", 1.0),
    "block/oak_door_bottom.png": ("block/willow_door_bottom.png", 1.0),
    "block/oak_trapdoor.png": ("block/willow_trapdoor.png", 1.0),
    "block/stripped_oak_log.png": ("block/stripped_willow_log.png", 1.08),
    "block/stripped_oak_log_top.png": ("block/stripped_willow_log_top.png", 1.08),
    "item/oak_door.png": ("item/willow_door.png", 1.0),
    "item/oak_sign.png": ("item/willow_sign.png", 1.0),
    "item/oak_hanging_sign.png": ("item/willow_hanging_sign.png", 1.0),
    "item/oak_boat.png": ("item/willow_boat.png", 1.0),
    "item/oak_chest_boat.png": ("item/willow_chest_boat.png", 1.0),
    "entity/signs/oak.png": ("entity/signs/willow.png", 1.0),
    "entity/signs/hanging/oak.png": ("entity/signs/hanging/willow.png", 1.0),
    "gui/hanging_signs/oak.png": ("gui/hanging_signs/willow.png", 1.0),
    "entity/boat/oak.png": ("entity/boat/willow.png", 1.0),
    "entity/chest_boat/oak.png": ("entity/chest_boat/willow.png", 1.0),
}


def mean(img):
    px = [p for p in img.convert("RGBA").getdata() if p[3] > 0]
    return [sum(p[i] for p in px) / len(px) for i in range(3)]


def main(jar):
    with zipfile.ZipFile(jar) as z:
        def vanilla(path):
            return Image.open(io.BytesIO(z.read("assets/minecraft/textures/" + path))).convert("RGBA")

        oak = mean(vanilla("block/oak_planks.png"))
        willow = mean(Image.open(os.path.join(ROOT, "block", "willow_planks.png")))
        ratio = [willow[i] / oak[i] for i in range(3)]
        for source, (target, bright) in FILES.items():
            out = os.path.join(ROOT, target)
            if os.path.exists(out) and not FORCE:
                continue
            img = vanilla(source)
            pixels = [(min(255, int(r * ratio[0] * bright)), min(255, int(g * ratio[1] * bright)),
                       min(255, int(b * ratio[2] * bright)), a) for r, g, b, a in img.getdata()]
            img.putdata(pixels)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            img.save(out)
            print("wrote", target)


if __name__ == "__main__":
    main(sys.argv[1])
