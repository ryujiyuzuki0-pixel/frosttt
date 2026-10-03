#!/usr/bin/env python3
"""
Builds the Reforged Frost resource pack (Minecraft 1.21.11+) from the bundled .bbmodel.

Hybrid rendering:
  * the base armor cubes (head shell, chest, arms, belt, legs, boots) are baked into the vanilla armor textures.
    The game animates those itself, so they sync with every pose and show up in the inventory preview.
  * everything vanilla cannot draw (spikes, horns, pauldrons, dust, rotated cubes) stays 3D and is shown by the
    plugin as animated ItemDisplays.
The display part of the model is split per body part (head, torso, dust, arms, hips, legs, boots). Each part becomes its
own item model whose pivot sits at the model centre, so the plugin can show every part with an
ItemDisplay and animate it (limb swing, floating dust). Each piece (helmet/chest/legs/boots) also gets
an inventory icon model. 

Run:  python3 tools/build_pack.py   (needs Pillow)
Out:  src/main/resources/pack.zip
"""
import base64, io, json, math, os, sys, zipfile
from PIL import Image

MODEL_REL = "src/main/resources/models/reforged_frost_armor.bbmodel"
HERE = os.path.dirname(os.path.abspath(__file__))


def find_root():
    """The project root is the folder that contains src/main/resources/models/... (works from tools/ or flat)."""
    for cand in (os.getcwd(), os.path.dirname(HERE), HERE):
        if os.path.isfile(os.path.join(cand, MODEL_REL)):
            return cand
    sys.exit("ERROR: " + MODEL_REL + " not found (looked in " + os.getcwd() + ", " + os.path.dirname(HERE) + ", " + HERE + ")")


ROOT = find_root()
MODEL = os.path.join(ROOT, MODEL_REL)
OUT = os.path.join(ROOT, "src/main/resources/pack.zip")
NS, NAME = "mythicarmor", "reforged_frost_armor"

d = json.load(open(MODEL))
dec = lambda t: Image.open(io.BytesIO(base64.b64decode(t["source"].split(",", 1)[1]))).convert("RGBA")
atlas = dec(d["textures"][0])
emis = dec(d["textures"][1])
ea = emis.getchannel("A").load()


def emissive(x1, y1, x2, y2):
    """True when most of the face is covered by the emissive texture (rendered fullbright)."""
    xa, xb = int(min(x1, x2)), int(math.ceil(max(x1, x2)))
    ya, yb = int(min(y1, y2)), int(math.ceil(max(y1, y2)))
    tot = hit = 0
    for y in range(ya, min(yb, emis.size[1])):
        for x in range(xa, min(xb, emis.size[0])):
            tot += 1
            hit += ea[x, y] > 0
    return tot > 0 and hit / tot >= 0.5


# ---------------- part layout: part id -> (bbmodel group, pivot in bbmodel px) ----------------
# pivot = the joint the part rotates around; it becomes the centre of the part's item model
PARTS = {
    "head":   ("helmet",     (0, 24, 0)),
    "torso":  ("torso",      (0, 24, 0)),
    "dust":   (None,         (0, 20, 0)),   # floating dust cubes, split out of the torso group
    "arm_r":  ("right_arm",  (5, 22, 0)),
    "arm_l":  ("left_arm",   (-5, 22, 0)),
    "hips":   ("hips",       (0, 12, 0)),      # only the baked belt: no display model, icon only
    "leg_r":  ("right_leg",  (2, 12, 0)),
    "leg_l":  ("left_leg",   (-2, 12, 0)),
    "boot_r": ("right_boot", (2, 12, 0)),
    "boot_l": ("left_boot",  (-2, 12, 0)),
}
PIECES = {
    "head_piece":  ["head"],
    "chest_piece": ["torso", "dust", "arm_r", "arm_l"],
    "legs_piece":  ["hips", "leg_r", "leg_l"],
    "feet_piece":  ["boot_r", "boot_l"],
}

# ---------------- element helpers ----------------
FLIPU = {"x": ("north", "south", "up", "down"), "z": ("east", "west")}
FLIPV = {"y": ("north", "south", "east", "west"), "z": ("up", "down")}
SWAP = {"x": ("east", "west"), "y": ("up", "down"), "z": ("north", "south")}


def norm(e):
    """(from, to, faces) with positive size; cubes stored mirrored get swapped faces/UVs."""
    f, t = list(map(float, e["from"])), list(map(float, e["to"]))
    faces = {k: dict(v) for k, v in e["faces"].items() if v and "uv" in v}
    for i, ax in enumerate("xyz"):
        if t[i] < f[i]:
            f[i], t[i] = t[i], f[i]
            for n in FLIPU.get(ax, ()):
                if n in faces:
                    u = faces[n]["uv"]; faces[n]["uv"] = [u[2], u[1], u[0], u[3]]
            for n in FLIPV.get(ax, ()):
                if n in faces:
                    u = faces[n]["uv"]; faces[n]["uv"] = [u[0], u[3], u[2], u[1]]
            a, b = SWAP[ax]
            fa, fb = faces.pop(a, None), faces.pop(b, None)
            if fb: faces[a] = fb
            if fa: faces[b] = fa
    inf = e.get("inflate", 0) or 0
    return [x - inf for x in f], [x + inf for x in t], faces


def convert(e, off):
    """bbmodel cube -> item-model element shifted by off (3-vector); None if nothing visible."""
    f, t, faces = norm(e)
    r = [float(x) for x in e.get("rotation", [0, 0, 0])]
    o = [float(x) for x in e.get("origin", [0, 0, 0])]
    el = {"from": [f[i] + off[i] for i in range(3)], "to": [t[i] + off[i] for i in range(3)], "faces": {}}
    if any(abs(a) > 1e-6 for a in r):
        el["rotation"] = {"x": r[0], "y": r[1], "z": r[2], "origin": [o[i] + off[i] for i in range(3)]}
    for n, fc in faces.items():
        x1, y1, x2, y2 = fc["uv"]
        if x1 == x2 or y1 == y2:
            continue
        out = {"uv": [x1 / 16, y1 / 16, x2 / 16, y2 / 16], "texture": "#0"}
        if fc.get("rotation"):
            out["rotation"] = int(fc["rotation"])
        if emissive(x1, y1, x2, y2):
            out["light_emission"] = 15
        el["faces"][n] = out
    return el if el["faces"] else None


# ---------------- assign every cube to a part via the outliner ----------------
els = {e["uuid"]: e for e in d["elements"]}
groups = {g["uuid"]: g["name"] for g in d["groups"]}
by_part = {p: [] for p in PARTS}
group_to_part = {g: p for p, (g, _) in PARTS.items() if g}


def walk(nodes, top):
    for n in nodes:
        if isinstance(n, dict):
            walk(n["children"], top or groups[n["uuid"]])
        else:
            e = els[n]
            if e.get("visibility", True) is False or e.get("export", True) is False:
                continue
            part = "dust" if e["name"].startswith("dust") else group_to_part.get(top)
            if part:
                by_part[part].append(e)


walk(d["outliner"], None)

# ---------------- bake the base cubes into vanilla armor layers ----------------
S = 2   # armor texture scale (64x32 * S)
BAKE = [  # (from, to, inflate): every cube that is baked is NOT also shown as a display
    ([-4, 12, -2], [4, 24, 2], 0.275), ([-4, 12, -2], [4, 24, 2], 0.575),                    # chest body (inner, outer)
    ([4, 12, -2], [8, 24, 2], 0.5), ([4, 11.5, -2], [8, 23.5, 2], 0.255),                    # arm + gauntlet
    ([-8, 12, -2], [-4, 24, 2], 0.5), ([-8, 11.5, -2], [-4, 23.5, 2], 0.255),                # mirrored (left) arm
    ([-4, 12, -2], [4, 24, 2], 0.2555),                                                      # belt
    ([0, 0.5, -2], [4, 14.5, 2], 0.255), ([-4, 0.5, -2], [0, 14.5, 2], 0.255),               # legs
    ([0, 0, -2], [4, 4, 2], 0.55), ([-4, 0, -2], [0, 4, 2], 0.55),                           # boot shaft
    ([-4, 24, -4], [4, 32, 4], 0.75),                                                        # head shell
]
cubes = [e for e in d["elements"] if e.get("type", "cube") == "cube"]


def find(frm, to, infl):
    for e in cubes:
        if e["from"] == frm and e["to"] == to and abs((e.get("inflate", 0) or 0) - infl) < 1e-4:
            return e
    raise SystemExit(f"base cube not found: {frm} {to} {infl}")


BAKED = {find(*b)["uuid"] for b in BAKE}


def face_img(face, name):
    x1, y1, x2, y2 = face["uv"]
    img = atlas.crop((int(min(x1, x2)), int(min(y1, y2)), int(max(x1, x2)), int(max(y1, y2))))
    std = name in ("up", "down")  # box-UV stores these reversed on both axes
    if (x1 > x2) != std:
        img = img.transpose(Image.FLIP_LEFT_RIGHT)
    if (y1 > y2) != std:
        img = img.transpose(Image.FLIP_TOP_BOTTOM)
    rot = face.get("rotation", 0)
    if rot:
        img = img.rotate(-rot, expand=True)
    return img


def box_rects(u, v, w, h, d_):
    return {
        "east": (u, v + d_, d_, h), "north": (u + d_, v + d_, w, h),
        "west": (u + d_ + w, v + d_, d_, h), "south": (u + 2 * d_ + w, v + d_, w, h),
        "up": (u + d_, v, w, d_), "down": (u + d_ + w, v, w, d_),
    }


def paste_cube(tex, e, u, v, w, h, d_, only=None, rows=None):
    """Composite cube e's faces onto tex using the vanilla armor box layout at (u,v).
    rows=(y0,y1) limits side faces to a vertical slice of the part (in part pixels, 0=top)."""
    layer = Image.new("RGBA", tex.size, (0, 0, 0, 0))
    for name, (rx, ry, rw, rh) in box_rects(u, v, w, h, d_).items():
        if only and name not in only:
            continue
        f = e["faces"].get(name)
        if not f or "uv" not in f:
            continue
        img = face_img(f, name)
        if rows and name in ("east", "north", "west", "south"):
            y0, y1 = rows
            sh = img.size[1]
            cy0, cy1 = round(sh * y0 / h), round(sh * y1 / h)
            img = img.crop((0, cy0, img.size[0], max(cy1, cy0 + 1)))
            ry, rh = ry + y0, y1 - y0
        img = img.resize((rw * S, rh * S), Image.NEAREST)
        layer.paste(img, (rx * S, ry * S))
    tex.alpha_composite(layer)


new_tex = lambda: Image.new("RGBA", (64 * S, 32 * S), (0, 0, 0, 0))
SIDES = ("east", "north", "west", "south", "down")
humanoid = new_tex()    # helmet (head), chest (body + arms), boots (lower legs)
leggings = new_tex()    # leggings (belt + legs)
paste_cube(humanoid, find([-4, 24, -4], [4, 32, 4], 0.75), 0, 0, 8, 8, 8)
for infl in (0.275, 0.575):
    paste_cube(humanoid, find([-4, 12, -2], [4, 24, 2], infl), 16, 16, 8, 12, 4)
paste_cube(humanoid, find([4, 12, -2], [8, 24, 2], 0.5), 40, 16, 4, 12, 4)
paste_cube(humanoid, find([4, 11.5, -2], [8, 23.5, 2], 0.255), 40, 16, 4, 12, 4)
paste_cube(humanoid, find([0, 0, -2], [4, 4, 2], 0.55), 0, 16, 4, 12, 4, only=SIDES, rows=(8, 12))
paste_cube(leggings, find([-4, 12, -2], [4, 24, 2], 0.2555), 16, 16, 8, 12, 4, only=SIDES, rows=(8, 12))
paste_cube(leggings, find([0, 0.5, -2], [4, 14.5, 2], 0.255), 0, 16, 4, 12, 4)

# what is left after baking is what the plugin draws as 3D displays
extra = {p: [e for e in by_part[p] if e["uuid"] not in BAKED] for p in PARTS}


def extents(elements):
    lo, hi = [1e9] * 3, [-1e9] * 3
    for el in elements:
        for i in range(3):
            lo[i] = min(lo[i], el["from"][i]); hi[i] = max(hi[i], el["to"][i])
    return lo, hi


TEX = {"0": f"{NS}:item/{NAME}", "particle": f"{NS}:item/{NAME}"}
part_models, icon_models = {}, {}
for pid, (_, pv) in PARTS.items():
    off = [8 - pv[i] for i in range(3)]
    elements = [x for x in (convert(e, off) for e in extra[pid]) if x]
    if not elements:
        print(f"{pid:7s}   0 elements  (fully baked, no display part)")
        continue
    lo, hi = extents(elements)
    flag = "  <-- outside -16..32!" if min(lo) < -16 or max(hi) > 32 else ""
    print(f"{pid:7s} {len(elements):3d} elements  range {[round(v) for v in lo]} .. {[round(v) for v in hi]}{flag}")
    part_models[pid] = {"textures": TEX, "elements": elements}

for piece, pids in PIECES.items():
    raw = [e for p in pids for e in by_part[p]]
    tmp = [x for x in (convert(e, (0, 0, 0)) for e in raw) if x]
    lo, hi = extents(tmp)
    centre = [(lo[i] + hi[i]) / 2 for i in range(3)]
    off = [8 - centre[i] for i in range(3)]
    elements = [x for x in (convert(e, off) for e in raw) if x]
    dim = max(hi[i] - lo[i] for i in range(3))
    s = round(max(0.4, min(1.5, 14 / dim)), 3)
    icon_models[piece] = {
        "textures": TEX,
        "elements": elements,
        "display": {
            "gui": {"rotation": [20, 200, 0], "scale": [s, s, s]},
            "ground": {"scale": [s * 0.5] * 3},
            "fixed": {"rotation": [0, 180, 0], "scale": [s, s, s]},
            "thirdperson_righthand": {"scale": [s * 0.5] * 3},
            "thirdperson_lefthand": {"scale": [s * 0.5] * 3},
            "firstperson_righthand": {"scale": [s * 0.5] * 3},
            "firstperson_lefthand": {"scale": [s * 0.5] * 3},
        },
    }


def png(im):
    b = io.BytesIO(); im.save(b, "PNG"); return b.getvalue()


js = lambda o: json.dumps(o, indent=1).encode()
mini = lambda o: json.dumps(o, separators=(",", ":")).encode()
item_def = lambda model: js({"model": {"type": "minecraft:model", "model": model}})
equip = lambda layer: js({"layers": {layer: [{"texture": f"{NS}:{NAME}"}]}})

files = {
    "pack.mcmeta": js({"pack": {"description": "Reforged Frost", "pack_format": 75,
                                "min_format": 75, "max_format": 9999,
                                "supported_formats": {"min_inclusive": 75, "max_inclusive": 9999}}}),
    f"assets/{NS}/textures/item/{NAME}.png": png(atlas),
    f"assets/{NS}/textures/entity/equipment/humanoid/{NAME}.png": png(humanoid),
    f"assets/{NS}/textures/entity/equipment/humanoid_leggings/{NAME}.png": png(leggings),
}
for pid, m in part_models.items():
    files[f"assets/{NS}/models/item/{NAME}_part_{pid}.json"] = mini(m)
    files[f"assets/{NS}/items/{NAME}_part_{pid}.json"] = item_def(f"{NS}:item/{NAME}_part_{pid}")
for piece, m in icon_models.items():
    files[f"assets/{NS}/models/item/{NAME}_{piece}_icon.json"] = mini(m)
    files[f"assets/{NS}/items/{NAME}_{piece}.json"] = item_def(f"{NS}:item/{NAME}_{piece}_icon")
    files[f"assets/{NS}/equipment/{NAME}_{piece}.json"] = equip("humanoid_leggings" if piece == "legs_piece" else "humanoid")

with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for k in sorted(files):
        zi = zipfile.ZipInfo(k, (2020, 1, 1, 0, 0, 0))   # fixed timestamp: identical content gives an identical pack hash
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        z.writestr(zi, files[k])
print("->", OUT, os.path.getsize(OUT) // 1024, "KB,", len(files), "files")
